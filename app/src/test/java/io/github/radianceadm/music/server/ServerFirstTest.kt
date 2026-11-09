package io.github.radianceadm.music.server

import io.github.radianceadm.music.App
import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.Plain
import io.github.radianceadm.music.split.Splitter
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.SocketTimeoutException

/**
 * Server first, for every app (owner, 2026-11-09): a YouTube song a joined server has comes from
 * that server (found by the same artist and title, asked once in a while, quickly), its parts kept
 * under the YouTube song's own key; and a song split here goes up to the servers you joined, once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class ServerFirstTest {
    private val server = MockWebServer()
    private val base get() = server.url("/").toString().trimEnd('/')
    private val app get() = App.instance

    @Before fun start() {
        server.start()
        ServerFirst.clear()
    }

    @After fun stop() {
        server.close()
        Servers.pretend(emptyList())
        ServerFirst.clear()
    }

    private fun json(body: String, code: Int = 200) = MockResponse(code, headersOf("Content-Type", "application/json"), body)

    private fun part(p: String) = """"$p": {"name": "Maki - Dilaw.$p.ogg", "size": 4, "url": "/files/Maki%20-%20Dilaw.$p.ogg"}"""

    private fun dilaw(split: Boolean, id: Long = 7) = """{"id": $id, "artist": "Maki", "title": "Dilaw", "file": "Maki - Dilaw.opus", "url": "/files/Maki%20-%20Dilaw.opus",
        "duration_ms": 228000, "split": $split, "parts": [], "lyrics": false, "word_times": false, "cover": null,
        "files": {${if (split) listOf("drums", "bass", "other", "vocals").joinToString(", ") { part(it) } else ""}}, "added_by": null}"""

    private val other = """{"id": 3, "artist": "Maki", "title": "Dilaw (Acoustic)", "file": "Maki - Dilaw (Acoustic).opus", "url": "/files/x.opus",
        "duration_ms": 1, "split": true, "parts": [], "lyrics": false, "word_times": false, "cover": null, "files": {}, "added_by": null}"""

    private fun page(vararg songs: String) = json("""{"server": "Bassi's server", "total": ${songs.size}, "offset": 0, "count": ${songs.size}, "songs": [${songs.joinToString(",")}]}""")

    private val fromYouTube = Track(Source.YOUTUBE, "dilawYT", "Dilaw", "Maki", seconds = 228)

    private fun joined(): Server = Server(base, "tok", "Bassi's server").also { Servers.pretend(listOf(it)) }

    @Test fun aSongTheServerHasComesFromThereAndIsAskedOnce() {
        joined()
        server.enqueue(page(other, dilaw(split = true)))
        val (s, song) = ServerFirst.copy(fromYouTube)!!
        assertEquals("Bassi's server", s.name)
        assertEquals(7L, song.id) // the same artist and title, not the acoustic one
        val asked = server.takeRequest()
        assertEquals("/api/library", asked.url.encodedPath)
        assertEquals("Dilaw", asked.url.queryParameter("q"))
        assertEquals("Bearer tok", asked.headers["Authorization"])
        // known now: not asked again
        assertNotNull(ServerFirst.copy(fromYouTube))
        assertEquals(1, server.requestCount)
    }

    @Test fun aSongItDoesntHaveGoesToYouTubeAndIsntAskedAgainForAWhile() {
        joined()
        server.enqueue(page(other))
        assertNull(ServerFirst.copy(fromYouTube))
        assertNull(ServerFirst.copy(fromYouTube))
        assertEquals(1, server.requestCount)
        assertTrue(ServerFirst.fresh(ServerFirst.Asked(null, null, 0), ServerFirst.NOT_THERE_MS - 1))
        assertFalse(ServerFirst.fresh(ServerFirst.Asked(null, null, 0), ServerFirst.NOT_THERE_MS))
    }

    @Test fun noServerJoinedNothingIsAsked() {
        assertNull(ServerFirst.copy(fromYouTube))
        assertEquals(0, server.requestCount)
    }

    @Test fun aServerThatDidntAnswerIsLeftAloneAMoment() {
        val a = Server("http://a.example", "t", "A")
        val b = Server("http://b.example", "t", "B")
        val gone = Server("http://c.example", "t", "C")
        val now = 1_000_000L
        val ask = ServerFirst.askable(listOf(a, b, gone), mapOf(gone.base to Health.Removed("x")), mapOf(a.base to now - 5_000, b.base to now - ServerFirst.QUIET_MS), now)
        assertEquals(listOf(b), ask)
    }

    @Test fun onlyTheSameVersionCounts() {
        val s = Server("http://a.example", "t", "A")
        val acoustic = ServerSong.from(org.json.JSONObject(other)) { s.rms.id(it) }!!
        val song = ServerSong.from(org.json.JSONObject(dilaw(split = true))) { s.rms.id(it) }!!
        assertNull(ServerFirst.match(listOf(acoustic), fromYouTube))
        assertEquals(song, ServerFirst.match(listOf(acoustic, song), fromYouTube))
        // accents, case and spaces aside
        assertEquals(song, ServerFirst.match(listOf(song), fromYouTube.copy(title = "DILAW", artist = "Mäki")))
    }

    @Test fun theSearchIsTheTitlesWords() {
        assertEquals("Mood Swings  feat. Lil Tjay", ServerFirst.query(fromYouTube.copy(title = "Mood Swings (feat. Lil Tjay)")))
    }

    @Test fun aServersSongPlaysFromThereWithThisPhonesKey() {
        joined()
        server.enqueue(page(dilaw(split = false)))
        ServerFirst.queued(fromYouTube)
        val url = ServerFirst.stream("dilawYT")!!
        assertTrue(url, url.startsWith("$base/files/Maki%20-%20Dilaw.opus"))
        assertTrue(url, url.contains("t=tok"))
        assertNull(ServerFirst.stream("notQueued"))
        // it wouldn't play (out of the house): the same song from YouTube, that server not asked a moment
        assertTrue(ServerFirst.wouldntPlay("dilawYT"))
        assertNull(ServerFirst.stream("dilawYT"))
        assertEquals(1, server.requestCount)
        assertFalse(ServerFirst.wouldntPlay("dilawYT")) // YouTube's this time: an error there is a song that won't play
    }

    @Test fun itsPartsAreKeptAsTheYouTubeSongs() {
        val s = joined()
        repeat(4) { server.enqueue(MockResponse(200, headersOf("Content-Type", "audio/ogg"), "OggS")) }
        val song = ServerSong.from(org.json.JSONObject(dilaw(split = true))) { s.rms.id(it) }!!
        Splitter.parts(app, fromYouTube.key).values.forEach(File::delete)
        assertTrue(ServerSongs.keepParts(app, song, fromYouTube))
        assertTrue(Splitter.isSplit(app, fromYouTube.key))
        assertEquals(setOf("drums", "bass", "other", "vocals"), Splitter.parts(app, fromYouTube.key).keys)
        // kept already: nothing downloaded again
        assertFalse(ServerSongs.keepParts(app, song, fromYouTube))
        assertEquals(4, server.requestCount)
        Splitter.parts(app, fromYouTube.key).values.forEach(File::delete)
    }

    // ── songs split here, up to the servers you joined ──

    @Test fun aSongSplitHereIsWantedOnceForEachServerThatTakesSongs() {
        val a = Server("http://a.example", "t", "A")
        val b = Server("http://b.example", "t", "B", can = Can(add = false, split = true, host = false))
        val waiting = listOf(ServerUploads.Item("yt:2", a.first))
        val done = setOf(ServerUploads.Item("yt:3", a.first).id)
        val want = ServerUploads.wanted(listOf("yt:1", "yt:1", "yt:2", "yt:3"), listOf(a, b), waiting, done)
        assertEquals(listOf(ServerUploads.Item("yt:1", a.first)), want)
    }

    @Test fun whatsTriedAgainAndWhatIsnt() {
        assertEquals(ServerUploads.Went.LATER, ServerUploads.after(SocketTimeoutException("away from home")))
        assertEquals(ServerUploads.Went.LATER, ServerUploads.after(Refused(503, "busy")))
        assertEquals(ServerUploads.Went.LATER, ServerUploads.after(Refused(429, "slow down")))
        assertEquals(ServerUploads.Went.NEVER, ServerUploads.after(Refused(403, "not for you")))
        assertEquals(ServerUploads.Went.NEVER, ServerUploads.after(Refused(413, "too big")))
        assertEquals(ServerUploads.Went.NEVER, ServerUploads.after(Plain("not saved here")))
        val a = ServerUploads.Item("yt:1", "http://a")
        val b = ServerUploads.Item("yt:2", "http://b")
        assertEquals(b, ServerUploads.next(listOf(a, b), setOf("http://a")))
        assertNull(ServerUploads.next(listOf(a), setOf("http://a")))
    }

    /** Dilaw, liked and saved here, split here into the phone's two halves. */
    private fun splitHere(): Track {
        val f = File(app.filesDir, "Maki - Dilaw.m4a").apply { writeBytes(ByteArray(20_000) { (it % 97).toByte() }) }
        val t = fromYouTube.copy(file = "file://${f.absolutePath}")
        for (p in Splitter.PARTS) Splitter.partFileFor(app, t.key, p, "m4a").apply { parentFile?.mkdirs(); writeBytes(ByteArray(1_000) { 1 }) }
        return t
    }

    @Test fun notThereTheSongGoesUpAndTheServerSplitsIt() {
        val s = joined()
        val t = splitHere()
        assertTrue(ServerUploads.splitHere(app, t))
        server.enqueue(page(other))
        server.enqueue(json("""{"added": true, "skipped": false, "extras": [], "song": ${dilaw(split = false)}}""", 201))
        server.enqueue(json("""{"already_split": false, "queued": true, "job": {"id": 5, "song": 7, "artist": "Maki", "title": "Dilaw", "state": "waiting", "progress": 0, "note": ""}}"""))
        ServerUploads.one(app, s, t)
        assertEquals("/api/library", server.takeRequest().url.encodedPath)
        val up = server.takeRequest()
        assertEquals("/api/songs", up.url.encodedPath)
        assertNull(up.url.queryParameter("parts")) // no parts follow: the server splits it
        // the phone's two halves aren't a server's split: they don't go
        assertFalse(up.body!!.string(Charsets.ISO_8859_1).contains("name=\"vocals\""))
        val split = server.takeRequest()
        assertEquals("/api/split", split.url.encodedPath)
        assertEquals("""{"song":7}""", split.body!!.utf8())
        Splitter.parts(app, t.key).values.forEach(File::delete)
    }

    @Test fun thereAndSplitNothingGoes() {
        val s = joined()
        val t = splitHere()
        server.enqueue(page(dilaw(split = true)))
        ServerUploads.one(app, s, t)
        assertEquals(1, server.requestCount)
        Splitter.parts(app, t.key).values.forEach(File::delete)
    }

    @Test fun thereNotSplitItsAskedToSplitIt() {
        val s = joined()
        val t = splitHere()
        server.enqueue(page(dilaw(split = false)))
        server.enqueue(json("""{"already_split": false, "queued": true, "job": null}"""))
        ServerUploads.one(app, s, t)
        server.takeRequest()
        assertEquals("/api/split", server.takeRequest().url.encodedPath)
        Splitter.parts(app, t.key).values.forEach(File::delete)
    }
}

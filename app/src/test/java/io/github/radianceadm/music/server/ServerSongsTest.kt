package io.github.radianceadm.music.server

import android.content.Intent
import android.net.Uri
import io.github.radianceadm.music.App
import io.github.radianceadm.music.data.Saver
import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
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

/**
 * The phone's side of round 2, with the app itself: a liked song split here
 * goes to a server with its parts (or only its parts, when the server has it
 * without), a server at a new address takes your songs from it along (their
 * keys, covers, lyrics and parts), and the join page's link opens the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class ServerSongsTest {
    private val server = MockWebServer()
    private val base get() = server.url("/").toString().trimEnd('/')
    private val app get() = App.instance

    @Before fun start() = server.start()

    @After fun stop() {
        server.close()
        Servers.pretend(emptyList())
    }

    private fun json(body: String, code: Int = 200) = MockResponse(code, headersOf("Content-Type", "application/json"), body)

    private fun song(split: Boolean) = """{"id": 7, "artist": "Maki", "title": "Dilaw", "file": "Maki - Dilaw.m4a", "url": "/files/Maki%20-%20Dilaw.m4a",
        "duration_ms": 228000, "split": $split, "parts": [], "lyrics": false, "word_times": false, "cover": null, "files": {}, "added_by": {"id": 2, "name": "Ana"}}"""

    /** Dilaw, liked and saved on this phone, and split here (its four parts in the phone's splits). */
    private fun dilaw(split: Boolean = true): Track {
        val f = File(app.filesDir, "Maki - Dilaw.m4a").apply { writeBytes(ByteArray(40_000) { (it % 97).toByte() }) }
        val t = Track(Source.YOUTUBE, "dilaw1", "Dilaw", "Maki", seconds = 228, file = "file://${f.absolutePath}")
        if (split) for (p in listOf("drums", "bass", "other", "vocals")) Splitter.partFileFor(app, t.key, p, "m4a").apply { parentFile?.mkdirs(); writeBytes(ByteArray(10_000) { 1 }) }
        return t
    }

    @Test fun aSongSplitHereGoesWithItsParts() {
        val s = Server(base, "tok", "Bassi's server")
        val t = dilaw()
        server.enqueue(json("""{"added": true, "skipped": false, "extras": [], "song": ${song(split = false)}}""", 201))
        server.enqueue(json("""{"added": ["drums", "bass", "other", "vocals"], "skipped": [], "lyrics": false, "song": ${song(split = true)}}""", 201))
        var last = 0f
        val got = ServerSongs.upload(app, s, t) { last = it }
        assertTrue(got.song.added)
        assertEquals(listOf("drums", "bass", "other", "vocals"), got.parts?.added)
        assertEquals(1f, last, 0.001f)
        assertEquals("Added “Dilaw” to Bassi's server, with its parts. Everyone there can play it and mix it now.", ServerSongs.said(s, t, got))
        // the song, then its parts (they need its id there)
        assertEquals("/api/songs", server.takeRequest().url.encodedPath)
        val parts = server.takeRequest()
        assertEquals("/api/songs/7/parts", parts.url.encodedPath)
        val form = parts.body!!.string(Charsets.ISO_8859_1)
        for (p in listOf("drums", "bass", "other", "vocals")) assertTrue(p, form.contains("name=\"$p\"; filename=\"Maki - Dilaw.$p.m4a\""))
    }

    @Test fun theServerHasItSplitAlreadyOnlyTheSongIsAsked() {
        val s = Server(base, "tok", "Bassi's server")
        val t = dilaw()
        server.enqueue(json("""{"added": false, "skipped": true, "extras": [], "song": ${song(split = true)}}"""))
        val got = ServerSongs.upload(app, s, t)
        assertNull(got.parts)
        assertEquals(1, server.requestCount)
        assertEquals("Bassi's server has “Dilaw” already.", ServerSongs.said(s, t, got))
    }

    @Test fun theServerHasItWithoutPartsItsPartsGo() {
        val s = Server(base, "tok", "Bassi's server")
        val t = dilaw()
        val copy = ServerSong.from(org.json.JSONObject(song(split = false))) { base + it }!!
        // the add sheet's "Send its parts": split here, not there
        assertTrue(ServerSongs.canSendParts(app, t, copy))
        assertFalse(ServerSongs.canSendParts(app, t, copy.copy(split = true)))
        assertFalse(ServerSongs.canSendParts(app, dilaw(split = false).copy(id = "other"), copy))
        server.enqueue(json("""{"added": ["drums", "bass", "other", "vocals"], "skipped": [], "lyrics": false, "song": ${song(split = true)}}""", 201))
        val got = ServerSongs.sendParts(app, s, t, copy)!!
        assertEquals(4, got.added.size)
        assertEquals("/api/songs/7/parts", server.takeRequest().url.encodedPath)
        assertEquals("Bassi's server has “Dilaw” already; its parts went to it now, so everyone there can mix it.", ServerSongs.said(s, t, ServerSongs.Sent(Uploaded(false, true, emptyList(), copy), got)))
    }

    @Test fun aServersNewAddressTakesYourSongsAlong() {
        val old = "http://203.0.113.5:8080"
        val new = "https://music.bassi.ph"
        val was = Track(Source.SERVER, "$old/files/Maki%20-%20Dilaw.opus", "Dilaw", "Maki", art = "$old/files/Maki%20-%20Dilaw.jpg")
        app.library.like(was)
        app.library.played(was)
        app.library.saveSession(listOf(was), 0, 61_000)
        // what's kept by its key: its cover, its lyrics, its parts
        Saver.coverFile(app, was).apply { parentFile?.mkdirs(); writeText("cover") }
        Saver.lyricsFile(app, was).apply { parentFile?.mkdirs(); writeText("[00:01.00]Dilaw") }
        for (p in listOf("drums", "bass", "other", "vocals")) Splitter.partFileFor(app, was.key, p, "ogg").apply { parentFile?.mkdirs(); writeText(p) }

        ServerSongs.rebase(app, old, new)

        val now = app.library.liked.value.single()
        assertEquals("$new/files/Maki%20-%20Dilaw.opus", now.id)
        assertEquals("$new/files/Maki%20-%20Dilaw.jpg", now.art)
        assertTrue(now.key != was.key)
        assertEquals(now, app.library.history.value.single())
        assertEquals(now, app.library.session()!!.first.single())
        assertEquals("cover", Saver.coverFile(app, now).readText())
        assertEquals("[00:01.00]Dilaw", Saver.lyricsFile(app, now).readText())
        assertFalse(Saver.coverFile(app, was).exists())
        assertEquals(setOf("drums", "bass", "other", "vocals"), Splitter.parts(app, now.key).keys)
        assertTrue(Splitter.parts(app, was.key).isEmpty())
        // another server's songs stay as they were
        val other = Track(Source.SERVER, "https://other.example/files/x.opus", "X", "Y")
        app.library.like(other)
        ServerSongs.rebase(app, old, new)
        assertNotNull(app.library.liked.value.firstOrNull { it.key == other.key })
    }

    @Test fun theJoinPagesLinkOpensTheApp() {
        val link = "radiancemusic://join?u=" + java.net.URLEncoder.encode("https://music.bassi.ph/join/ABCD-EFGH-JKLM", "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
        val found = app.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
        assertTrue(found.toString(), "io.github.radianceadm.music.ui.MainActivity" in found)
        assertEquals("https://music.bassi.ph/join/ABCD-EFGH-JKLM", Servers.joinLink(link))
    }
}

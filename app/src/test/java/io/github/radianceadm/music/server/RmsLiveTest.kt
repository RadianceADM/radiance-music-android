package io.github.radianceadm.music.server

import io.github.radianceadm.music.net.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The app against a real Radiance Music Server (rms, on this computer only:
 * 127.0.0.1), end to end: set up, the owner's phone and a friend's join by
 * invite, a song added and streamed, a split asked for (and turned off by the
 * owner), a listening party with its WebSocket and clock, and leaving.
 * Runs only when RMS_URL (a fresh server) and RMS_SETUP_KEY are given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RmsLiveTest {
    private val url = System.getenv("RMS_URL")
    private val key = System.getenv("RMS_SETUP_KEY")

    @After fun clear() {
        Servers.pretend(emptyList())
    }

    private fun call(method: String, path: String, token: String?, body: JSONObject?): JSONObject {
        val req = Request.Builder().url(url.trimEnd('/') + path)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            .build()
        return Http.client.newCall(req).execute().use { Rms.answer(it) }
    }

    @Test fun endToEnd() {
        assumeTrue("RMS_URL and RMS_SETUP_KEY not given: no server to try", url != null && key != null)
        val base = Rms.origin(url)!!

        // the server, before setup: no name yet
        val before = Rms(base).status()
        assertTrue(before.needsSetup)

        // its owner sets it up on its page (a browser): their name, the setup key → their first invite, for the app
        val setup = call("POST", "/api/setup", null, JSONObject().put("name", "Bassi").put("key", key))
        val browser = setup.getString("token")
        val ownersLink = setup.getJSONObject("invite").getString("link")
        assertEquals("Bassi's server", setup.getJSONObject("server").getString("name"))

        // 1. the owner's phone joins with that link (as Settings → Servers → Join a server does)
        val owned = Servers.join(ownersLink, deviceName = "Samsung SM-S928B")
        assertEquals("Bassi's server", owned.name)
        assertTrue(owned.me!!.owner)
        assertEquals(Can(add = true, split = true, host = true), owned.can)
        assertEquals(listOf(owned), Servers.list.value)
        // its own id, the same at any address, kept
        assertNotNull(owned.id)
        assertEquals(Rms(base).status().id, owned.id)
        // the owner's phone hears the server's changes as they happen (in place of asking again and again)
        val heard = CopyOnWriteArrayList<Events.Event>()
        val listening = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { Events.watch(owned).collect { heard += it } }
        waitFor { heard.any { it is Events.Event.Hello } }
        assertEquals(Events.Event.Hello(owned.id, "Bassi's server"), heard.first { it is Events.Event.Hello })
        assertTrue(Events.up(owned.base).value)
        // the same invite again: used up, in the server's words
        val again = runCatching { Servers.join(ownersLink) }.exceptionOrNull()
        assertEquals(410, (again as Refused).code)
        assertTrue(again.words, again.words.contains("used already"))

        // 2. a friend: the owner invites Ana; her phone joins
        val invite = call("POST", "/api/invites", browser, JSONObject().put("name", "Ana"))
        val code = Servers.invite(invite.getString("link"))!!.code
        val joined = Rms(base).join(code, "Google Pixel 8")
        val ana = Rms(base, joined.token)
        val me = ana.me()
        assertEquals("Ana", me.person.name)
        assertFalse(me.person.owner)
        assertEquals("Bassi's server", me.server)

        // 3. Ana adds a song (one of her liked songs: the song, its cover); the second time it's skipped
        val song = System.getenv("RMS_SONG")?.let(::File)?.takeIf { it.exists() }
        val bytes = song?.readBytes() ?: ByteArray(48_000) { (it % 251).toByte() }
        val ext = song?.extension ?: "flac"
        fun upload() = ana.upload(
            Rms.Upload("Maki - Dilaw.$ext", bytes.size.toLong(), "audio/ogg") { bytes.inputStream() }, "Maki", "Dilaw", 5,
            cover = Rms.Upload("Maki - Dilaw.jpg", 4, "image/jpeg") { byteArrayOf(1, 2, 3, 4).inputStream() },
        )
        val up = upload()
        assertTrue(up.added)
        // the owner's phone heard: the library changed
        waitFor { Events.Event.Library in heard }
        assertEquals(listOf("cover"), up.extras)
        assertTrue(upload().skipped)
        val songs = Servers.library(owned)
        val dilaw = songs.single { it.title == "Dilaw" }
        assertEquals("Ana", dilaw.addedBy?.name)
        assertFalse(dilaw.split)
        assertNotNull(dilaw.cover)
        assertEquals(base + "/files/Maki%20-%20Dilaw.$ext", dilaw.url)
        // known now by its key (the Mixer, the lyrics and saving find it)
        assertEquals(dilaw, Servers.song(dilaw.track())?.second)
        // one song by its id, and a search there (a big library's way), not the whole library
        assertEquals(dilaw, owned.rms.song(dilaw.id))
        val search = owned.rms.page(q = "maki dilaw", limit = 50)
        assertEquals(1, search.total)
        assertEquals(dilaw.id, search.songs.single().id)
        assertEquals(0, owned.rms.page(q = "nothing like it").total)

        // 4. it streams, with the key in its address (as the player asks), and seeks (HTTP Range)
        val signed = Servers.sign(dilaw.url)
        assertTrue(signed.contains("t="))
        Http.client.newCall(Request.Builder().url(signed).build()).execute().use { r: Response ->
            assertEquals(200, r.code)
            assertEquals(bytes.size.toLong(), r.body.bytes().size.toLong())
        }
        Http.client.newCall(Request.Builder().url(signed).header("Range", "bytes=100-199").build()).execute().use { r ->
            assertEquals(206, r.code)
            assertEquals(100, r.body.bytes().size)
        }
        // a cover through the app's client: its key goes along by itself (Servers.auth)
        Http.client.newCall(Request.Builder().url(dilaw.cover!!).build()).execute().use { assertEquals(200, it.code) }
        // without a key: no
        Http.client.newBuilder().build().let { plain ->
            Servers.pretend(emptyList())
            plain.newCall(Request.Builder().url(dilaw.url).build()).execute().use { assertEquals(401, it.code) }
            Servers.pretend(listOf(owned))
        }

        // 5. Ana asks the server to split it: queued, its place and whose turn (the splitter isn't installed here: it waits)
        val asked = ana.split(dilaw.id)
        assertTrue(asked.queued)
        assertTrue(asked.job!!.waiting)
        // the owner's phone heard the queue, with it in it
        waitFor { heard.any { it is Events.Event.Queued && it.queue.of(dilaw.id) != null } }
        val q = ana.queue()
        val job = q.of(dilaw.id)!!
        assertEquals("Ana", job.askedBy?.name)
        assertEquals("Ana", q.nextTurn?.name)
        assertEquals(0, q.place(job))
        assertTrue(job.note, job.note.startsWith("Waiting"))
        // asked again: its place, not a second one
        assertFalse(ana.split(dilaw.id).queued)
        // she takes it out; the owner turns splitting off for her: a plain no
        ana.unqueue(job.id)
        // Ana's own line hears when the owner changes her switches
        val anasNews = CopyOnWriteArrayList<String>()
        val anasLine = ana.eventsSocket(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                anasNews += JSONObject(text).optString("type")
            }
        })
        waitFor { "hello" in anasNews }
        call("PATCH", "/api/people/${me.person.id}", browser, JSONObject().put("can_split", false))
        waitFor { "me" in anasNews }
        anasLine.close(1000, null)
        assertFalse(ana.me().can.split)
        val no = runCatching { ana.split(dilaw.id) }.exceptionOrNull() as Refused
        assertEquals(403, no.code)
        assertTrue(no.words, no.words.contains("Bassi") && no.words.contains("turned off splitting"))
        // but split on her phone, it brings its parts (the same switch as adding songs)
        heard.clear()
        val parts = listOf("drums", "bass", "other", "vocals").associateWith { p -> Rms.Upload("Maki - Dilaw.$p.m4a", 2_000, "audio/mp4") { ByteArray(2_000) { 7 }.inputStream() } }
        val brought = ana.uploadParts(dilaw.id, parts)
        assertEquals(listOf("drums", "bass", "other", "vocals"), brought.added)
        assertTrue(brought.song!!.split)
        assertTrue(owned.rms.song(dilaw.id).split)
        waitFor { Events.Event.Library in heard }
        // again: the server has them, so they're skipped
        assertEquals(4, ana.uploadParts(dilaw.id, parts).skipped.size)

        // 6. a listening party: Ana hosts it; one at a time
        val started = ana.startParty(everyoneControls = false)
        assertTrue(started.on)
        assertEquals("Ana", started.host?.name)
        // the owner's phone heard it on the events line (its party card needn't ask)
        waitFor { Events.Event.Party(true, "Ana") in heard }
        val twice = runCatching { owned.rms.startParty(false) }.exceptionOrNull() as Refused
        assertEquals(409, twice.code)
        // the owner's phone listens over the WebSocket
        val states = CopyOnWriteArrayList<PartyState>()
        val socket = owned.rms.partySocket(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = JSONObject(text)
                if (o.optString("type") == "state") states += PartyState.from(o, owned.rms::id)
            }
        })
        // its clock: a few pings, the quickest wins (the same computer: next to no offset to its wall clock)
        val pings = (1..6).map {
            val sent = System.currentTimeMillis()
            val server = owned.rms.time()
            PartyClock.Ping(sent, server, System.currentTimeMillis())
        }
        val best = PartyClock.best(pings)!!
        assertTrue("offset ${best.offset} ms", abs(best.offset) < 100)
        // a song added: it starts lead_ms (800) from now, the same moment for everyone
        val playing = ana.addToParty(dilaw.id)
        assertEquals("Dilaw", playing.song?.title)
        assertTrue(playing.playing)
        val startsIn = playing.startedAtServerMs!! - playing.serverMs
        assertTrue("starts in $startsIn ms", startsIn in 1..playing.leadMs)
        val at = PartyClock.target(playing, System.currentTimeMillis() + best.offset)!!
        assertTrue("at $at ms", at in -playing.leadMs..2_000)
        waitFor { states.any { it.song?.title == "Dilaw" && it.playing } }
        // the host pauses; the owner (not the host) may not, until she lets everyone
        val paused = ana.control("pause")
        assertFalse(paused.playing)
        assertNotNull(paused.pausedAtMs)
        val notHost = runCatching { owned.rms.control("play") }.exceptionOrNull() as Refused
        assertEquals(403, notHost.code)
        assertTrue(notHost.words, notHost.words.contains("Only the host (Ana)"))
        ana.control("allow", JSONObject().put("everyone", true))
        assertTrue(owned.rms.control("play").playing)
        waitFor { states.any { it.song != null && !it.playing } && states.last().playing }
        // a reaction everyone sees
        assertEquals("🔥", owned.rms.react("🔥").reactions.last().emoji)
        // ended: off on every phone
        assertFalse(ana.endParty().on)
        waitFor { states.lastOrNull()?.on == false }
        waitFor { Events.Event.Party(false, null) in heard }
        socket.close(1000, null)

        listening.cancel()

        // 7. the join page's "Open in Radiance Music": the app's own link, with the invite in it
        val rads = call("POST", "/api/invites", browser, JSONObject().put("name", "Rad")).getString("link")
        val page = Http.client.newCall(Request.Builder().url(rads).build()).execute().use { it.body.string() }
        val appLink = Regex("""radiancemusic://join\?u=[^"'<\s]+""").find(page)?.value?.replace("&amp;", "&")
        assertNotNull(page, appLink)
        assertEquals(rads, Servers.joinLink(appLink!!))
        assertEquals(Servers.invite(rads), Servers.invite(appLink))

        // 8. the server at a new address (its owner gave it a domain): the same id, so it moves; its songs keep their keys, what's fetched goes there
        val keyBefore = dilaw.track().key
        val newAddress = base.replace("127.0.0.1", "localhost")
        val moved = Servers.move(owned, newAddress)
        assertEquals(newAddress, moved.base)
        assertEquals(base, moved.first)
        assertEquals(owned.token, moved.token)
        assertEquals("Bassi's server", moved.rms.me().server)
        assertEquals(listOf(moved), Servers.list.value)
        val listedNow = Servers.page(moved, q = "maki dilaw").songs.single()
        assertEquals(keyBefore, listedNow.track().key)
        val signedNow = Servers.sign(listedNow.url)
        assertTrue(signedNow, signedNow.startsWith("$newAddress/files/"))
        Http.client.newCall(Request.Builder().url(signedNow).build()).execute().use { assertEquals(200, it.code) }
        Http.client.newCall(Request.Builder().url(listedNow.cover!!).build()).execute().use { assertEquals(200, it.code) }

        // 9. Ana's phone leaves: its key stops working there
        ana.leave(me.device)
        val gone = runCatching { ana.me() }.exceptionOrNull() as Refused
        assertTrue(gone.removed)
        // the owner's phone leaves too (the owner keeps the browser's device)
        Servers.leave(moved)
        assertTrue(Servers.list.value.isEmpty())
        assertTrue((runCatching { owned.rms.me() }.exceptionOrNull() as Refused).removed)
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!what()) {
            assertTrue("waited 5 s", System.nanoTime() < until)
            Thread.sleep(50)
        }
    }
}

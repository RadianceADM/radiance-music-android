package io.github.radianceadm.music.server

import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.net.Errors
import io.github.radianceadm.music.net.Http
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's side of a Radiance Music Server's API, against a pretend server
 * on this computer: joining, this phone's key on every call (and only where
 * it belongs), the library and the queue read right, split requests, adding
 * a song, the party's state over its WebSocket, and what's said when it goes
 * wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RmsTest {
    private val server = MockWebServer()
    private val base get() = server.url("/").toString().trimEnd('/')

    @Before fun start() = server.start()

    @After fun stop() {
        server.close()
        Servers.pretend(emptyList())
        Servers.onMoved = null
        Events.firstBackoff = 1000
    }

    private fun json(body: String, code: Int = 200) = MockResponse(code, headersOf("Content-Type", "application/json"), body)

    /** A song as rms's library.rs song_json makes it. */
    private val dilaw = """
        {"id": 7, "artist": "Maki", "title": "Dilaw", "file": "Maki - Dilaw.opus", "url": "/files/Maki%20-%20Dilaw.opus",
         "duration_ms": 228000, "split": true, "parts": ["drums", "bass", "other", "vocals", "music"], "lyrics": true, "word_times": true,
         "cover": "/files/Maki%20-%20Dilaw.jpg", "video": null,
         "files": {"song": {"name": "Maki - Dilaw.opus", "size": 3400000, "url": "/files/Maki%20-%20Dilaw.opus"},
                   "drums": {"name": "Maki - Dilaw.drums.ogg", "size": 900000, "url": "/files/Maki%20-%20Dilaw.drums.ogg"},
                   "lyrics": {"name": "Maki - Dilaw.lyrics.yaml", "size": 4000, "url": "/files/Maki%20-%20Dilaw.lyrics.yaml"},
                   "cover": {"name": "Maki - Dilaw.jpg", "size": 60000, "url": "/files/Maki%20-%20Dilaw.jpg"}},
         "added_by": {"id": 2, "name": "Ana"}, "added_ms": 1761900000000}
    """.trimIndent()

    @Test fun joinsWithAnInvite() {
        server.enqueue(json("""{"token": "tok-123", "person": {"id": 2, "name": "Ana", "role": "friend"}, "device": {"id": 5, "name": "Samsung SM-S928B"}, "server": {"name": "Bassi's server"}}""", 201))
        server.enqueue(json("""{"person": {"id": 2, "name": "Ana", "role": "friend"}, "device": {"id": 5, "name": "Samsung SM-S928B"}, "server": {"name": "Bassi's server"}, "can": {"add": true, "split": false, "host": true}}"""))
        val s = Servers.join("Join my server: $base/join/ABCD-EFGH-JKLM", deviceName = "Samsung SM-S928B")
        val join = server.takeRequest()
        assertEquals("POST", join.method)
        assertEquals("/api/join", join.url.encodedPath)
        // joining has no key yet
        assertNull(join.headers["Authorization"])
        val body = JSONObject(join.body!!.utf8())
        assertEquals("ABCD-EFGH-JKLM", body.getString("code"))
        assertEquals("Samsung SM-S928B", body.getString("device_name"))
        // then who it is, with its new key
        val me = server.takeRequest()
        assertEquals("/api/me", me.url.encodedPath)
        assertEquals("Bearer tok-123", me.headers["Authorization"])
        // kept: its address, its key, its name, what you can do there
        assertEquals(base, s.base)
        assertEquals("tok-123", s.token)
        assertEquals("Bassi's server", s.name)
        assertEquals("Ana", s.me?.name)
        assertEquals(5L, s.device)
        assertEquals(Can(add = true, split = false, host = true), s.can)
        assertEquals(listOf(s), Servers.list.value)
        assertEquals("Bassi", Servers.ownerOf(s))
    }

    @Test fun anInviteUsedUpSaysSoInTheServersWords() {
        server.enqueue(json("""{"error": "That invite was used already: each one works once. Ask the server's owner for a new one."}""", 410))
        try {
            Servers.join("$base/join/ABCD-EFGH-JKLM")
            fail("joined with a used invite")
        } catch (e: Refused) {
            assertEquals(410, e.code)
            assertEquals("That invite was used already: each one works once. Ask the server's owner for a new one.", Errors.server(e, "Bassi's server", offline = false))
        }
        assertTrue(Servers.list.value.isEmpty())
    }

    @Test fun readsInvites() {
        assertEquals(Servers.Invite("https://music.example.com", "ABCD-EFGH-JKLM"), Servers.invite("https://music.example.com/join/ABCD-EFGH-JKLM"))
        assertEquals(Servers.Invite("https://music.example.com", "ABCD-EFGH-JKLM"), Servers.invite("  Here you go: https://Music.Example.com:443/join/ABCD-EFGH-JKLM  "))
        // a server without a domain (http, its own port)
        assertEquals(Servers.Invite("http://203.0.113.5:8080", "ABCDEFGHJKLM"), Servers.invite("http://203.0.113.5:8080/join/ABCDEFGHJKLM"))
        // no https:// typed: a name gets https, a bare address http
        assertEquals("https://music.example.com", Servers.invite("music.example.com/join/ABCD-EFGH-JKLM")?.base)
        assertEquals("http://192.168.1.20:8080", Servers.invite("192.168.1.20:8080/join/ABCD-EFGH-JKLM")?.base)
        // not an invite
        assertNull(Servers.invite("https://music.example.com/party"))
        assertNull(Servers.invite("radiancemusic://share?v=1&m=get&k=abc"))
        assertNull(Servers.invite(""))
    }

    @Test fun everyCallCarriesTheKeyAndOnlyToItsServer() {
        val s = Server(base, "tok-xyz", "Bassi's server")
        Servers.pretend(listOf(s))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = json("""{"now_ms": 1761900000000, "songs": [], "person": {"id": 1, "name": "Bassi", "role": "owner"}}""")
        }
        s.rms.me()
        assertEquals("Bearer tok-xyz", server.takeRequest().headers["Authorization"])
        // the clock's pings and the status need no key
        Rms(base, "tok-xyz").time()
        assertNull(server.takeRequest().headers["Authorization"])
        // a cover or a download through the app's own client: its key goes along (Servers.auth)
        Http.client.newCall(Request.Builder().url("$base/files/Maki%20-%20Dilaw.jpg").build()).execute().close()
        assertEquals("Bearer tok-xyz", server.takeRequest().headers["Authorization"])
        // anywhere else: nothing added
        val other = MockWebServer().apply { start() }
        other.enqueue(MockResponse(200, headersOf(), "ok"))
        Http.client.newCall(Request.Builder().url(other.url("/x.jpg")).build()).execute().close()
        assertNull(other.takeRequest().headers["Authorization"])
        other.close()
        // the player's address: its key as ?t= (it can't send a header everywhere), never kept with the song
        assertEquals("$base/files/Maki%20-%20Dilaw.opus?t=tok-xyz", Servers.sign("$base/files/Maki%20-%20Dilaw.opus"))
        assertEquals("$base/api/library", Servers.sign("$base/api/library"))
        assertEquals("https://elsewhere.example/files/x.opus", Servers.sign("https://elsewhere.example/files/x.opus"))
    }

    @Test fun readsTheLibrary() {
        server.enqueue(json("""{"server": "Bassi's server", "count": 1, "songs": [$dilaw]}"""))
        val songs = Rms(base, "tok").library()
        assertEquals(1, songs.size)
        val d = songs[0]
        assertEquals(7L, d.id)
        assertEquals("Maki", d.artist)
        assertEquals("Dilaw", d.title)
        assertEquals(228_000L, d.durationMs)
        assertTrue(d.split)
        assertTrue(d.wordTimes)
        assertEquals("Ana", d.addedBy?.name)
        // every address made whole
        assertEquals("$base/files/Maki%20-%20Dilaw.opus", d.url)
        assertEquals("$base/files/Maki%20-%20Dilaw.jpg", d.cover)
        assertEquals("$base/files/Maki%20-%20Dilaw.drums.ogg", d.files["drums"]?.url)
        assertEquals("Maki - Dilaw.lyrics.yaml", d.files["lyrics"]?.name)
        // as the app's players hold it: from the server, a short key the same on every phone
        val t = d.track()
        assertEquals(Source.SERVER, t.source)
        assertEquals(d.url, t.playUri)
        assertEquals(228, t.seconds)
        assertEquals("Opus", t.format)
        assertTrue(t.key.startsWith("rms:") && t.key.length == 20)
        assertEquals(t, io.github.radianceadm.music.model.Track.fromJson(t.toJson()))
        assertEquals("/api/library", server.takeRequest().url.encodedPath)
    }

    @Test fun asksForASplit() {
        server.enqueue(json("""{"already_split": false, "queued": true, "job": {"id": 31, "song": {"id": 7, "artist": "Maki", "title": "Dilaw"}, "asked_by": {"id": 2, "name": "Ana"}, "state": "waiting", "progress": 0, "note": "Waiting: 2 songs before it."}}""", 201))
        val a = Rms(base, "tok").split(7)
        val req = server.takeRequest()
        assertEquals("/api/split", req.url.encodedPath)
        assertEquals("Bearer tok", req.headers["Authorization"])
        assertEquals(7, JSONObject(req.body!!.utf8()).getInt("song"))
        assertTrue(a.queued)
        assertEquals("Waiting: 2 songs before it.", a.job?.note)
        assertTrue(a.job!!.waiting)
        // split already
        server.enqueue(json("""{"already_split": true, "queued": false, "song": {"id": 7, "artist": "Maki", "title": "Dilaw"}}"""))
        assertTrue(Rms(base, "tok").split(7).alreadySplit)
        // the owner turned splitting off for this friend: the server's words
        server.enqueue(json("""{"error": "The server's owner (Bassi) turned off splitting songs for you. Ask them if you'd like it back on."}""", 403))
        try {
            Rms(base, "tok").split(7)
            fail("split while it's turned off")
        } catch (e: Refused) {
            assertEquals("The server's owner (Bassi) turned off splitting songs for you. Ask them if you'd like it back on.", Errors.say(e, offline = false))
        }
    }

    @Test fun readsTheQueueInItsTurns() {
        server.enqueue(json("""
            {"splitter": {"ready": true, "message": "Ready."}, "models": {"state": "ready", "message": "Ready."}, "threads": 4,
             "next_turn": {"id": 3, "name": "Rad"},
             "jobs": [
               {"id": 30, "song": {"id": 5, "artist": "Ben&Ben", "title": "Araw-Araw"}, "asked_by": {"id": 1, "name": "Bassi"}, "state": "splitting", "progress": 0.42, "note": "Splitting (42 %)."},
               {"id": 33, "song": {"id": 9, "artist": "Dionela", "title": "Sining"}, "asked_by": {"id": 3, "name": "Rad"}, "state": "waiting", "progress": 0, "note": "Next up."},
               {"id": 31, "song": {"id": 7, "artist": "Maki", "title": "Dilaw"}, "asked_by": {"id": 2, "name": "Ana"}, "state": "waiting", "progress": 0, "note": "Waiting: 1 song before it."},
               {"id": 20, "song": {"id": 4, "artist": "JVKE", "title": "Golden Hour"}, "asked_by": null, "state": "done", "progress": 1, "note": "Split into drums, bass, other and vocals; every word is timed."}
             ]}
        """.trimIndent()))
        val q = Rms(base, "tok").queue()
        assertEquals("Rad", q.nextTurn?.name)
        assertTrue(q.splitterReady)
        val dilaw = q.of(7)!!
        assertEquals(1, q.place(dilaw))
        assertEquals(0.42, q.of(5)!!.progress, 1e-9)
        assertTrue(q.of(5)!!.working)
        // a finished one isn't in the queue any more
        assertNull(q.of(4))
    }

    @Test fun addsASong() {
        server.enqueue(json("""{"added": true, "skipped": false, "extras": ["cover", "lyrics"], "song": $dilaw}""", 201))
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        var sent = 0L
        val up = Rms(base, "tok").upload(
            Rms.Upload("Maki - Dilaw.m4a", bytes.size.toLong(), "audio/mp4") { bytes.inputStream() }, "Maki", "Dilaw", 228,
            cover = Rms.Upload("Maki - Dilaw.jpg", 3, "image/jpeg") { byteArrayOf(1, 2, 3).inputStream() },
        ) { sent = it }
        assertTrue(up.added)
        assertEquals(listOf("cover", "lyrics"), up.extras)
        assertEquals(bytes.size + 3L, sent)
        val req = server.takeRequest()
        assertEquals("/api/songs", req.url.encodedPath)
        assertEquals("Bearer tok", req.headers["Authorization"])
        assertTrue(req.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val form = req.body!!.string(Charsets.ISO_8859_1)
        assertTrue(form.contains("name=\"song\"; filename=\"Maki - Dilaw.m4a\""))
        assertTrue(form.contains("name=\"cover\"; filename=\"Maki - Dilaw.jpg\""))
        assertTrue(form.contains("name=\"artist\"\r\nContent-Length: 4\r\n\r\nMaki") || form.contains("name=\"artist\"\r\n\r\nMaki"))
        assertTrue(form.contains("name=\"seconds\""))
    }

    @Test fun thePartysStateOverItsWebSocket() {
        val state = """
            {"type": "state", "on": true, "id": "p19a", "host": {"id": 1, "name": "Bassi"}, "everyone_controls": false,
             "song": {"entry": 3, "id": 7, "artist": "Maki", "title": "Dilaw", "file": "Maki - Dilaw.opus", "url": "/files/Maki%20-%20Dilaw.opus", "cover": "/files/Maki%20-%20Dilaw.jpg", "duration_ms": 228000, "added_by": {"id": 2, "name": "Ana"}},
             "playing": true, "started_at_server_ms": 1761900000800, "paused_at_ms": null, "position_ms": -800, "server_ms": 1761900000000, "lead_ms": 800,
             "queue": [{"entry": 4, "id": 9, "artist": "Dionela", "title": "Sining", "file": "Dionela - Sining.flac", "url": "/files/Dionela%20-%20Sining.flac", "cover": null, "duration_ms": 201000, "added_by": {"id": 1, "name": "Bassi"}}],
             "members": [{"id": 1, "name": "Bassi", "devices": 1, "host": true}, {"id": 2, "name": "Ana", "devices": 2, "host": false}],
             "reactions": [{"emoji": "🔥", "by": {"id": 2, "name": "Ana"}, "at_server_ms": 1761899990000}], "started_ms": 1761899000000, "version": 12}
        """.trimIndent()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(state)
            }

            // the phone leaves: the server closes its side too
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }).build())
        val got = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var text: String? = null
        val ws = Rms(base, "tok-ws").partySocket(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, msg: String) {
                text = msg
                got.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed.countDown()
        })
        assertTrue("the state came", got.await(5, TimeUnit.SECONDS))
        ws.close(1000, null)
        assertTrue("closed", closed.await(5, TimeUnit.SECONDS))
        val req = server.takeRequest()
        // the key as ?t= (a WebSocket can't carry a header everywhere)
        assertEquals("/api/party/ws", req.url.encodedPath)
        assertEquals("tok-ws", req.url.queryParameter("t"))
        val p = PartyState.from(JSONObject(text!!)) { if (it.startsWith("/")) base + it else it }
        assertTrue(p.on)
        assertEquals("Bassi", p.host?.name)
        assertEquals(1_761_900_000_800L, p.startedAtServerMs)
        assertNull(p.pausedAtMs)
        assertEquals(800L, p.leadMs)
        assertEquals("Dilaw", p.song?.title)
        assertEquals("$base/files/Maki%20-%20Dilaw.opus", p.song?.url)
        assertEquals("Ana", p.song?.addedBy?.name)
        assertEquals(listOf("Sining"), p.queue.map { it.title })
        assertEquals(2, p.members.size)
        assertTrue(p.members[0].host)
        assertEquals("🔥", p.reactions.single().emoji)
        assertEquals(12L, p.version)
        // the song starts 800 ms after the server said so: where it is then, by the party's clock
        assertEquals(-800L, PartyClock.target(p, p.serverMs))
    }

    @Test fun whatsSaidWhenItGoesWrong() {
        // removed: the server's own words, and the server marked so
        server.enqueue(json("""{"error": "This device isn't joined to the server, or it was removed. Ask the server's owner for a new invite."}""", 401))
        val s = Server(base, "tok", "Bassi's server")
        Servers.pretend(listOf(s))
        val e = runCatching { Servers.refresh(s) }.exceptionOrNull()
        assertTrue(e is Refused && e.removed)
        assertEquals(Health.Removed("This phone was removed from Bassi's server. Ask Bassi for a new invite."), Servers.health.value[s.base])
        // the server behind its proxy isn't running: no JSON, plain words still
        server.enqueue(MockResponse(502, headersOf("Content-Type", "text/html"), "<html>Bad Gateway</html>"))
        val down = runCatching { Rms(base, "tok").library() }.exceptionOrNull()
        assertEquals("The server isn't running right now, or it's starting. Try again in a minute.", Errors.server(down, "Bassi's server", offline = false))
        // not answering at all, the phone online: whose server, and what to do
        assertEquals("Bassi's server isn't answering. It may be turned off; ask its owner.", Errors.server(ConnectException("refused"), "Bassi's server", offline = false))
        // offline: that first
        assertEquals("You're offline. Connect to reach Bassi's server.", Errors.server(java.net.UnknownHostException("x"), "Bassi's server", offline = true))
        assertFalse(Errors.server(java.net.SocketTimeoutException("t"), "Bassi's server", offline = false).isEmpty())
        // not a Radiance Music Server
        server.enqueue(MockResponse(200, headersOf("Content-Type", "text/html"), "<html>hello</html>"))
        assertNotNull(runCatching { Rms(base).status() }.exceptionOrNull() as? Refused)
    }

    @Test fun leavingGivesTheKeyBack() {
        val s = Server(base, "tok", "Bassi's server", device = 5)
        Servers.pretend(listOf(s))
        server.enqueue(json("""{"ok": true}"""))
        Servers.leave(s)
        val req = server.takeRequest()
        assertEquals("/api/devices/5/revoke", req.url.encodedPath)
        assertEquals("Bearer tok", req.headers["Authorization"])
        assertTrue(Servers.list.value.isEmpty())
        // the owner's last device: the server says no, and it's kept (Forget it on this phone is the way then)
        Servers.pretend(listOf(s))
        server.enqueue(json("""{"error": "That's your last device on the server: keep it, so you can still manage the server."}""", 409))
        val no = runCatching { Servers.leave(s) }.exceptionOrNull()
        assertEquals("That's your last device on the server: keep it, so you can still manage the server.", (no as Refused).words)
        assertEquals(listOf(s), Servers.list.value)
        Servers.leave(s, onServer = false)
        assertTrue(Servers.list.value.isEmpty())
    }

    // ── round 2: the events line, parts, one song, a search, the app link, a server's new address ──

    private fun upgrade(vararg sends: String, close: Boolean = false) = MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sends.forEach { webSocket.send(it) }
            if (close) webSocket.close(1000, "bye")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }).build()

    private val queueEvent = """{"type": "queue", "splitter": {"ready": true, "message": "Ready."}, "models": {"state": "ready", "message": "Ready."}, "next_turn": {"id": 2, "name": "Ana"},
        "jobs": [{"id": 31, "song": {"id": 7, "artist": "Maki", "title": "Dilaw"}, "asked_by": {"id": 2, "name": "Ana"}, "state": "splitting", "progress": 0.5, "note": "Splitting (50 %)."}]}"""

    @Test fun theServersChangesComeAsTheyHappen() = kotlinx.coroutines.runBlocking {
        val s = Server(base, "tok-ev", "Bassi's server", id = "srv-1")
        Servers.pretend(listOf(s))
        server.enqueue(upgrade("""{"type": "hello", "server": {"id": "srv-1", "name": "Bassi's server"}}""", """{"type": "library"}""", queueEvent, """{"type": "me"}"""))
        val ups = mutableListOf<Boolean>()
        val got = kotlinx.coroutines.withTimeout(5_000) { Events.watch(s).onEach { ups += Events.up(s.base).value }.take(4).toList() }
        // up while it listened: the screens don't look again by themselves meanwhile
        assertEquals(listOf(true, true, true, true), ups)
        assertEquals(Events.Event.Hello("srv-1", "Bassi's server"), got[0])
        assertEquals(Events.Event.Library, got[1])
        val q = (got[2] as Events.Event.Queued).queue
        assertEquals("Splitting (50 %).", q.of(7)?.note)
        assertEquals("Ana", q.nextTurn?.name)
        assertEquals(Events.Event.Me, got[3])
        val req = server.takeRequest()
        assertEquals("/api/events", req.url.encodedPath)
        assertEquals("tok-ev", req.url.queryParameter("t"))
    }

    @Test fun theEventsLineJoinsAgainWhenItDrops() = kotlinx.coroutines.runBlocking {
        Events.firstBackoff = 50
        val s = Server(base, "tok-ev2", "Bassi's server")
        Servers.pretend(listOf(s))
        // the first line drops at once; the second brings the news
        server.enqueue(upgrade("""{"type": "hello", "server": {"id": "srv-1", "name": "Bassi's server"}}""", close = true))
        server.enqueue(upgrade("""{"type": "library"}"""))
        val got = kotlinx.coroutines.withTimeout(5_000) { Events.watch(s).take(2).toList() }
        assertEquals(listOf(Events.Event.Hello("srv-1", "Bassi's server"), Events.Event.Library), got)
        assertEquals(2, server.requestCount)
        // nobody listening now: the line's closed
        assertFalse(Events.up(s.base).value)
    }

    @Test fun removedTheEventsLineSaysSoAndStops() = kotlinx.coroutines.runBlocking {
        Events.firstBackoff = 50
        val s = Server(base, "tok-gone", "Bassi's server")
        Servers.pretend(listOf(s))
        server.enqueue(json("""{"error": "This device isn't joined to the server, or it was removed."}""", 401))
        val got = kotlinx.coroutines.withTimeout(5_000) { Events.watch(s).first() }
        assertEquals(Events.Event.Me, got)
        assertEquals(Health.Removed("This phone was removed from Bassi's server. Ask Bassi for a new invite."), Servers.health.value[s.base])
        Thread.sleep(300)
        // not tried again
        assertEquals(1, server.requestCount)
        assertFalse(Events.up(s.base).value)
    }

    @Test fun aSongSplitHereBringsItsParts() {
        server.enqueue(json("""{"added": ["drums", "bass", "other"], "skipped": ["vocals"], "lyrics": false, "song": $dilaw}""", 201))
        val parts = listOf("drums", "bass", "other", "vocals").associateWith { p -> Rms.Upload("Maki - Dilaw.$p.m4a", 5, "audio/mp4") { "12345".byteInputStream() } }
        var sent = 0L
        val got = Rms(base, "tok").uploadParts(7, parts) { sent = it }
        assertEquals(listOf("drums", "bass", "other"), got.added)
        assertEquals(listOf("vocals"), got.skipped)
        assertTrue(got.song!!.split)
        assertEquals(20L, sent)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/songs/7/parts", req.url.encodedPath)
        assertEquals("Bearer tok", req.headers["Authorization"])
        val form = req.body!!.string(Charsets.ISO_8859_1)
        for (p in listOf("drums", "bass", "other", "vocals")) assertTrue(p, form.contains("name=\"$p\"; filename=\"Maki - Dilaw.$p.m4a\""))
    }

    @Test fun oneSongAndASearchNotTheWholeLibrary() {
        server.enqueue(json(dilaw))
        val d = Rms(base, "tok").song(7)
        assertEquals("Dilaw", d.title)
        assertEquals("/api/songs/7", server.takeRequest().url.encodedPath)
        // a big library's page, and a search there
        server.enqueue(json("""{"server": "Bassi's server", "total": 1234, "offset": 0, "count": 1, "songs": [$dilaw]}"""))
        val page = Rms(base, "tok").page(q = "maki dilaw", limit = 200)
        assertEquals(1234, page.total)
        assertTrue(page.more)
        val req = server.takeRequest()
        assertEquals("maki dilaw", req.url.queryParameter("q"))
        assertEquals("200", req.url.queryParameter("limit"))
        // a song not known yet (played from History): found by its file's name, not by fetching every song
        val s = Server(base, "tok", "Bassi's server")
        Servers.pretend(listOf(s))
        server.enqueue(json("""{"server": "Bassi's server", "total": 1, "offset": 0, "count": 1, "songs": [$dilaw]}"""))
        val t = io.github.radianceadm.music.model.Track(Source.SERVER, "$base/files/Maki%20-%20Dilaw.opus", "Dilaw", "Maki")
        assertEquals(7L, Servers.find(t)?.second?.id)
        val search = server.takeRequest()
        assertEquals("/api/library", search.url.encodedPath)
        assertEquals("Maki - Dilaw", search.url.queryParameter("q"))
        // known now: one song, asked again by its id
        server.enqueue(json(dilaw))
        assertEquals(7L, Servers.fresh(s, 7).id)
        assertEquals("/api/songs/7", server.takeRequest().url.encodedPath)
    }

    @Test fun theJoinPagesAppLink() {
        val invite = "https://music.example.com/join/ABCD-EFGH-JKLM"
        val link = "radiancemusic://join?u=" + java.net.URLEncoder.encode(invite, "UTF-8")
        assertEquals(invite, Servers.joinLink(link))
        assertEquals(Servers.Invite("https://music.example.com", "ABCD-EFGH-JKLM"), Servers.invite(link))
        assertNull(Servers.joinLink("radiancemusic://share?v=1&m=get&k=abc"))
        assertNull(Servers.joinLink("radiancemusic://join"))
        assertNull(Servers.joinLink(invite))
    }

    @Test fun theSameServerAtANewAddressMovesNotAnother() {
        // two addresses for the one pretend server: 127.0.0.1 and localhost
        val port = server.port
        val old = "http://127.0.0.1:$port"
        val new = "http://localhost:$port"
        val s = Server(old, "tok-old", "Bassi's server", Person(2, "Ana"), device = 5, id = "srv-1")
        Servers.pretend(listOf(Server("https://other.example", "t9", "Rad's server", id = "srv-9"), s))
        val moves = mutableListOf<Pair<String, String>>()
        Servers.onMoved = { a, b -> moves += a to b }
        // a new invite at its new address: the same id, so it's the same server (the old key given back, the new one kept in its place)
        server.enqueue(json("""{"token": "tok-new", "person": {"id": 2, "name": "Ana", "role": "friend"}, "device": {"id": 8, "name": "Pixel"}, "server": {"name": "Bassi's server", "id": "srv-1"}}""", 201))
        server.enqueue(json("""{"person": {"id": 2, "name": "Ana", "role": "friend"}, "device": {"id": 8, "name": "Pixel"}, "server": {"name": "Bassi's server", "id": "srv-1"}, "can": {"add": true, "split": true, "host": true}}"""))
        server.enqueue(json("""{"ok": true}"""))
        val joined = Servers.join("$new/join/ABCD-EFGH-JKLM", deviceName = "Pixel")
        assertEquals(new, joined.base)
        assertEquals("srv-1", joined.id)
        assertEquals(listOf("Rad's server", "Bassi's server"), Servers.list.value.map { it.name })
        assertEquals(listOf(new), Servers.list.value.filter { it.id == "srv-1" }.map { it.base })
        assertEquals(listOf(old to new), moves)
        server.takeRequest(); server.takeRequest()
        val gave = server.takeRequest()
        assertEquals("/api/devices/5/revoke", gave.url.encodedPath)
        assertEquals("Bearer tok-old", gave.headers["Authorization"])

        // or its new address typed (Settings → the server → It moved): its id says it's the same; this phone's key goes on working
        Servers.pretend(listOf(s))
        moves.clear()
        server.enqueue(json("""{"server": "Bassi's server", "id": "srv-1", "needs_setup": false}"""))
        server.enqueue(json("""{"person": {"id": 2, "name": "Ana", "role": "friend"}, "device": {"id": 5, "name": "Pixel"}, "server": {"name": "Bassi's server", "id": "srv-1"}, "can": {"add": true, "split": false, "host": true}}"""))
        val moved = Servers.move(s, "localhost:$port".let { "http://$it" })
        assertEquals(new, moved.base)
        assertEquals("tok-old", moved.token)
        assertEquals(Can(split = false), moved.can)
        assertEquals(listOf(old to new), moves)
        assertEquals(listOf(moved), Servers.list.value)
        // another server answering there: no
        server.enqueue(json("""{"server": "Someone's server", "id": "srv-2", "needs_setup": false}"""))
        val no = runCatching { Servers.move(moved, "http://127.0.0.1:$port") }.exceptionOrNull()
        assertEquals("That's another server, not Bassi's server. Check the address with Bassi.", Errors.say(no, offline = false))
        assertEquals(listOf(moved), Servers.list.value)
        // a song's address there, rebased
        assertEquals("$new/files/x.opus", Servers.rebased("$old/files/x.opus", old, new))
        assertNull(Servers.rebased("https://other.example/files/x.opus", old, new))
    }
}

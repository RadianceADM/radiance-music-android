package io.github.radianceadm.music.server

import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.Http
import io.github.radianceadm.music.net.Plain
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * What a Radiance Music Server answered "no" with: its HTTP [code] and its
 * own plain words ({"error": "…"}), said to you as they are (a [Plain]).
 */
class Refused(val code: Int, words: String) : Plain(words, "HTTP $code") {
    /** This phone isn't joined any more (removed, or its owner removed it): its key doesn't work. */
    val removed get() = code == 401
}

/**
 * One Radiance Music Server's API (rms/README.md, "For app developers"), as
 * this phone: every call carries its key (`Authorization: Bearer …`); the
 * party's WebSocket and file addresses take `?t=…`. Plain OkHttp and JSON
 * underneath (no screens), so it's tested on a computer against a pretend
 * server and the real one. Every call waits for the network: never on the
 * main thread.
 */
class Rms(base: String, val token: String? = null, private val client: OkHttpClient = Http.client) {
    /** "https://music.example.com" (its scheme, host and port; nothing after). */
    val base: String = origin(base) ?: base.trimEnd('/')

    // ── people ──

    /** The server's name and state (no key needed). */
    fun status(): Status = Status.from(get("/api/status", auth = false))

    /** Joins with an invite's one-time [code], as [deviceName]: this phone's key. */
    fun join(code: String, deviceName: String): Joined =
        Joined.from(post("/api/join", JSONObject().put("code", code).put("device_name", deviceName), auth = false))

    /** Who this phone is there, and what the owner lets it do. */
    fun me(): Me = Me.from(get("/api/me"))

    /** This phone leaves: its key stops working (a friend may remove their own devices). */
    fun leave(device: Long) {
        post("/api/devices/$device/revoke", JSONObject())
    }

    // ── the library ──

    /** Every song on the server, A to Z by artist. */
    fun library(): List<ServerSong> {
        val o = get("/api/library")
        val list = o.optJSONArray("songs") ?: JSONArray()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let { s -> ServerSong.from(s, ::file) } }
    }

    /**
     * Adds a song to the server: its file ([song], [songName] like "Artist -
     * Title.m4a"), its details, and its cover and lyrics when there are.
     * [progress] gets the bytes sent so far. A song the server has already is
     * skipped (only the extras it lacks are kept).
     */
    fun upload(
        song: Upload, artist: String, title: String, seconds: Int,
        cover: Upload? = null, lyrics: Upload? = null, progress: (Long) -> Unit = {},
    ): Uploaded {
        var sent = 0L
        val counted = { n: Long -> sent += n; progress(sent) }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("artist", artist)
            .addFormDataPart("title", title)
            .apply { if (seconds > 0) addFormDataPart("seconds", seconds.toString()) }
            .addFormDataPart("song", song.name, song.body(counted))
            .apply { cover?.let { addFormDataPart("cover", it.name, it.body(counted)) } }
            .apply { lyrics?.let { addFormDataPart("lyrics", it.name, it.body(counted)) } }
            .build()
        // a big song on a slow connection: no time limit while it goes
        val slow = client.newBuilder().writeTimeout(0, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).build()
        return Uploaded.from(call(request("/api/songs").post(form).build(), slow), ::file)
    }

    /** A file to send: its name, its size (-1: not known) and how to read it. */
    class Upload(val name: String, val size: Long, val mime: String, val open: () -> InputStream) {
        fun body(moved: (Long) -> Unit) = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        moved(n.toLong())
                    }
                }
            }
        }
    }

    // ── splitting ──

    /** Asks the server to split [song] (its id): its place in the queue, or that it's split already. */
    fun split(song: Long): SplitAnswer = SplitAnswer.from(post("/api/split", JSONObject().put("song", song)))

    /** The split queue: the song at work, the waiting ones in their turns, the last day's finished. */
    fun queue(): Queue = Queue.from(get("/api/queue"))

    /** Takes a song out of the queue (whoever asked for it, or the owner). */
    fun unqueue(job: Long) {
        call(request("/api/queue/$job").delete().build())
    }

    // ── the listening party ──

    /** The server's clock (ms since 1970), for clock-offset pings (no key needed). */
    fun time(): Long = get("/api/time", auth = false).getLong("now_ms")

    fun party(): PartyState = PartyState.from(get("/api/party"), ::file)
    fun startParty(everyoneControls: Boolean): PartyState = PartyState.from(post("/api/party/start", JSONObject().put("everyone_controls", everyoneControls)), ::file)
    fun addToParty(song: Long): PartyState = PartyState.from(post("/api/party/add", JSONObject().put("song", song)), ::file)

    /** play, pause, skip, seek {position_ms}, reorder {entry, to}, remove {entry}, allow {everyone}. */
    fun control(action: String, extra: JSONObject = JSONObject()): PartyState =
        PartyState.from(post("/api/party/control", extra.put("action", action)), ::file)

    fun react(emoji: String): PartyState = PartyState.from(post("/api/party/react", JSONObject().put("emoji", emoji)), ::file)
    fun endParty(): PartyState = PartyState.from(post("/api/party/end", JSONObject()), ::file)

    /** The party's WebSocket: its whole state on every change (`?t=` carries the key: a WebSocket can't take a header everywhere). */
    fun partySocket(listener: WebSocketListener): WebSocket {
        val ws = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        return ws.newWebSocket(Request.Builder().url(withKey("$base/api/party/ws")).header("User-Agent", Http.USER_AGENT).build(), listener)
    }

    // ── addresses ──

    /** A file's address on the server ("/files/…") made whole: https://HOST/files/…. */
    fun file(path: String): String = if (path.startsWith("http://") || path.startsWith("https://")) path else base + (if (path.startsWith("/")) path else "/$path")

    /** [url] with this phone's key in it (`?t=`), for what can't send a header (a player, a picture). */
    fun withKey(url: String): String = token?.let { url.toHttpUrlOrNull()?.newBuilder()?.setQueryParameter("t", it)?.build()?.toString() } ?: url

    // ── underneath ──

    private fun request(path: String, auth: Boolean = true) = Request.Builder().url(file(path))
        .header("User-Agent", Http.USER_AGENT)
        .header("Accept", "application/json")
        .apply { if (auth && token != null) header("Authorization", "Bearer $token") }

    private fun get(path: String, auth: Boolean = true): JSONObject = call(request(path, auth).get().build())

    private fun post(path: String, body: JSONObject, auth: Boolean = true): JSONObject =
        call(request(path, auth).post(body.toString().toRequestBody(JSON)).build())

    private fun call(req: Request, with: OkHttpClient = client): JSONObject = with.newCall(req).execute().use { r -> answer(r) }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** "https://Music.Example.com:443/join/X" → "https://music.example.com" (scheme, host and port only); null: not an address. */
        fun origin(url: String): String? {
            val u = url.trim().toHttpUrlOrNull() ?: return null
            val port = if (u.port == okhttp3.HttpUrl.defaultPort(u.scheme)) "" else ":${u.port}"
            val host = if (u.host.contains(':')) "[${u.host}]" else u.host
            return "${u.scheme}://$host$port"
        }

        /** The server's answer as JSON, or what it said no with (its own words; else what its HTTP code means). */
        fun answer(r: Response): JSONObject {
            val text = runCatching { r.body.string() }.getOrDefault("")
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (r.isSuccessful) return json ?: throw Refused(r.code, "The server's answer didn't make sense. Is this a Radiance Music Server?")
            val words = json?.optString("error")?.takeIf { it.isNotBlank() } ?: when (r.code) {
                401 -> "This phone isn't joined to the server any more. Ask its owner for a new invite."
                403 -> "The server's owner doesn't let you do that."
                404 -> "The server didn't find that. Is this a Radiance Music Server?"
                413 -> "That's too big for the server."
                502, 503, 504 -> "The server isn't running right now, or it's starting. Try again in a minute."
                else -> "The server couldn't do that (it answered ${r.code}). Try again."
            }
            throw Refused(r.code, words)
        }

        /** "FLAC", "Opus", "AAC": what kind of file a name is, as the app shows it. */
        fun formatOf(file: String) = when (file.substringAfterLast('.', "").lowercase()) {
            "flac" -> "FLAC"
            "opus", "ogg" -> "Opus"
            "m4a", "mp4", "aac" -> "AAC"
            else -> ""
        }
    }
}

/** null for a missing key or JSON null (optString would say "null"). */
internal fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
internal fun JSONObject.long(k: String): Long? = if (isNull(k) || !has(k)) null else optLong(k)
private fun JSONObject.objects(k: String): List<JSONObject> = optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

/** What the owner lets this phone's person do (the owner: everything). */
data class Can(val add: Boolean = true, val split: Boolean = true, val host: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("add", add).put("split", split).put("host", host)

    companion object {
        fun from(o: JSONObject?) = Can(o?.optBoolean("add", true) ?: true, o?.optBoolean("split", true) ?: true, o?.optBoolean("host", true) ?: true)
    }
}

/** Someone on a server. */
data class Person(val id: Long, val name: String, val role: String = "friend") {
    val owner get() = role == "owner"
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("role", role)

    companion object {
        fun from(o: JSONObject?): Person? = o?.let { Person(it.optLong("id"), it.optString("name"), it.str("role") ?: "friend") }
    }
}

data class Joined(val token: String, val person: Person, val device: Long, val server: String?) {
    companion object {
        fun from(o: JSONObject) = Joined(
            o.str("token") ?: throw Refused(500, "The server didn't give this phone a key. Try again."),
            Person.from(o.optJSONObject("person")) ?: Person(0, ""),
            o.optJSONObject("device")?.optLong("id") ?: 0,
            o.optJSONObject("server")?.str("name"),
        )
    }
}

data class Me(val person: Person, val device: Long, val deviceName: String, val server: String?, val can: Can) {
    companion object {
        fun from(o: JSONObject) = Me(
            Person.from(o.optJSONObject("person")) ?: Person(0, ""),
            o.optJSONObject("device")?.optLong("id") ?: 0,
            o.optJSONObject("device")?.str("name").orEmpty(),
            o.optJSONObject("server")?.str("name"),
            Can.from(o.optJSONObject("can")),
        )
    }
}

/** GET /api/status: the server's name, and how its splitter is getting on. */
data class Status(val server: String?, val needsSetup: Boolean, val version: String, val splitterReady: Boolean, val splitterMessage: String, val models: String, val modelsMessage: String) {
    companion object {
        fun from(o: JSONObject): Status {
            val s = o.optJSONObject("splitter")
            val m = o.optJSONObject("models")
            return Status(o.str("server"), o.optBoolean("needs_setup"), o.optString("version"), s?.optBoolean("ready") ?: false, s?.str("message").orEmpty(), m?.str("state").orEmpty(), m?.str("message").orEmpty())
        }
    }
}

/** One of a song's files on the server: its name, its size, its whole address. */
data class ServerFile(val name: String, val size: Long, val url: String)

/** A song in a server's library, with its parts, lyrics, cover and who added it. */
data class ServerSong(
    val id: Long,
    val artist: String,
    val title: String,
    val file: String,
    /** The song's own file, whole address (no key in it). */
    val url: String,
    val durationMs: Long?,
    val split: Boolean,
    val parts: List<String>,
    val lyrics: Boolean,
    val wordTimes: Boolean,
    val cover: String?,
    /** kind (song, drums, bass, other, vocals, music, lyrics, cover, video) → the file. */
    val files: Map<String, ServerFile>,
    val addedBy: Person?,
) {
    /** As the app's players and lists hold a song: from this server, its file the address, its cover the picture. */
    fun track(): Track = Track(Source.SERVER, url, title, artist, seconds = ((durationMs ?: 0) / 1000).toInt(), art = cover.orEmpty(), format = Rms.formatOf(file))

    companion object {
        fun from(o: JSONObject, whole: (String) -> String): ServerSong? {
            val file = o.str("file") ?: return null
            val files = o.optJSONObject("files")?.let { f ->
                f.keys().asSequence().mapNotNull { k -> f.optJSONObject(k)?.let { e -> k to ServerFile(e.optString("name"), e.optLong("size"), whole(e.optString("url"))) } }.toMap()
            } ?: emptyMap()
            val parts = o.optJSONArray("parts")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            return ServerSong(
                o.optLong("id"), o.optString("artist"), o.optString("title"), file, whole(o.str("url") ?: "/files/$file"),
                o.long("duration_ms")?.takeIf { it > 0 }, o.optBoolean("split"), parts, o.optBoolean("lyrics"), o.optBoolean("word_times"),
                o.str("cover")?.let(whole), files, Person.from(o.optJSONObject("added_by")),
            )
        }
    }
}

/** A song in the split queue, and how it's getting on (the server's words: "Waiting: 2 songs before it."). */
data class Job(val id: Long, val song: Long, val artist: String, val title: String, val askedBy: Person?, val state: String, val progress: Double, val note: String) {
    val working get() = state == "splitting" || state == "timing"
    val waiting get() = state == "waiting"
    val open get() = working || waiting

    companion object {
        fun from(o: JSONObject): Job {
            val s = o.optJSONObject("song")
            return Job(o.optLong("id"), s?.optLong("id") ?: 0, s?.optString("artist").orEmpty(), s?.optString("title").orEmpty(), Person.from(o.optJSONObject("asked_by")), o.optString("state"), o.optDouble("progress", 0.0), o.optString("note"))
        }
    }
}

/** GET /api/queue: the jobs (at work, waiting in their turns, finished lately), whose turn is next, and the splitter's state. */
data class Queue(val jobs: List<Job>, val nextTurn: Person?, val splitterReady: Boolean, val splitterMessage: String, val modelsMessage: String) {
    /** [song]'s job still in the queue (at work or waiting), if there is one. */
    fun of(song: Long): Job? = jobs.firstOrNull { it.song == song && it.open }

    /** Its place among the waiting songs (0: next), or null. */
    fun place(job: Job): Int? = jobs.filter { it.waiting }.indexOfFirst { it.id == job.id }.takeIf { it >= 0 }

    companion object {
        fun from(o: JSONObject) = Queue(
            o.objects("jobs").map(Job::from), Person.from(o.optJSONObject("next_turn")),
            o.optJSONObject("splitter")?.optBoolean("ready") ?: false, o.optJSONObject("splitter")?.str("message").orEmpty(), o.optJSONObject("models")?.str("message").orEmpty(),
        )
    }
}

/** POST /api/split's answer: split already, or its job (new, or the one there was). */
data class SplitAnswer(val alreadySplit: Boolean, val queued: Boolean, val job: Job?) {
    companion object {
        fun from(o: JSONObject) = SplitAnswer(o.optBoolean("already_split"), o.optBoolean("queued"), o.optJSONObject("job")?.let(Job::from))
    }
}

/** POST /api/songs's answer: added, or skipped (the server has it), and the extras it kept. */
data class Uploaded(val added: Boolean, val skipped: Boolean, val extras: List<String>, val song: ServerSong?) {
    companion object {
        fun from(o: JSONObject, whole: (String) -> String) = Uploaded(
            o.optBoolean("added"), o.optBoolean("skipped"),
            o.optJSONArray("extras")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            o.optJSONObject("song")?.let { ServerSong.from(it, whole) },
        )
    }
}

/** A song in the party: playing, or waiting its turn ([entry]: its place's own number). */
data class PartySong(val entry: Long, val id: Long, val artist: String, val title: String, val file: String, val url: String, val cover: String?, val durationMs: Long?, val addedBy: Person?) {
    fun track(): Track = Track(Source.SERVER, url, title, artist, seconds = ((durationMs ?: 0) / 1000).toInt(), art = cover.orEmpty(), format = Rms.formatOf(file))

    companion object {
        fun from(o: JSONObject, whole: (String) -> String) = PartySong(
            o.optLong("entry"), o.optLong("id"), o.optString("artist"), o.optString("title"), o.optString("file"), whole(o.str("url") ?: "/files/${o.optString("file")}"),
            o.str("cover")?.let(whole), o.long("duration_ms")?.takeIf { it > 0 }, Person.from(o.optJSONObject("added_by")),
        )
    }
}

data class Member(val id: Long, val name: String, val devices: Int, val host: Boolean)

/** A reaction (an emoji, drawn as a soft picture) and when it came, by the server's clock. */
data class Reaction(val emoji: String, val by: Person?, val atServerMs: Long)

/**
 * The party as everyone sees it. A playing song has [startedAtServerMs]: the
 * server time its position was (or will be) 0; a paused one, [pausedAtMs].
 * Each change starts [leadMs] after it's made, so everyone has it in time.
 */
data class PartyState(
    val on: Boolean,
    val id: String? = null,
    val host: Person? = null,
    val everyoneControls: Boolean = false,
    val song: PartySong? = null,
    val playing: Boolean = false,
    val startedAtServerMs: Long? = null,
    val pausedAtMs: Long? = null,
    val serverMs: Long = 0,
    val leadMs: Long = 800,
    val queue: List<PartySong> = emptyList(),
    val members: List<Member> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val version: Long = 0,
) {
    companion object {
        val OFF = PartyState(on = false)

        fun from(o: JSONObject, whole: (String) -> String) = PartyState(
            on = o.optBoolean("on"),
            id = o.str("id"),
            host = Person.from(o.optJSONObject("host")),
            everyoneControls = o.optBoolean("everyone_controls"),
            song = o.optJSONObject("song")?.let { PartySong.from(it, whole) },
            playing = o.optBoolean("playing"),
            startedAtServerMs = o.long("started_at_server_ms"),
            pausedAtMs = o.long("paused_at_ms"),
            serverMs = o.optLong("server_ms"),
            leadMs = o.optLong("lead_ms", 800),
            queue = o.objects("queue").map { PartySong.from(it, whole) },
            members = o.objects("members").map { Member(it.optLong("id"), it.optString("name"), it.optInt("devices", 1), it.optBoolean("host")) },
            reactions = o.objects("reactions").map { Reaction(it.optString("emoji"), Person.from(it.optJSONObject("by")), it.optLong("at_server_ms")) },
            version = o.optLong("version"),
        )
    }
}

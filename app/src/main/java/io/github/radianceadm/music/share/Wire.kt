package io.github.radianceadm.music.share

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Songs going from one device to another, straight (same Wi-Fi, or the
 * phone's own Wi-Fi Direct network): no server in between.
 *
 * The device showing the QR code listens; the one scanning it connects. The
 * QR says which way the songs go: "get" (the scanner takes the songs shown)
 * or "put" (the scanner sends its songs here). Before anything moves, the
 * receiving side says what it has already: a song saved there is never sent
 * again, nor parts already split there; only what's missing goes.
 *
 * Plain Java underneath (no Android), so it's tested on a computer.
 */
object Wire {
    const val VERSION = 1
    /** What a song can come with: the song itself, its four parts, lyrics, cover, music video. */
    val KINDS = listOf("song", "drums", "bass", "other", "vocals", "lyrics", "cover", "video")
    /** Bigger than any song or video would be: refused. */
    const val MOST_BYTES = 4L shl 30

    data class FileInfo(val name: String, val size: Long)

    /** One song on offer: its key, its details (Track's JSON) and its files by kind. */
    data class Item(val key: String, val track: JSONObject, val files: Map<String, FileInfo>) {
        fun toJson(): JSONObject = JSONObject().put("key", key).put("track", track)
            .put("files", JSONObject().apply { files.forEach { (k, f) -> put(k, JSONObject().put("name", f.name).put("size", f.size)) } })

        companion object {
            fun fromJson(o: JSONObject): Item? {
                val key = o.optString("key").takeIf { it.isNotEmpty() } ?: return null
                val f = o.optJSONObject("files") ?: JSONObject()
                val files = f.keys().asSequence().filter { it in KINDS }.associateWith {
                    val e = f.getJSONObject(it)
                    FileInfo(e.optString("name"), e.optLong("size"))
                }.filterValues { it.size in 0..MOST_BYTES }
                return Item(key, o.optJSONObject("track") ?: JSONObject(), files)
            }
        }
    }

    /** What a device offers. */
    interface Offer {
        fun items(): List<Item>
        fun open(key: String, kind: String): InputStream?
    }

    /** Where songs land. */
    interface Store {
        /** The kinds of [item] already here (it's saved here: "song"; split here: the parts…). */
        fun have(item: Item): Set<String>
        /** [kind] of [item], [size] bytes from [input]. */
        fun write(item: Item, kind: String, input: InputStream, size: Long)
        /** All [item]'s files are here: it joins the library. */
        fun done(item: Item)
    }

    /** How far along: bytes done of all, songs done of all, the song now. */
    data class Progress(val bytes: Long, val totalBytes: Long, val songs: Int, val totalSongs: Int, val now: String?)

    /** A QR's words: `radiancemusic://share?…`. */
    data class Ticket(
        val mode: String, // "get" or "put"
        val host: String?,
        val port: Int,
        val key: String,
        val name: String,
        /** The phone's own Wi-Fi Direct network, to join when there's no Wi-Fi in common. */
        val group: String? = null,
        val pass: String? = null,
        /** Its address on that network (the group owner's, always 192.168.49.1 on Android). */
        val groupHost: String? = null,
    ) {
        fun toUri(): String = buildString {
            append("radiancemusic://share?v=$VERSION&m=$mode&k=$key&p=$port&n=").append(enc(name))
            host?.let { append("&h=").append(it) }
            group?.let { append("&g=").append(enc(it)) }
            pass?.let { append("&w=").append(enc(it)) }
            groupHost?.let { append("&gh=").append(it) }
        }

        companion object {
            fun parse(s: String): Ticket? {
                if (!s.startsWith("radiancemusic://share?")) return null
                val q = query(s.substringAfter('?'))
                if ((q["v"]?.toIntOrNull() ?: 0) != VERSION) return null
                val mode = q["m"]?.takeIf { it == "get" || it == "put" } ?: return null
                val key = q["k"]?.takeIf { it.length >= 16 } ?: return null
                val port = q["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                return Ticket(mode, q["h"], port, key, q["n"] ?: "a device", q["g"], q["w"], q["gh"])
            }
        }
    }

    fun newKey(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun query(s: String): Map<String, String> = s.split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** Bytes a second it may move now (0: as fast as it can). Checked as it goes. */
    fun interface Pace { fun limit(): Long }

    private fun copy(input: InputStream, out: OutputStream, size: Long, pace: Pace, moved: (Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var left = size
        var windowStart = System.nanoTime()
        var windowBytes = 0L
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) error("the song stopped coming (${size - left} of $size bytes)")
            out.write(buf, 0, n)
            left -= n
            moved(n.toLong())
            val limit = pace.limit()
            if (limit > 0) {
                windowBytes += n
                val should = windowBytes * 1_000_000_000L / limit
                val took = System.nanoTime() - windowStart
                if (should > took) Thread.sleep((should - took) / 1_000_000)
                if (took > 1_000_000_000L) { windowStart = System.nanoTime(); windowBytes = 0 }
            }
        }
    }

    /**
     * Listens for the one scanning the QR ([ticket]'s key lets it in, nothing
     * else does). "get": it serves [offer]. "put": songs sent come into [store].
     */
    class Server(
        private val mode: String,
        private val offer: Offer?,
        private val store: Store?,
        bind: InetAddress? = null,
        private val pace: Pace = Pace { 0 },
        private val onProgress: (Progress) -> Unit = {},
    ) {
        val key = newKey()
        private val socket = ServerSocket(0, 8, bind)
        val port: Int get() = socket.localPort
        private val pool = Executors.newFixedThreadPool(4)
        @Volatile private var open = true
        private val items: List<Item> by lazy { offer?.items() ?: emptyList() }
        private var plan: Map<String, Item> = emptyMap()
        @Volatile private var bytes = 0L
        @Volatile private var totalBytes = 0L
        @Volatile private var songs = 0

        fun start(): Server {
            Thread({
                while (open) {
                    val s = runCatching { socket.accept() }.getOrNull() ?: break
                    pool.execute { runCatching { s.use { serve(it) } } }
                }
            }, "radiance-share").apply { isDaemon = true }.start()
            return this
        }

        fun close() {
            open = false
            runCatching { socket.close() }
            pool.shutdownNow()
        }

        private fun serve(s: Socket) {
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream())
            val line = readLine(input) ?: return
            val (method, target) = line.split(' ').let { if (it.size < 2) return else it[0] to it[1] }
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(input) ?: return
                if (h.isEmpty()) break
                headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
            }
            val path = target.substringBefore('?')
            val q = if ('?' in target) query(target.substringAfter('?')) else emptyMap()
            val out = s.getOutputStream()
            if (!same(q["k"] ?: "", key)) return reply(out, 403, "not this share")
            val length = headers["content-length"]?.toLongOrNull() ?: 0L
            when {
                // get: the list, then each file asked for
                mode == "get" && method == "GET" && path == "/list" ->
                    reply(out, 200, JSONObject().put("v", VERSION).put("songs", JSONArray(items.map { it.toJson() })).toString())
                mode == "get" && method == "GET" && path == "/file" -> {
                    val item = items.firstOrNull { it.key == q["key"] } ?: return reply(out, 404, "no such song")
                    val kind = q["kind"]?.takeIf { it in item.files } ?: return reply(out, 404, "no such file")
                    val size = item.files.getValue(kind).size
                    val src = offer!!.open(item.key, kind) ?: return reply(out, 404, "gone")
                    src.use {
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: $size\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray())
                        val title = item.track.optString("title")
                        copy(it, out, size, pace) { n -> bytes += n; onProgress(Progress(bytes, 0, songs, 0, title)) }
                        out.flush()
                    }
                }
                // put: what's wanted of the list sent, then each file, then each song done
                mode == "put" && method == "POST" && path == "/plan" -> {
                    if (length !in 1..(64L shl 20)) return reply(out, 413, "too big")
                    val a = JSONObject(String(input.readNBytesCompat(length.toInt()))).getJSONArray("songs")
                    val offered = (0 until a.length()).mapNotNull { Item.fromJson(a.getJSONObject(it)) }
                    val want = JSONObject()
                    val planned = HashMap<String, Item>()
                    var total = 0L
                    for (item in offered) {
                        val missing = item.files.keys - store!!.have(item)
                        if (missing.isEmpty()) continue
                        planned[item.key] = item
                        want.put(item.key, JSONArray(missing.toList()))
                        total += missing.sumOf { item.files.getValue(it).size }
                    }
                    plan = planned
                    totalBytes = total
                    onProgress(Progress(0, total, 0, planned.size, null))
                    reply(out, 200, JSONObject().put("want", want).toString())
                }
                mode == "put" && method == "PUT" && path == "/file" -> {
                    val item = plan[q["key"]] ?: return reply(out, 404, "not asked for")
                    val kind = q["kind"]?.takeIf { it in item.files } ?: return reply(out, 404, "no such file")
                    if (length != item.files.getValue(kind).size || length > MOST_BYTES) return reply(out, 400, "wrong size")
                    onProgress(Progress(bytes, totalBytes, songs, plan.size, item.track.optString("title")))
                    store!!.write(item, kind, Limited(input, length), length)
                    bytes += length
                    reply(out, 200, "ok")
                }
                mode == "put" && method == "POST" && path == "/done" -> {
                    val item = plan[q["key"]] ?: return reply(out, 404, "not asked for")
                    store!!.done(item)
                    songs++
                    onProgress(Progress(bytes, totalBytes, songs, plan.size, null))
                    reply(out, 200, "ok")
                }
                else -> reply(out, 404, "no")
            }
        }

        private fun reply(out: OutputStream, code: Int, body: String) {
            val b = body.toByteArray()
            out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "No"}\r\nContent-Length: ${b.size}\r\nContent-Type: ${if (body.startsWith("{")) "application/json" else "text/plain"}; charset=utf-8\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(b)
            out.flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) throw SocketException("line too long")
            sb.append(c.toChar())
        }
    }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val b = ByteArray(n)
        var at = 0
        while (at < n) {
            val r = read(b, at, n - at)
            if (r < 0) break
            at += r
        }
        return b.copyOf(at)
    }

    /** Exactly [left] bytes of [input], then the end. */
    private class Limited(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else input.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()

    private fun url(base: String, path: String, key: String, extra: Map<String, String> = emptyMap()) =
        "$base$path?k=$key" + extra.entries.joinToString("") { "&${it.key}=${enc(it.value)}" }

    /** Can [base] be reached (a moment)? */
    fun reachable(base: String, key: String, mode: String): Boolean = runCatching {
        val req = if (mode == "get") Request.Builder().url(url(base, "/list", key)).build()
        else Request.Builder().url(url(base, "/plan", key)).post("{\"songs\":[]}".toRequestBody("application/json".toMediaType())).build()
        http.newBuilder().connectTimeout(2, TimeUnit.SECONDS).build().newCall(req).execute().use { it.code == 200 }
    }.onFailure { e -> runCatching { android.util.Log.w("radiance-share", "can't reach $base: $e") } }.getOrDefault(false) // (no Log in the computer's tests)

    /** Takes what's missing here of what [base] offers, into [store]. How many songs came. */
    fun pull(base: String, key: String, store: Store, pace: Pace = Pace { 0 }, cancelled: () -> Boolean = { false }, progress: (Progress) -> Unit = {}): Int {
        val list = http.newCall(Request.Builder().url(url(base, "/list", key)).build()).execute().use { r ->
            if (!r.isSuccessful) error("the other device said no (${r.code})")
            JSONObject(r.body.string()).getJSONArray("songs")
        }
        val items = (0 until list.length()).mapNotNull { Item.fromJson(list.getJSONObject(it)) }
        val todo = items.map { it to (it.files.keys - store.have(it)) }.filter { it.second.isNotEmpty() }
        val total = todo.sumOf { (i, kinds) -> kinds.sumOf { i.files.getValue(it).size } }
        var bytes = 0L
        var songs = 0
        progress(Progress(0, total, 0, todo.size, null))
        for ((item, kinds) in todo) {
            if (cancelled()) break
            val title = item.track.optString("title")
            for (kind in kinds) {
                if (cancelled()) break
                val size = item.files.getValue(kind).size
                http.newCall(Request.Builder().url(url(base, "/file", key, mapOf("key" to item.key, "kind" to kind))).build()).execute().use { r ->
                    if (!r.isSuccessful) error("“$title” didn't come (${r.code})")
                    val counted = Counting(r.body.byteStream()) { n -> bytes += n; progress(Progress(bytes, total, songs, todo.size, title)) }
                    val paced = Paced(counted, pace)
                    store.write(item, kind, paced, size)
                }
            }
            if (cancelled()) break
            store.done(item)
            songs++
            progress(Progress(bytes, total, songs, todo.size, null))
        }
        return songs
    }

    /** Sends [offer]'s songs that [base] hasn't got. How many went. */
    fun push(base: String, key: String, offer: Offer, pace: Pace = Pace { 0 }, cancelled: () -> Boolean = { false }, progress: (Progress) -> Unit = {}): Int {
        val items = offer.items()
        val body = JSONObject().put("songs", JSONArray(items.map { it.toJson() })).toString()
        val want = http.newCall(Request.Builder().url(url(base, "/plan", key)).post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) error("the other device said no (${r.code})")
            JSONObject(r.body.string()).getJSONObject("want")
        }
        val todo = items.filter { want.has(it.key) }.map { i -> i to (0 until want.getJSONArray(i.key).length()).map { want.getJSONArray(i.key).getString(it) }.filter { it in i.files } }
        val total = todo.sumOf { (i, kinds) -> kinds.sumOf { i.files.getValue(it).size } }
        var bytes = 0L
        var songs = 0
        progress(Progress(0, total, 0, todo.size, null))
        for ((item, kinds) in todo) {
            if (cancelled()) break
            val title = item.track.optString("title")
            for (kind in kinds) {
                if (cancelled()) break
                val size = item.files.getValue(kind).size
                val src = offer.open(item.key, kind) ?: error("“$title” isn't here any more")
                val req = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength() = size
                    override fun writeTo(sink: BufferedSink) {
                        src.use { copy(it, sink.outputStream(), size, pace) { n -> bytes += n; progress(Progress(bytes, total, songs, todo.size, title)) } }
                    }
                }
                http.newCall(Request.Builder().url(url(base, "/file", key, mapOf("key" to item.key, "kind" to kind))).put(req).build()).execute().use { r ->
                    if (!r.isSuccessful) error("“$title” wasn't taken (${r.code})")
                }
            }
            if (cancelled()) break
            http.newCall(Request.Builder().url(url(base, "/done", key, mapOf("key" to item.key))).post(ByteArray(0).toRequestBody()).build()).execute().use { }
            songs++
            progress(Progress(bytes, total, songs, todo.size, null))
        }
        return songs
    }

    private class Counting(private val input: InputStream, private val moved: (Long) -> Unit) : InputStream() {
        override fun read(): Int = input.read().also { if (it >= 0) moved(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len).also { if (it > 0) moved(it.toLong()) }
        override fun close() = input.close()
    }

    /** Reads no faster than [pace] allows. */
    private class Paced(private val input: InputStream, private val pace: Pace) : InputStream() {
        private var windowStart = System.nanoTime()
        private var windowBytes = 0L
        override fun read(): Int = input.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = input.read(b, off, len)
            val limit = pace.limit()
            if (n > 0 && limit > 0) {
                windowBytes += n
                val should = windowBytes * 1_000_000_000L / limit
                val took = System.nanoTime() - windowStart
                if (should > took) Thread.sleep((should - took) / 1_000_000)
                if (took > 1_000_000_000L) { windowStart = System.nanoTime(); windowBytes = 0 }
            }
            return n
        }
        override fun close() = input.close()
    }
}

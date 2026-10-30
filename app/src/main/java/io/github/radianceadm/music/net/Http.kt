package io.github.radianceadm.music.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** One HTTP client for everything: lookups, NewPipe, images, playback, saving. */
object Http {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun url(base: String, query: Map<String, String>) =
        base.toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    fun text(base: String, query: Map<String, String> = emptyMap()): String? = runCatching {
        val req = Request.Builder().url(url(base, query)).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body.string() else null }
    }.getOrNull()

    fun json(base: String, query: Map<String, String> = emptyMap()): JSONObject? =
        text(base, query)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun jsonArray(base: String, query: Map<String, String> = emptyMap()): JSONArray? =
        text(base, query)?.let { runCatching { JSONArray(it) }.getOrNull() }

    fun postJson(base: String, body: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject? = runCatching {
        val req = Request.Builder().url(base)
            .header("User-Agent", USER_AGENT)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body.string()) else null }
    }.getOrNull()

    /**
     * Downloads [url] into [out] in pieces (YouTube slows down whole-file
     * downloads, never ranged ones). [progress] gets the part done, 0-1.
     */
    fun download(url: String, out: OutputStream, size: Long?, progress: (Float) -> Unit = {}) {
        val piece = 4L * 1024 * 1024
        var at = 0L
        while (true) {
            val end = at + piece - 1
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Range", "bytes=$at-$end").build()
            val got = client.newCall(req).execute().use { r ->
                if (r.code == 416 && at > 0) return@use -1L // past the end: it's all here
                if (r.code != 206 && r.code != 200) error("the download stopped (HTTP ${r.code})")
                val bytes = r.body.byteStream().use { it.copyTo(out) }
                if (r.code == 200) return@use -1L // the server sent it all at once
                bytes
            }
            if (got < 0) break
            at += got
            size?.takeIf { it > 0 }?.let { progress((at.toFloat() / it).coerceIn(0f, 1f)) }
            if (got < piece || (size != null && size > 0 && at >= size)) break
        }
        progress(1f)
    }
}

/** NewPipe Extractor's way onto the network. */
class NewPipeDownloader : Downloader() {
    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = Request.Builder().url(request.url()).method(request.httpMethod(), body).header("User-Agent", Http.USER_AGENT)
        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        Http.client.newCall(builder.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("YouTube asks to confirm you're not a bot", request.url())
            val headers: Map<String, List<String>> = r.headers.toMultimap()
            return Response(r.code, r.message, headers, r.body.string(), r.request.url.toString())
        }
    }
}

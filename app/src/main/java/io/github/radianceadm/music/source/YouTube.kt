package io.github.radianceadm.music.source

import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.Http
import io.github.radianceadm.music.net.fail
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube Music, the fallback when the Internet Archive doesn't have a song:
 * searched and played through NewPipe Extractor (it keeps up with YouTube's
 * changes, as yt-dlp does on the desktop).
 */
object YouTube {
    private val yt get() = ServiceList.YouTube

    /** Songs for [query] (never music videos: phones have none, android#32). */
    fun search(query: String): List<Track> {
        val ex = yt.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        ex.fetchPage()
        return ex.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
            val id = runCatching { YoutubeStreamLinkHandlerFactory.getInstance().getId(item.url) }.getOrNull() ?: return@mapNotNull null
            Track(
                source = Source.YOUTUBE,
                id = id,
                title = item.name,
                artist = item.uploaderName.orEmpty().removeSuffix(" - Topic"),
                seconds = item.duration.toInt().coerceAtLeast(0),
                art = item.thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
                format = "Opus",
            )
        }
    }

    fun suggestions(query: String): List<String> = runCatching { yt.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())

    /** A stream to play or save: its address, type, file ending and size (if known). */
    data class Stream(val url: String, val mime: String, val ext: String, val size: Long?, val kbps: Int)

    private val info = ConcurrentHashMap<String, Pair<StreamInfo, Long>>()

    /** The video's stream info, kept 2 hours (YouTube's addresses last about 6). */
    private fun infoOf(id: String): StreamInfo {
        info[id]?.let { (i, at) -> if (System.currentTimeMillis() - at < 2 * 3600_000) return i }
        val i = StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$id")
        info[id] = i to System.currentTimeMillis()
        return i
    }

    private fun AudioStream.asStream(): Stream {
        val opus = format == MediaFormat.WEBMA_OPUS || format == MediaFormat.WEBMA
        return Stream(content, if (opus) "audio/webm" else "audio/mp4", if (opus) "webm" else "m4a", itagItem?.contentLength?.takeIf { it > 0 }, averageBitrate)
    }

    private fun AudioStream.plainHttp() = deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && isUrl

    /** The best sound: Opus first (the best YouTube has), else AAC. */
    fun audio(id: String): Stream {
        val streams = infoOf(id).audioStreams.filter { it.plainHttp() }
        val best = streams.filter { it.format == MediaFormat.WEBMA_OPUS }.maxByOrNull { it.averageBitrate }
            ?: streams.maxByOrNull { it.averageBitrate }
            ?: fail("YouTube didn't give this song's sound.")
        return best.asStream()
    }

    /** The AAC sound (for saving where Opus can't be moved into an MP4). */
    fun aac(id: String): Stream? = infoOf(id).audioStreams.filter { it.plainHttp() && it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }?.asStream()

    // ------------------------------------------------------------------ lyrics

    private const val CLIENT_VERSION = "1.20251020.01.00"

    /** The text YouTube Music shows in its Lyrics tab (from LyricFind and others). */
    fun lyrics(id: String): String? {
        val context = JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", CLIENT_VERSION).put("hl", "en"))
        val headers = mapOf("Origin" to "https://music.youtube.com", "Referer" to "https://music.youtube.com/")
        val next = Http.postJson("https://music.youtube.com/youtubei/v1/next?prettyPrint=false", JSONObject().put("context", context).put("videoId", id), headers) ?: return null
        val ends = mutableListOf<Any>()
        collect(next, "browseEndpoint", ends)
        val tab = ends.filterIsInstance<JSONObject>().map { it.optString("browseId") }.firstOrNull { it.startsWith("MPLYt") } ?: return null
        val page = Http.postJson("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", JSONObject().put("context", context).put("browseId", tab), headers) ?: return null
        val shelves = mutableListOf<Any>()
        collect(page, "musicDescriptionShelfRenderer", shelves)
        val runs = (shelves.firstOrNull() as? JSONObject)?.optJSONObject("description")?.optJSONArray("runs") ?: return null
        val text = (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
        return text.ifBlank { null }
    }

    // ------------------------------------------------------------------ up next

    /**
     * Songs like [id], as YouTube Music plays after it (its radio for the song: the
     * "Up next" list), the song itself left out. Owner, 2026-11-06: a song from search
     * played the other search results next ("bakit yung nag auto queue hindi related").
     */
    fun related(id: String): List<Track> {
        val context = JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", CLIENT_VERSION).put("hl", "en"))
        val headers = mapOf("Origin" to "https://music.youtube.com", "Referer" to "https://music.youtube.com/")
        val body = JSONObject().put("context", context).put("videoId", id).put("playlistId", "RDAMVM$id").put("isAudioOnly", true)
        val next = Http.postJson("https://music.youtube.com/youtubei/v1/next?prettyPrint=false", body, headers) ?: return emptyList()
        val found = mutableListOf<Any>()
        collect(next, "playlistPanelVideoRenderer", found)
        return found.filterIsInstance<JSONObject>().mapNotNull { upNext(it) }.filter { it.id != id }.distinctBy { it.id }
    }

    /** One song of an "Up next" list. */
    fun upNext(r: JSONObject): Track? {
        val vid = r.optString("videoId").ifEmpty { return null }
        fun runs(key: String) = r.optJSONObject(key)?.optJSONArray("runs")
        val title = runs("title")?.optJSONObject(0)?.optString("text").orEmpty().ifEmpty { return null }
        // "Artist • Album • 2019" (the artist's run first; more artists joined by " & " or ", ")
        val by = runs("longBylineText") ?: runs("shortBylineText")
        val artist = by?.let { b -> (0 until b.length()).map { b.optJSONObject(it)?.optString("text").orEmpty() }.joinToString("").split(" • ").firstOrNull() }.orEmpty()
        val length = runs("lengthText")?.optJSONObject(0)?.optString("text").orEmpty()
        val seconds = length.split(':').mapNotNull { it.trim().toIntOrNull() }.fold(0) { acc, n -> acc * 60 + n }
        val thumbs = r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val art = thumbs?.let { t -> (0 until t.length()).mapNotNull { t.optJSONObject(it) }.maxByOrNull { it.optInt("height") }?.optString("url") }.orEmpty()
            .replace(Regex("=w\\d+-h\\d+"), "=w544-h544") // a bigger cover than the list's thumbnail
        return Track(Source.YOUTUBE, vid, title, artist, seconds = seconds, art = art, format = "Opus")
    }

    private fun collect(v: Any?, key: String, out: MutableList<Any>) {
        when (v) {
            is JSONObject -> v.keys().forEach { k ->
                val x = v.opt(k)
                if (k == key && x != null) out += x
                collect(x, key, out)
            }
            is JSONArray -> for (i in 0 until v.length()) collect(v.opt(i), key, out)
        }
    }
}

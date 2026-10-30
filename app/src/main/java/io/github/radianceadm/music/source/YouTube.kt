package io.github.radianceadm.music.source

import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.model.plain
import io.github.radianceadm.music.model.splitCredit
import io.github.radianceadm.music.net.Http
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
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube Music, the fallback when the Internet Archive doesn't have a song:
 * searched and played through NewPipe Extractor (it keeps up with YouTube's
 * changes, as yt-dlp does on the desktop).
 */
object YouTube {
    private val yt get() = ServiceList.YouTube

    /** Songs (or music videos) for [query]. */
    fun search(query: String, videos: Boolean = false): List<Track> {
        val filter = if (videos) YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS else YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        val ex = yt.getSearchExtractor(query, listOf(filter), "")
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
            ?: error("YouTube didn't give this song's sound")
        return best.asStream()
    }

    /** The AAC sound (for saving where Opus can't be moved into an MP4). */
    fun aac(id: String): Stream? = infoOf(id).audioStreams.filter { it.plainHttp() && it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }?.asStream()

    /** For a music video saved as one file: its picture (H.264, up to 720p) and AAC sound. */
    fun videoParts(id: String): Pair<Stream, Stream>? {
        val i = infoOf(id)
        val picture = i.videoOnlyStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.format == MediaFormat.MPEG_4 && it.codec.orEmpty().startsWith("avc1") }
            .filter { height(it) in 1..720 }
            .maxByOrNull { height(it) } ?: return null
        val sound = i.audioStreams.filter { it.plainHttp() && it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate } ?: return null
        return Stream(picture.content, "video/mp4", "mp4", picture.itagItem?.contentLength?.takeIf { it > 0 }, height(picture)) to sound.asStream()
    }

    /** The best video with its sound in one stream (YouTube keeps these to 360p): the fallback. */
    fun videoWithSound(id: String): Stream? = infoOf(id).videoStreams
        .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.format == MediaFormat.MPEG_4 }
        .maxByOrNull { height(it) }
        ?.let { Stream(it.content, "video/mp4", "mp4", it.itagItem?.contentLength?.takeIf { n -> n > 0 }, height(it)) }

    private fun height(v: VideoStream): Int = v.resolution.orEmpty().takeWhile { it.isDigit() }.toIntOrNull() ?: v.height

    /** The song's official music video (or, for a video, its song): same title, same artist. */
    fun counterpart(t: Track): Track? {
        val artist = splitCredit(t.artist).firstOrNull().orEmpty()
        val (a, title) = plain(artist) to plain(t.title)
        if (a.isEmpty() || title.isEmpty()) return null
        val found = runCatching { search("$artist ${t.title}", videos = true) }.getOrDefault(emptyList())
        return found.firstOrNull { c ->
            val ct = plain(c.title)
            c.id != t.id && ct.contains(title) && plain("${c.artist} ${c.title}").contains(a)
        }
    }

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

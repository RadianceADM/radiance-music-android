package io.github.radianceadm.music.source

import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Internet Archive: free, lossless (FLAC) recordings, searched first. The search
 * finds items with FLAC files; each item's file list gives the songs, kept only
 * when every word searched for is in the song's title, artist or album.
 */
object Archive {
    private const val ITEMS = 8
    private const val MAX = 10
    private val pool = Executors.newFixedThreadPool(ITEMS)

    fun search(query: String): List<Track> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val escaped = query.filter { it.isLetterOrDigit() || it.isWhitespace() || it == '\'' }
        val q = "($escaped) AND mediatype:(audio) AND format:(\"Flac\" OR \"24bit Flac\")"
        val found = Http.json(
            "https://archive.org/advancedsearch.php",
            mapOf("q" to q, "fl[]" to "identifier", "rows" to "$ITEMS", "sort[]" to "downloads desc", "output" to "json"),
        ) ?: return emptyList()
        val docs = found.optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        val ids = (0 until docs.length()).mapNotNull { docs.optJSONObject(it)?.optString("identifier")?.ifEmpty { null } }
        // the items' file lists, all at once
        val lists = ids.map { id -> id to pool.submit<JSONObject?> { Http.json("https://archive.org/metadata/$id") } }
        val out = mutableListOf<Track>()
        for ((id, future) in lists) {
            val meta = runCatching { future.get(20, TimeUnit.SECONDS) }.getOrNull() ?: continue
            for (t in tracks(id, meta)) {
                val hay = "${t.title} ${t.artist} ${t.album}".lowercase()
                if (words.all { hay.contains(it) } && out.none { it.id == t.id }) out += t
            }
        }
        // the song's own title matching first, then hi-res
        val phrase = query.lowercase()
        return out.sortedWith(compareBy({ !it.title.lowercase().contains(phrase) }, { it.format != "24-bit FLAC" })).take(MAX)
    }

    private fun text(v: Any?): String = when (v) {
        is String -> v
        is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it) as? String }.joinToString(", ")
        else -> ""
    }

    private fun tracks(id: String, meta: JSONObject): List<Track> {
        val m = meta.optJSONObject("metadata") ?: JSONObject()
        val itemTitle = text(m.opt("title"))
        val itemArtist = text(m.opt("creator"))
        val files = meta.optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).mapNotNull { i ->
            val f = files.optJSONObject(i) ?: return@mapNotNull null
            val format = when (f.optString("format")) {
                "Flac" -> "FLAC"
                "24bit Flac" -> "24-bit FLAC"
                else -> return@mapNotNull null
            }
            val name = f.optString("name").ifEmpty { return@mapNotNull null }
            val title = text(f.opt("title")).ifEmpty { name.substringAfterLast('/').removeSuffix(".flac").replace('_', ' ') }
            val artist = listOf(text(f.opt("artist")), text(f.opt("creator")), itemArtist).firstOrNull { it.isNotEmpty() } ?: ""
            val album = text(f.opt("album")).ifEmpty { itemTitle }
            Track(
                source = Source.ARCHIVE,
                id = "https://archive.org/download/$id/${encodePath(name)}",
                title = title,
                artist = artist,
                album = album,
                seconds = parseClock(f.optString("length")),
                art = "https://archive.org/services/img/$id",
                format = format,
            )
        }
    }

    /** "4:05", "1:02:03" or "245.3" -> seconds. */
    private fun parseClock(text: String): Int {
        val t = text.trim()
        if (t.isEmpty()) return 0
        return if (':' in t) t.split(':').fold(0) { acc, part -> acc * 60 + (part.trim().toDoubleOrNull()?.toInt() ?: 0) } else t.toDoubleOrNull()?.toInt() ?: 0
    }

    private fun encodePath(path: String): String = buildString {
        for (b in path.toByteArray()) {
            val c = b.toInt() and 0xff
            if (c.toChar().let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-_.~/" }) append(c.toChar()) else append("%%%02X".format(c))
        }
    }
}

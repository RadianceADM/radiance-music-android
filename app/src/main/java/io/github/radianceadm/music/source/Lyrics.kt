package io.github.radianceadm.music.source

import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.model.plain
import io.github.radianceadm.music.model.splitCredit
import io.github.radianceadm.music.net.Http
import org.json.JSONObject

/** A line of lyrics and when it's sung (seconds). */
data class Line(val start: Double, val end: Double, val text: String)

sealed class Lyrics {
    data class Timed(val lines: List<Line>) : Lyrics()
    data class Plain(val text: String) : Lyrics()
    object None : Lyrics()

    /** As a file: LRC when timed, else the text. */
    fun toFile(): String? = when (this) {
        is Timed -> lines.joinToString("\n") { "[%02d:%05.2f]%s".format((it.start / 60).toInt(), it.start % 60, it.text) }
        is Plain -> text
        None -> null
    }
}

/**
 * Lyrics from LRCLIB (free, open, no account), else the text YouTube Music
 * shows. As the desktop app: an LRCLIB entry must be filed under the song's
 * artist, and lyrics whose own "[ar: …]" tag names another artist are turned
 * away (someone once filed Young Greatness's "Moolah" under SCOOP DOGG's).
 */
object LyricsFinder {
    fun find(t: Track): Lyrics {
        lrclib(t)?.let { return it }
        if (t.source == Source.YOUTUBE) YouTube.lyrics(t.id)?.let { return Lyrics.Plain(it) }
        return Lyrics.None
    }

    /** Lyrics kept as a file (LRC or text). */
    fun fromFile(text: String): Lyrics {
        val lines = parseLrc(text)
        return when {
            lines.isNotEmpty() -> Lyrics.Timed(lines)
            text.isNotBlank() -> Lyrics.Plain(text)
            else -> Lyrics.None
        }
    }

    private fun byThem(t: Track, v: JSONObject): Boolean {
        val names = splitCredit(t.artist).map { plain(it) }.filter { it.length >= 2 }
        fun ours(name: String): Boolean {
            val theirs = plain(name)
            return theirs.isNotEmpty() && names.any { theirs.contains(it) || it.contains(theirs) }
        }
        val tag = listOf("syncedLyrics", "plainLyrics").firstNotNullOfOrNull { k ->
            v.optString(k).takeIf { it.isNotEmpty() && !v.isNull(k) }?.let { Regex("\\[ar:\\s*([^\\]]+)]").find(it)?.groupValues?.get(1)?.trim() }
        }
        return ours(v.optString("artistName")) && (tag == null || ours(tag))
    }

    private fun lrclib(t: Track): Lyrics? {
        val query = buildMap {
            put("track_name", t.title)
            put("artist_name", t.artist)
            if (t.album.isNotEmpty()) put("album_name", t.album)
            if (t.seconds > 0) put("duration", t.seconds.toString())
        }
        Http.json("https://lrclib.net/api/get", query)?.takeIf { byThem(t, it) }?.let { from(it) }?.let { return it }
        // not that exact recording: the best match by title and artist
        val list = Http.jsonArray("https://lrclib.net/api/search", mapOf("track_name" to t.title, "artist_name" to t.artist)) ?: return null
        val all = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        val close = all.filter { byThem(t, it) && (t.seconds == 0 || kotlin.math.abs(it.optDouble("duration", -99.0) - t.seconds) < 8) }
        return (close.firstOrNull { !it.isNull("syncedLyrics") && it.optString("syncedLyrics").isNotBlank() } ?: close.firstOrNull())?.let { from(it) }
    }

    private fun from(v: JSONObject): Lyrics? {
        if (!v.isNull("syncedLyrics")) {
            val lines = parseLrc(v.optString("syncedLyrics"))
            if (lines.isNotEmpty()) return Lyrics.Timed(lines)
        }
        if (!v.isNull("plainLyrics")) v.optString("plainLyrics").takeIf { it.isNotBlank() }?.let { return Lyrics.Plain(it) }
        return null
    }

    /** "[01:02.50]words" lines; each ends where the next starts. Tags like "[ar: …]" are skipped. */
    fun parseLrc(text: String): List<Line> {
        val stamp = Regex("\\[(\\d+):(\\d+(?:[.:]\\d+)?)]")
        val found = mutableListOf<Pair<Double, String>>()
        for (raw in text.lines()) {
            val stamps = stamp.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val words = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val at = m.groupValues[1].toDouble() * 60 + m.groupValues[2].replace(':', '.').toDouble()
                found += at to words
            }
        }
        found.sortBy { it.first }
        return found.mapIndexed { i, (at, words) ->
            val end = found.getOrNull(i + 1)?.first ?: (at + 5)
            Line(at, maxOf(end, at + 0.3), words.ifEmpty { "♪" })
        }
    }
}

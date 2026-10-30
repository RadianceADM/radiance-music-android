package io.github.radianceadm.music.data

import android.content.Context
import android.os.Environment
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.source.Line
import io.github.radianceadm.music.split.Splitter
import io.github.radianceadm.music.source.Lyrics
import io.github.radianceadm.music.source.LyricsFinder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Your library from the computer (Radiance Music for Linux), copied onto the
 * phone: the songs in Music/Radiance Music, their videos in Movies/Radiance
 * Music, and in the app's own folder (Library/) a list of them (library.json)
 * with their parts (drums, bass, other, vocals), lyrics and covers. Read once:
 * each song joins Liked Songs, playing its own file, with its lyrics and cover.
 */
object Import {
    fun dir(context: Context) = File(context.getExternalFilesDir(null), "Library")
    private fun list(context: Context) = File(dir(context), "library.json")
    private fun done(context: Context) = File(context.filesDir, "imported")

    /** A library is waiting to be read (new, or copied again since). */
    fun waiting(context: Context): Boolean {
        val l = list(context)
        return l.exists() && done(context).let { !it.exists() || it.readText().trim() != l.lastModified().toString() }
    }

    /** The song's parts, split on this phone or copied: name ("drums", "bass", "other", "vocals", "music") to its file. */
    fun parts(context: Context, key: String): Map<String, File> {
        // split on this phone
        Splitter.parts(context, key).takeIf { it.size == Splitter.PARTS.size }?.let { return it }
        val songs = runCatching { JSONObject(list(context).readText()).getJSONArray("songs") }.getOrNull() ?: return emptyMap()
        for (i in 0 until songs.length()) {
            val e = songs.getJSONObject(i)
            if (e.optString("key") != key) continue
            val p = e.optJSONObject("parts") ?: return emptyMap()
            return p.keys().asSequence().associateWith { File(dir(context), p.getString(it)) }.filterValues { it.exists() }
        }
        return emptyMap()
    }

    /** Reads it into Liked Songs (worker thread). How many songs came in. */
    fun run(context: Context, library: Library): Int {
        val l = list(context)
        val songs = JSONObject(l.readText()).getJSONArray("songs")
        val music = File(Environment.getExternalStorageDirectory(), "Music/Radiance Music")
        val movies = File(Environment.getExternalStorageDirectory(), "Movies/Radiance Music")
        var n = 0
        for (i in 0 until songs.length()) {
            val e = songs.getJSONObject(i)
            val base = Track.fromJson(e.getJSONObject("track")) ?: continue
            val song = File(music, e.optString("song"))
            if (!song.exists()) continue
            val video = e.optString("video").takeIf { it.isNotEmpty() }?.let { File(movies, it) }?.takeIf { it.exists() }
            val t = base.copy(file = "file://${song.absolutePath}", video = video?.let { "file://${it.absolutePath}" })
            // its cover and its lyrics where the app keeps them
            e.optString("cover").takeIf { it.isNotEmpty() }?.let { File(dir(context), it) }?.takeIf { it.exists() }?.let { c ->
                Saver.coverFile(context, t).apply { parentFile?.mkdirs() }.let { c.copyTo(it, overwrite = true) }
            }
            val lyrics = e.optString("lyricsfile").takeIf { it.isNotEmpty() }?.let { File(dir(context), it) }?.takeIf { it.exists() }?.let { lyricsfile(it.readText()) }
                ?: e.optString("lrc").takeIf { it.isNotEmpty() }?.let { File(dir(context), it) }?.takeIf { it.exists() }?.let { LyricsFinder.fromFile(it.readText()) }
            lyrics?.toFile()?.let { Saver.lyricsFile(context, t).apply { parentFile?.mkdirs() }.writeText(it) }
            if (library.isLiked(t.key)) library.update(t) else library.like(t)
            n++
        }
        done(context).writeText(l.lastModified().toString())
        return n
    }

    /**
     * A Lyricsfile (the desktop app's YAML: lines with their text, start_ms and
     * end_ms, and maybe each word's) as timed lines.
     */
    fun lyricsfile(yaml: String): Lyrics? {
        val lines = mutableListOf<Line>()
        var text: String? = null
        var start = -1L
        var end = -1L
        fun flush() {
            val t = text ?: return
            if (start >= 0) lines += Line(start / 1000.0, (if (end > start) end else start + 3000) / 1000.0, t.ifBlank { "♪" })
            text = null; start = -1; end = -1
        }
        var inLines = false
        for (raw in yaml.lines()) {
            if (raw.startsWith("lines:")) { inLines = true; continue }
            if (!inLines) continue
            if (raw.isNotEmpty() && !raw.startsWith(" ")) break // the next top-level key
            when {
                raw.startsWith("  - text: ") -> { flush(); text = unquote(raw.removePrefix("  - text: ")) }
                raw.startsWith("    start_ms: ") -> start = raw.substringAfter(": ").trim().toLongOrNull() ?: -1
                raw.startsWith("    end_ms: ") -> end = raw.substringAfter(": ").trim().toLongOrNull() ?: -1
            }
        }
        flush()
        return if (lines.isEmpty()) null else Lyrics.Timed(lines.sortedBy { it.start })
    }

    /** "…" (JSON-style) or '…' (YAML) or bare. */
    private fun unquote(v: String): String {
        val s = v.trim()
        return when {
            s.startsWith("\"") -> runCatching { JSONArray("[$s]").getString(0) }.getOrDefault(s.trim('"'))
            s.startsWith("'") && s.endsWith("'") -> s.substring(1, s.length - 1).replace("''", "'")
            else -> s
        }
    }
}

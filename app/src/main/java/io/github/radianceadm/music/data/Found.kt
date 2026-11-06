package io.github.radianceadm.music.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.model.fold
import io.github.radianceadm.music.source.YouTube
import kotlin.math.abs

/**
 * Songs already saved on this phone (Music/Radiance Music, their videos in
 * Movies/Radiance Music) back into Liked Songs: the app was uninstalled and
 * its list went with it, but the songs stayed (owner, 2026-11-06: "bakit
 * nawala yung mga liked music ko? inuninstall ko"). Each file is matched to
 * its song on YouTube Music by its name ("Artist - Title", as Saver names it)
 * and its length, so it gets its cover and lyrics again; it keeps playing its
 * own file. A file with no match waits for the next time (offline, say).
 */
object Found {
    /** A saved song file: its address, its name without the extension, the extension, its length (0: unknown). */
    data class File(val uri: String, val name: String, val ext: String, val seconds: Int)

    /** The songs saved here that aren't in Liked Songs, matched and liked. [say]: how many done of how many. How many came back of how many there were. */
    fun run(context: Context, library: Library, say: (Int, Int) -> Unit): Pair<Int, Int> {
        val songs = files(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Music/Radiance Music")
        val videos = files(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/Radiance Music").associateBy { it.name }
        val mine = library.liked.value.mapNotNull { it.file }.toSet()
        val missing = songs.filter { it.uri !in mine }
        var back = 0
        missing.forEachIndexed { i, f ->
            say(i, missing.size)
            val (artist, title) = split(f.name)
            val found = runCatching { YouTube.search(listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")) }
                .onFailure { Log.w(TAG, "couldn't look up “${f.name}”", it) }
                .getOrNull()?.let { pick(f, it) }
            if (found == null) {
                Log.i(TAG, "no match for “${f.name}”")
                return@forEachIndexed
            }
            val t = found.copy(
                file = f.uri,
                video = videos[f.name]?.uri,
                format = if (f.ext.equals("flac", true)) "FLAC" else found.format,
            )
            if (library.isLiked(t.key)) library.update(t) else library.like(t)
            runCatching { Saver.cover(context, t) }
            runCatching { Saver.lyrics(context, t) }
            back++
        }
        say(missing.size, missing.size)
        Log.i(TAG, "brought back $back of ${missing.size} saved songs")
        return back to missing.size
    }

    /** The files saved in [folder] (a MediaStore relative path). */
    private fun files(context: Context, collection: android.net.Uri, folder: String): List<File> {
        val out = mutableListOf<File>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$folder%"), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val display = c.getString(1) ?: continue
                val dot = display.lastIndexOf('.')
                out += File(
                    uri = ContentUris.withAppendedId(collection, c.getLong(0)).toString(),
                    name = if (dot > 0) display.substring(0, dot) else display,
                    ext = if (dot > 0) display.substring(dot + 1) else "",
                    seconds = (c.getLong(2) / 1000).toInt(),
                )
            }
        }
        return out
    }

    /** "Artist - Title" (as Saver names a song) as its artist and title; no " - ": the title alone. */
    fun split(name: String): Pair<String, String> {
        val at = name.indexOf(" - ")
        return if (at > 0) name.substring(0, at).trim() to name.substring(at + 3).trim() else "" to name.trim()
    }

    /**
     * The search result that is [f]'s song: its name like the file's and its
     * length within a few seconds; else one of the same length whose name
     * shares a word; else none (a wrong cover and lyrics are worse than none).
     */
    fun pick(f: File, results: List<Track>): Track? {
        val want = words(f.name)
        fun sameLength(t: Track, within: Int) = f.seconds <= 0 || t.seconds <= 0 || abs(t.seconds - f.seconds) <= within
        fun named(t: Track): Boolean {
            val (artist, title) = split(f.name)
            val a = norm(artist)
            val ti = norm(title)
            val rt = norm(t.title)
            return ti.isNotEmpty() && (rt == ti || rt.contains(ti) || ti.contains(rt)) && (a.isEmpty() || norm(t.artist).let { it.contains(a) || a.contains(it) })
        }
        return results.firstOrNull { named(it) && sameLength(it, 5) }
            ?: results.firstOrNull { sameLength(it, 3) && f.seconds > 0 && it.seconds > 0 && words("${it.artist} ${it.title}").any { w -> w in want } }
    }

    private fun norm(s: String) = fold(s).lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun words(s: String) = norm(s).split(' ').filter { it.length > 2 }.toSet()

    private const val TAG = "radiance-found"
}

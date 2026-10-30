package io.github.radianceadm.music.data

import android.content.Context
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.model.fold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What's yours on this phone: Liked Songs (always A to Z), the songs played
 * (newest first) and where you left off. Kept as small JSON files in the
 * app's own folder; the songs themselves are in Music/Radiance Music.
 */
class Library(context: Context) {
    private val dir = context.filesDir

    private val _liked = MutableStateFlow(load("liked.json").sortedWith(byTitle))
    val liked: StateFlow<List<Track>> = _liked

    private val _history = MutableStateFlow(load("history.json"))
    val history: StateFlow<List<Track>> = _history

    fun isLiked(key: String) = _liked.value.any { it.key == key }

    fun like(t: Track) {
        if (isLiked(t.key)) return
        _liked.value = (_liked.value + t).sortedWith(byTitle)
        save("liked.json", _liked.value)
    }

    fun unlike(t: Track) {
        _liked.value = _liked.value.filterNot { it.key == t.key }
        save("liked.json", _liked.value)
    }

    /** A liked song again, now with its saved copy (or video). */
    fun update(t: Track) {
        if (!isLiked(t.key)) return
        _liked.value = _liked.value.map { if (it.key == t.key) t else it }
        save("liked.json", _liked.value)
    }

    /** The liked copy of a song when there is one (it has the saved file). */
    fun best(t: Track): Track = _liked.value.firstOrNull { it.key == t.key } ?: t

    fun played(t: Track) {
        val before = _history.value
        if (before.firstOrNull()?.key == t.key) return
        _history.value = (listOf(t) + before).take(300)
        save("history.json", _history.value)
    }

    /** The queue, the song playing and where in it: kept when the app closes. */
    fun saveSession(queue: List<Track>, index: Int, positionMs: Long) {
        val o = JSONObject().put("queue", JSONArray(queue.map { it.toJson() })).put("index", index).put("position", positionMs)
        runCatching { File(dir, "session.json").writeText(o.toString()) }
    }

    fun session(): Triple<List<Track>, Int, Long>? = runCatching {
        val o = JSONObject(File(dir, "session.json").readText())
        val q = o.getJSONArray("queue")
        val tracks = (0 until q.length()).mapNotNull { Track.fromJson(q.getJSONObject(it)) }.map { best(it) }
        Triple(tracks, o.optInt("index"), o.optLong("position"))
    }.getOrNull()?.takeIf { it.first.isNotEmpty() }

    private fun load(name: String): List<Track> = runCatching {
        val a = JSONArray(File(dir, name).readText())
        (0 until a.length()).mapNotNull { Track.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(name: String, tracks: List<Track>) {
        runCatching {
            val f = File(dir, "$name.new")
            f.writeText(JSONArray(tracks.map { it.toJson() }).toString())
            f.renameTo(File(dir, name))
        }
    }

    companion object {
        /** A to Z as filed: accents, case and leading quotes or symbols aside; letters first. */
        val byTitle: Comparator<Track> = compareBy({ fileKey(it.title).first }, { fileKey(it.title).second })

        fun fileKey(title: String): Pair<Int, String> {
            val key = fold(title).lowercase().trimStart { !it.isLetterOrDigit() }
            return (if (key.firstOrNull()?.let { it in 'a'..'z' } == true) 0 else 1) to key
        }
    }
}

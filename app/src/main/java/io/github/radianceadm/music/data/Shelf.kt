package io.github.radianceadm.music.data

import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.model.fold
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.TreeMap

/** Library's shelves beside Liked Songs and History (as the computer app's): the liked songs by album, artist or genre. */
enum class ShelfKind(val title: String) { Albums("Albums"), Artists("Artists"), Genres("Genres") }

/** One album, artist or genre, and its liked songs in Liked Songs' order. */
data class Group(val name: String, val songs: List<Track>)

/**
 * The liked songs grouped (android#38; owner, 2026-11-08: "asan na yung genres / artists / songs like this"), as the
 * computer app groups them (core/src/library.rs): every credited artist, a song with no album under Singles, a song
 * whose genres Deezer doesn't know under Other, one not looked up yet under Finding the genre…; A to Z.
 */
object Shelf {
    const val SINGLES = "Singles"
    const val OTHER = "Other"
    const val FINDING = "Finding the genre…"

    fun groups(liked: List<Track>, kind: ShelfKind): List<Group> {
        val map = TreeMap<String, Pair<String, MutableList<Track>>>()
        fun put(name: String, t: Track) {
            val g = map.getOrPut(fold(name).lowercase()) { name to mutableListOf() }
            if (g.second.none { it.key == t.key }) g.second += t
        }
        for (t in liked) when (kind) {
            ShelfKind.Albums -> put(t.album.ifBlank { SINGLES }, t)
            ShelfKind.Artists -> t.artists().forEach { put(it, t) }
            ShelfKind.Genres -> when {
                t.genres == null -> put(FINDING, t)
                t.genres.isEmpty() -> put(OTHER, t)
                else -> t.genres.forEach { put(it, t) }
            }
        }
        return map.values.map { (name, songs) -> Group(name, songs) }
    }

    /** A card on Home asks for its album, artist or genre: Library opens it. */
    val open = MutableStateFlow<Pair<ShelfKind, String>?>(null)
}

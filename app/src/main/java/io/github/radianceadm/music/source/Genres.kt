package io.github.radianceadm.music.source

import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.model.plain
import io.github.radianceadm.music.net.Http
import org.json.JSONObject

/**
 * A song's genres, from Deezer's free public catalogue (no account), as the computer app finds them
 * (engine/src/genre.rs): the genres of the album the song is on, e.g. ["Rap/Hip Hop", "Pop"]. The same answer
 * names the album, for a song that came without one (YouTube Music's search gives none): Albums groups by it.
 */
object Genres {
    /** What Deezer said: the genres (empty: it doesn't know the song) and the album's title when it's surely this song's. */
    data class Found(val genres: List<String>, val album: String?)

    /** Looks [t] up (in the background). Null: Deezer couldn't be asked (offline): ask again later. */
    fun lookup(t: Track): Found? {
        val artist = t.artists().firstOrNull().orEmpty()
        val search = Http.json("https://api.deezer.com/search", mapOf("q" to "$artist ${t.title}", "limit" to "5")) ?: return null
        val (albumId, title) = pick(search, artist) ?: return Found(emptyList(), null)
        val album = Http.json("https://api.deezer.com/album/$albumId") ?: return null
        return Found(genresOf(album), title)
    }

    /**
     * The album of the hit by the same artist, with its title; else the first hit's album with no title (its
     * genres are likely right, its album not surely this song's). Null: no hits.
     */
    internal fun pick(search: JSONObject, artist: String): Pair<Long, String?>? {
        val hits = search.optJSONArray("data") ?: return null
        val all = (0 until hits.length()).mapNotNull { hits.optJSONObject(it) }
        val same = all.firstOrNull { plain(it.optJSONObject("artist")?.optString("name").orEmpty()) == plain(artist) }
        val hit = same ?: all.firstOrNull() ?: return null
        val album = hit.optJSONObject("album") ?: return null
        val id = album.optLong("id").takeIf { it > 0 } ?: return null
        return id to album.optString("title").takeIf { same != null && it.isNotBlank() }
    }

    internal fun genresOf(album: JSONObject): List<String> {
        val data = album.optJSONObject("genres")?.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
    }
}

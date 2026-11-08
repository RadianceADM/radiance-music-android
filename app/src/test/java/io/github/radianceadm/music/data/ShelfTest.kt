package io.github.radianceadm.music.data

import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Albums, Artists and Genres (android#38), grouped as the computer app groups them (core/src/library.rs). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShelfTest {
    private fun song(artist: String, title: String, album: String = "", genres: List<String>? = null) =
        Track(Source.YOUTUBE, "$artist-$title", title, artist, album = album, genres = genres)

    // the computer app's own cases (core/src/track.rs featured_artists_count)
    @Test fun featuredArtistsCount() {
        assertEquals(listOf("Uncle Dags", "KKRAM"), song("Uncle Dags", "Tamaraw (feat. KKRAM)").artists())
        assertEquals(listOf("Gat Putch", "Icetizy", "Uncle Dags", "Smugglaz"), song("Gat Putch, Icetizy & Uncle Dags", "MATIKAS (feat. Smugglaz)").artists())
        assertEquals(5, song("SUPAFLY", "PUFF ME UP (feat. Gat Putch, Gaspari, OG CRY & Ghoti Scale)").artists().size)
        assertEquals(listOf("Hev Abi"), song("Hev Abi", "Para Sa Streets").artists())
        assertEquals(listOf("Al James", "Muric"), song("Al James & Muric", "Mood (feat. Muric)").artists()) // named twice: once
    }

    @Test fun everyCreditedArtistHasTheSong() {
        val a = song("Lil Tjay", "Headshot (feat. Polo G & Fivio Foreign)")
        val b = song("Lil Tjay", "Calling My Phone (feat. 6LACK)")
        val g = Shelf.groups(listOf(a, b), ShelfKind.Artists)
        assertEquals(listOf("6LACK", "Fivio Foreign", "Lil Tjay", "Polo G"), g.map { it.name })
        assertEquals(listOf(a, b), g.first { it.name == "Lil Tjay" }.songs)
    }

    @Test fun albumsWithSinglesForNone() {
        val g = Shelf.groups(listOf(song("Lil Tjay", "Headshot", "Destined 2 Win"), song("Lil Tjay", "Oh Well", "Destined 2 Win"), song("Maki", "Dilaw")), ShelfKind.Albums)
        assertEquals(listOf("Destined 2 Win" to 2, Shelf.SINGLES to 1), g.map { it.name to it.songs.size })
    }

    @Test fun genresOtherAndStillFinding() {
        val g = Shelf.groups(
            listOf(song("Lil Tjay", "Headshot", genres = listOf("Rap/Hip Hop", "Pop")), song("Maki", "Dilaw", genres = emptyList()), song("Ben&Ben", "Pasilyo")),
            ShelfKind.Genres,
        )
        assertEquals(listOf(Shelf.FINDING, Shelf.OTHER, "Pop", "Rap/Hip Hop"), g.map { it.name })
    }

    @Test fun sameNameAnyCaseIsOneGroup() {
        val g = Shelf.groups(listOf(song("SB19", "Gento", "Pagsibol"), song("SB19", "What?", "PAGSIBOL")), ShelfKind.Albums)
        assertEquals(1, g.size)
        assertEquals(2, g[0].songs.size)
    }

    @Test fun genresKeptWithTheSong() {
        val t = song("Lil Tjay", "Headshot", genres = listOf("Rap/Hip Hop"))
        assertEquals(listOf("Rap/Hip Hop"), Track.fromJson(t.toJson())?.genres)
        assertNull(Track.fromJson(song("Maki", "Dilaw").toJson())?.genres) // not looked up yet: stays so
        assertEquals(emptyList<String>(), Track.fromJson(song("Maki", "Dilaw", genres = emptyList()).toJson())?.genres)
        // a library from before genres: none yet
        assertNull(Track.fromJson(JSONObject("""{"source":"youtube","id":"x","title":"T","artist":"A"}"""))?.genres)
    }
}

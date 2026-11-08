package io.github.radianceadm.music.source

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deezer's answers, as they came on 2026-11-08 (android#38). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GenresTest {
    private val search = JSONObject(
        """{"data":[
            {"title":"Headshots","artist":{"name":"Lilwaddy"},"album":{"id":469914725,"title":"Who Is He"}},
            {"title":"Headshot","artist":{"name":"Lil Tjay"},"album":{"id":218804652,"title":"Destined 2 Win"}}
        ]}""",
    )

    @Test fun theSameArtistsAlbumWithItsTitle() = assertEquals(218804652L to "Destined 2 Win", Genres.pick(search, "Lil Tjay"))
    // no hit by the artist: the first hit's genres are likely right, its album not surely this song's
    @Test fun elseTheFirstHitWithoutAnAlbum() = assertEquals(469914725L to null, Genres.pick(search, "Someone Else"))
    @Test fun noHits() = assertNull(Genres.pick(JSONObject("""{"data":[]}"""), "Lil Tjay"))

    @Test fun theAlbumsGenres() {
        val album = JSONObject("""{"title":"Destined 2 Win","genres":{"data":[{"id":116,"name":"Rap/Hip Hop"},{"id":132,"name":"Pop"}]}}""")
        assertEquals(listOf("Rap/Hip Hop", "Pop"), Genres.genresOf(album))
        assertEquals(emptyList<String>(), Genres.genresOf(JSONObject("""{"title":"X"}""")))
    }
}

package io.github.radianceadm.music.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Covers at the size they're shown (android#36; owner, 2026-11-08: "ang low quality ng album art"). */
class ArtTest {
    private val search = "https://yt3.googleusercontent.com/i1qCCS4BbP6z11E08FkQg6fN-83Uj4fQg4bmBsD2E6SvGQ3RW7nXxpQ3hmcSlI5Ipek10H7R4BjV5mAY=w120-h120-l90-rj"
    private val base = search.substringBefore('=')

    @Test fun bigForNowPlayingAndStories() = assertEquals("$base=w1200-h1200-l90-rj", Art.sized(search, Art.BIG))
    @Test fun roundedUpToASize() {
        assertEquals("$base=w120-h120-l90-rj", Art.sized(search, 100))
        assertEquals("$base=w226-h226-l90-rj", Art.sized(search, 132)) // a row's 44 dp on a phone
        assertEquals("$base=w544-h544-l90-rj", Art.sized(search, 420)) // a Home card
        assertEquals("$base=w1200-h1200-l90-rj", Art.sized(search, 3000)) // never past the big one
    }
    @Test fun otherSizeForms() {
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", Art.sized("https://lh3.googleusercontent.com/abc=s120-c-k-c0x00ffffff-no-rj", 500))
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", Art.sized("https://lh3.googleusercontent.com/abc", 500))
    }
    @Test fun otherAddressesAsTheyAre() {
        for (url in listOf("https://archive.org/services/img/some-album", "https://i.ytimg.com/vi/abc/hqdefault.jpg", "https://bassi.example/files/song.jpg", "")) {
            assertEquals(url, Art.sized(url, Art.BIG))
            assertFalse(Art.grows(url))
        }
    }
}

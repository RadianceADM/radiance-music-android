package io.github.radianceadm.music.source

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpNextTest {
    // one item of YouTube Music's "Up next" (a playlistPanelVideoRenderer), as /next sends it
    private val item = JSONObject(
        """
        {"videoId":"abc123XYZ_-",
         "title":{"runs":[{"text":"Come Inside Of My Heart"}]},
         "longBylineText":{"runs":[{"text":"IV OF SPADES"},{"text":" • "},{"text":"CLAPCLAPCLAP!"},{"text":" • "},{"text":"2019"}]},
         "lengthText":{"runs":[{"text":"4:21"}]},
         "thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/x=w60-h60-l90-rj","width":60,"height":60},
                                    {"url":"https://lh3.googleusercontent.com/x=w120-h120-l90-rj","width":120,"height":120}]}}
        """,
    )

    @Test fun anUpNextItemIsASong() {
        val t = YouTube.upNext(item)!!
        assertEquals("abc123XYZ_-", t.id)
        assertEquals("Come Inside Of My Heart", t.title)
        assertEquals("IV OF SPADES", t.artist) // the artist, not the album or year
        assertEquals(261, t.seconds)
        assertEquals("https://lh3.googleusercontent.com/x=w544-h544-l90-rj", t.art) // the biggest, bigger still
        assertEquals("yt:abc123XYZ_-", t.key)
    }

    @Test fun anItemWithoutAVideoIsLeftOut() {
        assertNull(YouTube.upNext(JSONObject("""{"title":{"runs":[{"text":"x"}]}}""")))
        assertNull(YouTube.upNext(JSONObject("""{"videoId":"v"}""")))
    }
}

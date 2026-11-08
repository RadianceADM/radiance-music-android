package io.github.radianceadm.music

import io.github.radianceadm.music.update.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the phone looks for the newest version (android#39): Radiance's copy on downloads.radianceph.com first,
 * GitHub when it can't be had (GitHub answered the owner's S24 403: too many asks from one ISP address).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UpdatesSourceTest {
    private fun release(tag: String, at: String) = """{"tag_name":"$tag","name":"Radiance Music ${tag.removePrefix("v")} for Android","body":"- What's new",
        "html_url":"https://github.com/RadianceADM/radiance-music-android/releases/tag/$tag",
        "assets":[{"name":"RadianceMusic.apk","size":43829946,"digest":"sha256:59517c932a8d87202ad8e75f4610580a16742053eca891b4f37292af980d1270",
        "browser_download_url":"$at/$tag/RadianceMusic.apk"}]}"""

    private val mirror = "https://downloads.radianceph.com/radiance-music-android"
    private val github = "https://github.com/RadianceADM/radiance-music-android/releases/download"

    @Test fun theMirrorFirstAndItsFileFromThere() {
        val asked = mutableListOf<String>()
        val r = Updates.latest { url -> asked += url; release("v1.2.1", mirror) }
        assertEquals("1.2.1", r.version)
        assertEquals("$mirror/v1.2.1/RadianceMusic.apk", r.apk)
        assertEquals("59517c932a8d87202ad8e75f4610580a16742053eca891b4f37292af980d1270", r.sha256)
        assertEquals(listOf("$mirror/latest.json"), asked) // GitHub isn't asked
    }

    @Test fun gitHubWhenTheMirrorCantBeHad() {
        val r = Updates.latest { url -> if (url.contains("radianceph")) error("downloads.radianceph.com answered 503") else release("v1.2.1", github) }
        assertEquals("1.2.1", r.version)
        assertEquals("$github/v1.2.1/RadianceMusic.apk", r.apk)
    }

    @Test fun neitherAnswersSaysWhy() {
        val e = runCatching { Updates.latest { url -> error("${if (url.contains("radianceph")) "downloads.radianceph.com" else "api.github.com"} answered 403") } }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("api.github.com answered 403"))
    }

    @Test fun aBrokenCopyIsPassedOver() {
        val r = Updates.latest { url -> if (url.contains("radianceph")) "<html>502 Bad Gateway</html>" else release("v1.2.1", github) }
        assertEquals("1.2.1", r.version)
    }
}

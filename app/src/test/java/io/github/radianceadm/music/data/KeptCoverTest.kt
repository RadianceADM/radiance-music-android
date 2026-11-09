package io.github.radianceadm.music.data

import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers that went missing on the owner's S24 (android#41; "nawala yung ibang album art"): songs imported from the
 * computer came without a cover's address, blanking the one the phone had; and the lists showed only addresses,
 * never the cover kept on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class KeptCoverTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val song = Track(Source.YOUTUBE, "kept1", "Bad Type", "Shanti Dope", art = "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj")

    @Test fun aCopyWithoutAnAddressKeepsTheOneItHad() {
        val lib = Library(context)
        lib.like(song)
        lib.update(song.copy(art = "", file = "file:///sdcard/Music/Radiance%20Music/Shanti%20Dope%20-%20Bad%20Type.opus")) // as the import did
        val now = lib.liked.value.first { it.key == song.key }
        assertEquals(song.art, now.art)
        assertEquals("file:///sdcard/Music/Radiance%20Music/Shanti%20Dope%20-%20Bad%20Type.opus", now.file)
        lib.update(song.copy(art = "https://lh3.googleusercontent.com/new")) // a real new address still goes in
        assertEquals("https://lh3.googleusercontent.com/new", lib.liked.value.first { it.key == song.key }.art)
    }

    @Test fun theKeptCoverFirst() {
        val t = song.copy(id = "kept2")
        assertNull(Saver.keptCover(context, t)) // none kept: the address is shown
        Saver.coverFile(context, t).apply { parentFile?.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(Saver.keptCover(context, t)) // asked once: not looked at again until told
        Saver.coverKept(t.key)
        assertEquals(Saver.coverFile(context, t).path, Saver.keptCover(context, t))
    }
}

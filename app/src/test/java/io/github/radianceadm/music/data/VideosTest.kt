package io.github.radianceadm.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideosTest {
    private val dir = File("/data/user/0/io.github.radianceadm.music/no_backup/videos")

    @Test fun aSongsVideoIsNamedByItsKey() {
        assertEquals(File(dir, "yt_dQw4w9WgXcQ.mp4"), Videos.fileIn(dir, "yt:dQw4w9WgXcQ"))
        // one sent from the computer keeps its own kind
        assertEquals(File(dir, "yt_abc.mkv"), Videos.fileIn(dir, "yt:abc", "mkv"))
        assertEquals(File(dir, "yt_abc.mp4"), Videos.fileIn(dir, "yt:abc", ""))
    }

    @Test fun aKeptVideoIsInTheAppsFolder() {
        val f = Videos.fileIn(dir, "yt:abc")
        assertTrue(Videos.inApp(dir, Videos.address(f)))
    }

    @Test fun videosFromBeforeAreNotInTheApp() {
        // saved in Movies/Radiance Music before 1.0.2: Gallery shows those
        assertFalse(Videos.inApp(dir, "content://media/external/video/media/1234"))
        assertFalse(Videos.inApp(dir, "file:///storage/emulated/0/Movies/Radiance Music/Maki - Dilaw.mp4"))
        // a folder whose name only starts the same isn't it
        assertFalse(Videos.inApp(dir, "file:///data/user/0/io.github.radianceadm.music/no_backup/videos-old/yt_abc.mp4"))
    }
}

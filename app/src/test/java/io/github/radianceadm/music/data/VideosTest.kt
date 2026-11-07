package io.github.radianceadm.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** No music videos on the phone (owner, 2026-11-08, android#32): the ones it kept go by themselves. */
class VideosTest {
    private val root = Files.createTempDirectory("videos-test").toFile()

    @Test fun keptVideosAreDeletedByThemselves() {
        val videos = root.resolve("no_backup/videos").apply { mkdirs() }
        videos.resolve("yt_abc.mp4").writeBytes(ByteArray(1000))
        videos.resolve("yt_def.mkv.part").writeBytes(ByteArray(200)) // half-written when the app closed
        val cache = root.resolve("cache").apply { mkdirs() }
        cache.resolve("video-yt_abc").apply { mkdirs() }.resolve("picture.mp4").writeBytes(ByteArray(300))
        // a story video (the share clip) and a song being saved aren't music videos: they stay
        cache.resolve("share/video").apply { mkdirs() }.resolve("story.mp4").writeBytes(ByteArray(50))
        cache.resolve("song-yt_abc").apply { mkdirs() }.resolve("song.opus").writeBytes(ByteArray(40))

        assertEquals(1500L, Videos.forget(videos, cache))

        assertFalse(videos.exists())
        assertFalse(cache.resolve("video-yt_abc").exists())
        assertTrue(cache.resolve("share/video/story.mp4").exists())
        assertTrue(cache.resolve("song-yt_abc/song.opus").exists())
        root.deleteRecursively()
    }

    @Test fun nothingKeptIsNothingToDo() {
        assertEquals(0L, Videos.forget(root.resolve("no_backup/videos"), root.resolve("cache")))
        root.deleteRecursively()
    }
}

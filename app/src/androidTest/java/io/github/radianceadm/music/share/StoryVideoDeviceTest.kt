package io.github.radianceadm.music.share

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A story video made on a real Android (the emulator on the build server): the
 * card's frames with a song's sound, from the two kinds of saved songs (Opus in
 * MP4 from YouTube, FLAC). Comes out an MP4 with H.264 at 1080×1920 and AAC, as
 * long as the clip. The videos are left in the app's files, to look at.
 */
@RunWith(AndroidJUnit4::class)
class StoryVideoDeviceTest {
    @Test fun makesAStoryWithItsSound() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val keep = context.getExternalFilesDir(null)!!
        for (name in listOf("tone.m4a", "tone.flac")) {
            val song = File(context.cacheDir, name).also { f -> inst.context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } } }
            val t = Track(Source.YOUTUBE, "test", "Golden Hour", "JVKE", seconds = 20)
            val lines = listOf("It's just a test", "Of the story video", "With its sound")
            val clip = StoryVideo.clip(listOf(2.0, 4.5, 7.0), 9.5, 0, 20_000)
            val frames = StoryVideo.frames(clip) { lit -> StoryCard.draw(context, t, null, lines, null, lit) }
            assertEquals(clip.lengthMs, frames.sumOf { it.ms })
            var last = 0f
            val out = StoryVideo.make(context, "test-$name", Uri.fromFile(song), clip, frames) { last = it }
            assertTrue(out.length() > 10_000)
            assertEquals(1f, last)
            val ex = MediaExtractor().apply { setDataSource(out.path) }
            val formats = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
            val video = formats.first { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC }
            // upright as it's stored (where the phone's encoder can), or turned upright by its mark
            val turned = if (video.containsKey(MediaFormat.KEY_ROTATION)) video.getInteger(MediaFormat.KEY_ROTATION) % 180 != 0 else false
            val (w, h) = video.getInteger(MediaFormat.KEY_WIDTH) to video.getInteger(MediaFormat.KEY_HEIGHT)
            assertEquals("$name: ${w}×$h, turned $turned", 1080 to 1920, if (turned) h to w else w to h)
            assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC })
            ex.release()
            val ms = MediaMetadataRetriever().run { setDataSource(out.path); val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong(); release(); d }
            assertTrue("$name: $ms ms for a ${clip.lengthMs} ms clip", kotlin.math.abs(ms - clip.lengthMs) < 400)
            out.copyTo(File(keep, "story-$name.mp4"), overwrite = true)
        }
    }

    @Test fun aClipWithoutTimedLinesIsFifteenSecondsFromHere() {
        val c = StoryVideo.clip(null, null, fromMs = 30_000, songMs = 200_000)
        assertEquals(30_000, c.startMs)
        assertEquals(StoryVideo.PLAIN_MS, c.lengthMs)
        // near the end: it starts earlier, to stay 15 s long
        val end = StoryVideo.clip(null, null, fromMs = 195_000, songMs = 200_000)
        assertEquals(185_000, end.startMs)
    }
}

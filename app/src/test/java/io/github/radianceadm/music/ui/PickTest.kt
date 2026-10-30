package io.github.radianceadm.music.ui

import io.github.radianceadm.music.share.StoryCard
import io.github.radianceadm.music.share.StoryVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Picking lyric lines as Spotify does: lines next to each other, 150 characters at most. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PickTest {
    private val lengths = listOf(40, 40, 40, 40, 200, 10)

    @Test fun linesNextToEachOtherJoin() {
        val p = mutableListOf<Int>()
        assertNull(pick(p, 1, lengths))
        assertNull(pick(p, 2, lengths))
        assertNull(pick(p, 0, lengths))
        assertEquals(listOf(0, 1, 2), p.sorted())
    }

    @Test fun moreThan150CharactersDoesNot() {
        val p = mutableListOf(0, 1, 2) // 120
        assertNotNull(pick(p, 3, lengths)) // 160: refused
        assertEquals(listOf(0, 1, 2), p.sorted())
        assertEquals(150, StoryCard.MOST_CHARS)
    }

    @Test fun anEndComesOffAndAFarLineStartsAgain() {
        val p = mutableListOf(0, 1, 2)
        assertNull(pick(p, 2, lengths)) // an end: off
        assertEquals(listOf(0, 1), p.sorted())
        assertNull(pick(p, 5, lengths)) // far away: starts again
        assertEquals(listOf(5), p)
        assertNotNull(pick(mutableListOf(), 4, lengths)) // one line too long on its own
    }

    @Test fun framesNeverOutlastTheClip() {
        // two lines picked far apart: the clip stops at 30 s, the frames too
        val clip = StoryVideo.clip(listOf(10.0, 80.0), null, 0, 200_000)
        val frames = StoryVideo.frames(clip) { android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888) }
        assertEquals(clip.lengthMs, frames.sumOf { it.ms })
    }
}

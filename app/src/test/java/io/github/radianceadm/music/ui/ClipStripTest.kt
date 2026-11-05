package io.github.radianceadm.music.ui

import io.github.radianceadm.music.share.StoryVideo.Window
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a finger lands on the strip under the story (#19): an end of the bright part, its middle, or elsewhere. */
class ClipStripTest {
    // a 200 s song on a 1000 px strip: 5 px a second; the part is 40-60 s = 200-300 px
    private val w = Window(40_000, 20_000)
    private val song = 200_000L

    @Test fun theEndsAreHandles() {
        assertEquals(StripTouch.Grab.START, StripTouch.grab(195f, 1000f, w, song, edgePx = 24f))
        assertEquals(StripTouch.Grab.START, StripTouch.grab(212f, 1000f, w, song, edgePx = 24f))
        assertEquals(StripTouch.Grab.END, StripTouch.grab(305f, 1000f, w, song, edgePx = 24f))
    }

    @Test fun theMiddleMovesIt() {
        assertEquals(StripTouch.Grab.MIDDLE, StripTouch.grab(250f, 1000f, w, song, edgePx = 24f))
    }

    @Test fun elsewhereMovesItThere() {
        assertEquals(StripTouch.Grab.OUTSIDE, StripTouch.grab(700f, 1000f, w, song, edgePx = 24f))
    }

    @Test fun aNarrowPartStillHasAMiddle() {
        // 6 s = 30 px, narrower than two handles: its ends are only its outer quarters
        val narrow = Window(40_000, 6_000)
        assertEquals(StripTouch.Grab.MIDDLE, StripTouch.grab(215f, 1000f, narrow, song, edgePx = 24f))
        assertEquals(StripTouch.Grab.START, StripTouch.grab(198f, 1000f, narrow, song, edgePx = 24f))
        assertEquals(StripTouch.Grab.END, StripTouch.grab(232f, 1000f, narrow, song, edgePx = 24f))
    }

    @Test fun pixelsAndTimesMatch() {
        assertEquals(50_000L, StripTouch.msAt(250f, 1000f, song))
        assertEquals(0L, StripTouch.msAt(-20f, 1000f, song))
        assertEquals(song, StripTouch.msAt(1200f, 1000f, song))
    }
}

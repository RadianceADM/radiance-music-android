package io.github.radianceadm.music.share

import io.github.radianceadm.music.share.StoryVideo.Window
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The part of the song picked on the strip under the story (#19): moved, and made shorter or longer (6 to 30 s). */
class ClipWindowTest {
    private val song = 221_000L

    @Test fun startsWhereTheSongIsFor15Seconds() {
        assertEquals(Window(42_000, 15_000), Window.around(42_000, song))
        assertEquals(Window(206_000, 15_000), Window.around(215_000, song)) // never past the end
        assertEquals(Window(0, 4_000), Window.around(1_000, 4_000)) // a song shorter than the clip: all of it
    }

    @Test fun movingKeepsItsLengthInsideTheSong() {
        val w = Window(60_000, 20_000)
        assertEquals(Window(90_000, 20_000), w.moved(90_000, song))
        assertEquals(Window(0, 20_000), w.moved(-5_000, song))
        assertEquals(Window(201_000, 20_000), w.moved(220_000, song))
    }

    @Test fun theStartHandleKeepsTheEnd() {
        val w = Window(60_000, 15_000) // ends at 75 s
        assertEquals(Window(50_000, 25_000), w.startAt(50_000, song))
        assertEquals(Window(45_000, 30_000), w.startAt(40_000, song)) // 30 s at most
        assertEquals(Window(69_000, 6_000), w.startAt(72_000, song)) // 6 s at least
        assertEquals(Window(45_000, 30_000), w.startAt(-1_000, song))
    }

    @Test fun theEndHandleKeepsTheStart() {
        val w = Window(60_000, 15_000)
        assertEquals(Window(60_000, 20_000), w.endAt(80_000, song))
        assertEquals(Window(60_000, 30_000), w.endAt(100_000, song))
        assertEquals(Window(60_000, 6_000), w.endAt(62_000, song))
        assertEquals(Window(200_000, 21_000), Window(200_000, 15_000).endAt(300_000, song)) // the song's end
    }

    @Test fun itIsTheVideosClip() {
        assertEquals(StoryVideo.Clip(42_000, 15_000, emptyList()), Window(42_000, 15_000).clip())
    }

    @Test fun loudnessBarsAreScaledToTheLoudest() {
        assertArrayEquals(floatArrayOf(0.12f, 0.56f, 1f), Loudness.bars(floatArrayOf(0f, 0.5f, 1f)), 0.001f)
        assertArrayEquals(floatArrayOf(0.12f, 0.12f), Loudness.bars(floatArrayOf(0f, 0f)), 0.001f) // silence: still seen
        assertArrayEquals(floatArrayOf(), Loudness.bars(floatArrayOf()), 0f)
    }

    @Test fun loudnessIsCollectedByTime() {
        val b = Loudness.Buckets(durationUs = 4_000_000, count = 4)
        b.add(timeUs = 500_000, sumSquares = 4f, samples = 4) // 1 s bucket 0: rms 1
        b.add(timeUs = 2_500_000, sumSquares = 1f, samples = 4) // bucket 2: rms 0.5
        b.add(timeUs = 9_000_000, sumSquares = 4f, samples = 1) // past the end: the last bucket
        assertArrayEquals(floatArrayOf(1f, 0f, 0.5f, 2f), b.rms(), 0.001f)
    }
}

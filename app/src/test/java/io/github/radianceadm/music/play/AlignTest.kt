package io.github.radianceadm.music.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts found as far off the song as they are (a decoder's delay), either way. */
class AlignTest {
    private fun noise(n: Int, seed0: Long): FloatArray {
        var seed = seed0
        return FloatArray(n) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            ((seed ushr 40).toInt() / 16777216f - 0.5f) * 0.4f
        }
    }

    /** The song, and its two halves [late] frames later, added up. */
    private fun split(late: Int): Pair<FloatArray, FloatArray> {
        val n = 16384
        val voice = noise(n + 6000, 7)
        val music = noise(n + 6000, 11)
        val song = FloatArray(n) { voice[it + 3000] + music[it + 3000] }
        val parts = FloatArray(n) { voice[it + 3000 - late] * 1f + music[it + 3000 - late] }
        return song to parts
    }

    @Test fun lateParts() {
        val (s, p) = split(37)
        val (lag, score) = Align.lag(s, p, 2400)!!
        assertEquals(37, lag)
        assertTrue("$score", score > 0.99)
    }

    @Test fun earlyParts() {
        val (s, p) = split(-2112) // an AAC encoder's delay, the other way
        assertEquals(-2112, Align.lag(s, p, 2400)!!.first)
    }

    @Test fun inStep() {
        val (s, p) = split(0)
        assertEquals(0, Align.lag(s, p, 2400)!!.first)
    }

    /** A long stretch, the parts as far off as after an Opus seek (80 ms at 48 kHz) and more. */
    private fun wide(late: Int): Pair<FloatArray, FloatArray> {
        val n = 65536
        val voice = noise(n + 30000, 13)
        val music = noise(n + 30000, 17)
        val song = FloatArray(n) { voice[it + 15000] * 0.6f + music[it + 15000] }
        val parts = FloatArray(n) { voice[it + 15000 - late] * 0.6f + music[it + 15000 - late] }
        return song to parts
    }

    @Test fun findsAnOpusSeeksDrop() {
        for (late in intArrayOf(3840, -3840, 37, -11999, 0)) {
            val (s, p) = wide(late)
            val (lag, score) = Align.find(s, p, 12000)!!
            assertEquals("late $late", late, lag)
            assertTrue("late $late: $score", score > 0.99)
        }
    }

    @Test fun silenceCantTell() {
        assertNull(Align.lag(FloatArray(16384), FloatArray(16384), 2400))
    }

    @Test fun unlikeSoundsScoreLow() {
        val (_, score) = Align.lag(noise(16384, 3), noise(16384, 5), 2400)!!
        assertTrue("$score", score < 0.2)
    }
}

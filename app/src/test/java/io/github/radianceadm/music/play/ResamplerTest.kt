package io.github.radianceadm.music.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The parts at the song's rate, as they play: in step to the sample, nothing lost. */
class ResamplerTest {
    private fun tone(rate: Int, frames: Int, hz: Double) = FloatArray(2 * frames) { i ->
        val t = (i / 2).toDouble() / rate
        (0.5 * sin(2 * PI * hz * t) * (if (i % 2 == 0) 1.0 else 0.7)).toFloat()
    }

    private fun run(from: Int, to: Int, input: FloatArray, blocks: IntArray): FloatArray {
        val r = Resampler(from, to)
        val out = FloatArray((input.size.toLong() * to / from).toInt() + 4096)
        var made = 0
        var at = 0
        var b = 0
        while (at < input.size / 2) {
            val n = minOf(blocks[b++ % blocks.size], input.size / 2 - at)
            r.push(input.copyOfRange(2 * at, 2 * (at + n)), 0, n)
            at += n
            made += r.pull(out, made, out.size / 2 - made, false)
        }
        made += r.pull(out, made, out.size / 2 - made, true)
        return out.copyOf(2 * made)
    }

    @Test fun fortyFourOneToFortyEightInStep() {
        val frames = 44100
        val out = run(44100, 48000, tone(44100, frames, 1000.0), intArrayOf(1024, 37, 4096, 333))
        val want = tone(48000, 48000, 1000.0)
        assertEquals(48000, out.size / 2)
        var worst = 0.0
        for (i in 2 * 200 until 2 * 47800) worst = maxOf(worst, abs((out[i] - want[i]).toDouble()))
        assertTrue("off by $worst", worst < 1e-3)
    }

    @Test fun fortyEightToFortyFourOneInStep() {
        val out = run(48000, 44100, tone(48000, 48000, 440.0), intArrayOf(2048, 5, 777))
        val want = tone(44100, 44100, 440.0)
        assertEquals(44100, out.size / 2)
        var worst = 0.0
        for (i in 2 * 200 until 2 * 43900) worst = maxOf(worst, abs((out[i] - want[i]).toDouble()))
        assertTrue("off by $worst", worst < 1e-3)
    }

    @Test fun resetStartsOver() {
        val r = Resampler(44100, 48000)
        val x = tone(44100, 4410, 500.0)
        val a = FloatArray(2 * 6000)
        r.push(x, 0, 4410)
        val first = r.pull(a, 0, 6000, true)
        r.reset()
        val b = FloatArray(2 * 6000)
        r.push(x, 0, 4410)
        assertEquals(first, r.pull(b, 0, 6000, true))
        for (i in 0 until 2 * first) assertEquals(a[i], b[i], 0f)
    }
}

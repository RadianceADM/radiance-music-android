package io.github.radianceadm.music.play

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stereo sound from one rate to another as it plays (windowed sinc, Kaiser,
 * as Pcm.resample, fewer taps): a song's parts at the song's own rate, so the
 * Mixer can blend the song and its parts sample for sample. Output frame n is
 * the input at n * from / to exactly (no delay), from the last [reset].
 */
class Resampler(from: Int, to: Int, taps: Int = 32) {
    private val ratio = from.toDouble() / to
    private val cutoff = minOf(1.0, to.toDouble() / from) * 0.96
    private val half = ceil(taps / cutoff).toInt()
    private val table = Array(PHASES) { p ->
        val frac = p.toDouble() / PHASES
        FloatArray(2 * half) { j ->
            val d = (j - half + 1) - frac
            (cutoff * sinc(cutoff * d) * kaiser(d / half)).toFloat()
        }
    }
    /** Input kept (interleaved stereo); its first frame is input frame [base]. */
    private var buf = FloatArray(1 shl 15)
    private var frames = 0
    private var base = 0L
    private var n = 0L

    fun reset() {
        frames = 0
        base = 0
        n = 0
    }

    /** [count] input frames (interleaved stereo) from [src] at [from]. */
    fun push(src: FloatArray, from: Int, count: Int) {
        // what no output needs any more goes
        val keep = floor(n * ratio).toLong() - half - base
        if (keep > 0) {
            val drop = minOf(keep, frames.toLong()).toInt()
            System.arraycopy(buf, 2 * drop, buf, 0, 2 * (frames - drop))
            frames -= drop
            base += drop
        }
        if (2 * (frames + count) > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, 2 * (frames + count)))
        System.arraycopy(src, from, buf, 2 * frames, 2 * count)
        frames += count
    }

    /** Up to [max] output frames into [dst] from frame [at] (interleaved stereo); how many. [end]: no more input will come. */
    fun pull(dst: FloatArray, at: Int, max: Int, end: Boolean): Int {
        var made = 0
        val last = base + frames // first input frame not here
        while (made < max) {
            val t = n * ratio
            val i0 = floor(t).toLong()
            if (end) { if (i0 >= last) break } else if (i0 + half >= last) break
            val h = table[((t - i0) * PHASES).toInt().coerceIn(0, PHASES - 1)]
            var l = 0f
            var r = 0f
            val first = i0 - half + 1
            for (j in 0 until 2 * half) {
                val k = first + j - base
                if (k < 0 || k >= frames) continue
                val w = h[j]
                l += buf[(2 * k).toInt()] * w
                r += buf[(2 * k + 1).toInt()] * w
            }
            dst[2 * (at + made)] = l
            dst[2 * (at + made) + 1] = r
            made++
            n++
        }
        return made
    }

    private fun sinc(x: Double) = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)

    private fun kaiser(x: Double): Double {
        if (abs(x) > 1) return 0.0
        return bessel0(BETA * sqrt(1 - x * x)) / bessel0(BETA)
    }

    private fun bessel0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        var k = 1
        while (term > 1e-12 * sum) {
            term *= (x / (2 * k)) * (x / (2 * k))
            sum += term
            k++
        }
        return sum
    }

    private companion object {
        const val PHASES = 512
        const val BETA = 9.0
    }
}

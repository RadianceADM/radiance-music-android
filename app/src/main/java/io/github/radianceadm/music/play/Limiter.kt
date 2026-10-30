package io.github.radianceadm.music.play

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The Mixer keeps a song as loud as it was whole (as the desktop's limiter.rs):
 * turning parts down raises what's left (never louder than the whole song, at
 * most MOST), and a look-ahead limiter keeps every peak under full scale.
 */
class Limiter(private val channels: Int, rate: Int) {
    private val len = maxOf((LOOKAHEAD_S * rate).toInt(), 1)
    private val delay = FloatArray(len * channels)
    private var at = 0
    private val held = ArrayDeque<Pair<Long, Float>>()
    private val smooth = FloatArray(len) { 1f }
    private var sum = len.toDouble()
    private var n = 0L
    private var released = 1f
    private var makeup = 1f
    private val kRelease = 1f - exp(-1.0 / (RELEASE_S * rate)).toFloat()
    private val kGlide = 1f - exp(-1.0 / (GLIDE_S * rate)).toFloat()

    /** [samples] (interleaved, [frames] of them) raised by [target] (gliding there) and limited, in place. */
    fun process(samples: FloatArray, frames: Int, target: Float) {
        val goal = target.coerceIn(1f, MOST)
        for (f in 0 until frames) {
            makeup += (goal - makeup) * kGlide
            var peak = 0f
            for (c in 0 until channels) peak = maxOf(peak, abs(samples[f * channels + c] * makeup))
            val wanted = if (peak > CEILING) CEILING / peak else 1f
            released = minOf(wanted, released + (1f - released) * kRelease)
            while (held.isNotEmpty() && held.last().second >= released) held.removeLast()
            held.addLast(n to released)
            while (held.isNotEmpty() && held.first().first + len < n) held.removeFirst()
            val low = held.firstOrNull()?.second ?: 1f
            val slot = (n % len).toInt()
            sum += (low - smooth[slot]).toDouble()
            smooth[slot] = low
            val gain = (sum / len).toFloat()
            val base = at * channels
            for (c in 0 until channels) {
                val i = f * channels + c
                val out = delay[base + c]
                delay[base + c] = samples[i] * makeup
                samples[i] = (out * gain).coerceIn(-1f, 1f)
            }
            at = (at + 1) % len
            n++
        }
    }

    companion object {
        const val MOST = 2f // +6 dB
        private const val CEILING = 0.97f
        private const val LOOKAHEAD_S = 0.008
        private const val RELEASE_S = 0.25
        private const val GLIDE_S = 0.25

        /** The whole song's loudness over what's left of it: (each part's power, its volume). */
        fun makeup(parts: List<Pair<Double, Float>>): Float {
            val whole = parts.sumOf { it.first }
            val left = parts.sumOf { it.first * it.second * it.second }
            if (whole < 1e-9) return 1f
            return sqrt(whole / maxOf(left, 1e-12)).toFloat().coerceIn(1f, MOST)
        }
    }
}

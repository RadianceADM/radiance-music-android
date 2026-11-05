package io.github.radianceadm.music.play

import kotlin.math.sqrt

/**
 * How far a song's parts are from the song itself (desktop memory: "16 samples
 * off ruined my fits"): the parts added up are the song, so the lag where the
 * two match best is the offset. Parts split here are in step by construction
 * (Pcm takes the encoder's delay off); parts from the computer or a server can
 * carry a decoder's delay of their own. Checked on the sound as it plays.
 */
object Align {
    /** Lag (frames) and how well they match there (0..1); positive: the parts come [lag] frames late. Null: too quiet to tell. */
    fun lag(song: FloatArray, parts: FloatArray, most: Int): Pair<Int, Double>? {
        val n = minOf(song.size, parts.size)
        if (n <= 2 * most) return null
        var es = 0.0
        var ep = 0.0
        for (i in most until n - most) {
            es += song[i] * song[i]
            ep += parts[i] * parts[i]
        }
        if (es / (n - 2 * most) < QUIET || ep / (n - 2 * most) < QUIET) return null
        var best = 0
        var bestC = -1.0
        for (l in -most..most) {
            var c = 0.0
            for (i in most until n - most) c += song[i] * parts[i + l]
            if (c > bestC) { bestC = c; best = l }
        }
        // the parts' energy at the best lag, for a fair 0..1 score
        var el = 0.0
        for (i in most until n - most) el += parts[i + best] * parts[i + best]
        return best to bestC / sqrt(es * el)
    }

    /**
     * As [lag], up to [most] frames either way (a quarter second and more) at a quarter of the
     * cost and then some: found on the sound summed in fours, then to the frame around that.
     * (Android's Opus decoder drops 80 ms after every seek and keeps its timestamps: the song
     * comes that much early, more than [lag]'s ±50 ms could see.)
     */
    fun find(song: FloatArray, parts: FloatArray, most: Int): Pair<Int, Double>? {
        val n = minOf(song.size, parts.size)
        val d = 4
        val m = n / d
        val s4 = FloatArray(m) { i -> song[d * i] + song[d * i + 1] + song[d * i + 2] + song[d * i + 3] }
        val p4 = FloatArray(m) { i -> parts[d * i] + parts[d * i + 1] + parts[d * i + 2] + parts[d * i + 3] }
        val coarse = lag(s4, p4, most / d) ?: return null
        val margin = most + 2 * d
        if (n <= 2 * margin) return null
        val around = coarse.first * d
        var best = around
        var bestC = Double.NEGATIVE_INFINITY
        for (l in maxOf(-most, around - 2 * d)..minOf(most, around + 2 * d)) {
            var c = 0.0
            for (i in margin until n - margin) c += song[i] * parts[i + l]
            if (c > bestC) { bestC = c; best = l }
        }
        var es = 0.0
        var el = 0.0
        for (i in margin until n - margin) {
            es += song[i] * song[i]
            el += parts[i + best] * parts[i + best]
        }
        if (es < 1e-12 || el < 1e-12) return null
        return best to bestC / kotlin.math.sqrt(es * el)
    }

    /** Below this mean power (about -50 dBFS) there's too little to line up. */
    private const val QUIET = 1e-5
}

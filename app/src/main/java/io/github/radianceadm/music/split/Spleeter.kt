package io.github.radianceadm.music.split

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.github.radianceadm.music.play.Fft
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * A song in two: its vocals and its music (everything else), with Deezer's
 * Spleeter (2 stems, MIT), the way Spleeter itself does it. The song's
 * spectrum (4096-sample frames, a frame every 1024 samples, Hann window) goes
 * to the two models (vocals, accompaniment) 512 frames at a time; each model
 * says how loud its part is in each of the lower 1024 bins (up to 11 kHz);
 * each part keeps its share of the song's own sound (ratio masks, squared),
 * the bins above get each frame's average share, and the parts are turned
 * back into sound. The two parts add up to the song.
 *
 * Owner, 2026-11-05 (#43): the phone splits in two only ("karaoke mode lang or
 * vocals only"), picked Spleeter after listening: ~30x faster than Demucs.
 * Plain Java underneath (no Android): tested against the bench's Python
 * version (spleeter_ort.py on the build server).
 */
object Spleeter {
    /** The parts, in the order they're handed on. Named as the computer names a song's halves. */
    val PARTS = listOf("vocals", "music")
    const val N = 4096 // frame
    const val H = 1024 // hop
    const val F = 1024 // the bins the models see
    const val T = 512 // frames per model run
    private const val BINS = N / 2 + 1
    private const val EPS = 1e-10f

    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() } // periodic Hann
    /** The overlap-add's window sum (four frames over every sample). */
    private val wsum = FloatArray(H) { j -> (0 until N / H).sumOf { k -> (win[j + k * H] * win[j + k * H]).toDouble() }.toFloat() }

    /**
     * [x]: [total] stereo frames (interleaved) at 44.1 kHz. [part] gets each
     * part's next [frames] frames (interleaved, in its buffer from 0), in
     * order, part 0 then part 1; [block] how many blocks are done of how many,
     * and the seconds left at this pace.
     */
    fun run(
        env: OrtEnvironment,
        vocals: OrtSession,
        music: OrtSession,
        x: FloatBuffer,
        total: Int,
        part: (index: Int, buf: FloatArray, frames: Int) -> Unit,
        block: (done: Int, of: Int, secondsLeft: Int) -> Unit = { _, _, _ -> },
        cancelled: () -> Boolean = { false },
        beforeBlock: () -> Unit = {},
    ) {
        // the song padded with N samples before and N + H after (as the bench does), in frames of N every H
        val padded = total + 2 * N + H
        val frames = 1 + (padded - N) / H
        val blocks = (frames + T - 1) / T
        fun sample(s: Int, ch: Int): Float { val i = s - N; return if (i in 0 until total) x.get(2 * i + ch) else 0f }

        val fft = Fft(N)
        val re = FloatArray(N)
        val im = FloatArray(N)
        // this block's spectrum, both channels: [frame][bin]
        val sRe = Array(2) { Array(T) { FloatArray(BINS) } }
        val sIm = Array(2) { Array(T) { FloatArray(BINS) } }
        val input = ByteBuffer.allocateDirect(2 * T * F * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val shape = longArrayOf(2, 1, T.toLong(), F.toLong())
        val yv = FloatArray(2 * T * F)
        val ya = FloatArray(2 * T * F)
        val maskL = Array(2) { FloatArray(BINS) } // [part][bin], left channel
        val maskR = Array(2) { FloatArray(BINS) }
        // each part's sound being added up: a block's worth plus one frame's overlap, interleaved
        val span = T * H + N
        val acc = Array(2) { FloatArray(2 * span) }
        val out = FloatArray(2 * T * H)
        var emitted = N // the next padded sample to hand on
        var spent = 0.0

        for (b in 0 until blocks) {
            beforeBlock() // waits while the phone needs a rest (see Guard)
            if (cancelled()) return
            val t0 = b * T
            val count = minOf(T, frames - t0)
            // the spectrum: both channels in one FFT (left real, right imaginary), then pulled apart
            input.clear()
            for (ch in 0 until 2) for (j in count * F until T * F) input.put(ch * T * F + j, 0f) // past the song's end
            for (tl in 0 until count) {
                val at = (t0 + tl) * H
                for (i in 0 until N) { re[i] = sample(at + i, 0) * win[i]; im[i] = sample(at + i, 1) * win[i] }
                fft.run(re, im, false)
                val lr = sRe[0][tl]; val li = sIm[0][tl]; val rr = sRe[1][tl]; val ri = sIm[1][tl]
                for (k in 0 until BINS) {
                    val m = (N - k) % N
                    lr[k] = 0.5f * (re[k] + re[m]); li[k] = 0.5f * (im[k] - im[m])
                    rr[k] = 0.5f * (im[k] + im[m]); ri[k] = -0.5f * (re[k] - re[m])
                }
                for (ch in 0 until 2) {
                    val sr = sRe[ch][tl]; val si = sIm[ch][tl]
                    val base = (ch * T + tl) * F
                    for (k in 0 until F) input.put(base + k, sqrt(sr[k] * sr[k] + si[k] * si[k]))
                }
            }
            input.rewind()
            val began = System.nanoTime()
            OnnxTensor.createTensor(env, input, shape).use { mag ->
                vocals.run(mapOf("x" to mag)).use { r -> (r[0] as OnnxTensor).floatBuffer.get(yv) }
                music.run(mapOf("x" to mag)).use { r -> (r[0] as OnnxTensor).floatBuffer.get(ya) }
            }
            spent += (System.nanoTime() - began) / 1e9

            // each part: its share of each bin, back to sound, added up in place
            for (tl in 0 until count) {
                for (ch in 0 until 2) {
                    val base = (ch * T + tl) * F
                    val mv = (if (ch == 0) maskL else maskR)[0]
                    val ma = (if (ch == 0) maskL else maskR)[1]
                    var sumV = 0f
                    var sumA = 0f
                    for (k in 0 until F) {
                        val v = yv[base + k]; val a = ya[base + k]
                        val den = v * v + a * a + EPS
                        mv[k] = (v * v + EPS / 2) / den
                        ma[k] = (a * a + EPS / 2) / den
                        sumV += mv[k]; sumA += ma[k]
                    }
                    // above the models' bins: each frame's average share
                    val avgV = sumV / F
                    val avgA = sumA / F
                    for (k in F until BINS) { mv[k] = avgV; ma[k] = avgA }
                }
                for (p in 0 until 2) {
                    // both channels in one inverse FFT: left + i·right, both real
                    val ml = maskL[p]; val mr = maskR[p]
                    val lr = sRe[0][tl]; val li = sIm[0][tl]; val rr = sRe[1][tl]; val ri = sIm[1][tl]
                    for (k in 0 until BINS) {
                        val ar = lr[k] * ml[k]; val ai = li[k] * ml[k] // left's bin
                        val br = rr[k] * mr[k]; val bi = ri[k] * mr[k] // right's bin
                        // Z[k] = A + iB; Z[N-k] = conj(A) + i·conj(B)
                        re[k] = ar - bi; im[k] = ai + br
                        if (k in 1 until N / 2) { re[N - k] = ar + bi; im[N - k] = -ai + br }
                    }
                    // as numpy's irfft: the DC and middle bins' imaginary parts don't count
                    re[0] = lr[0] * ml[0]; im[0] = rr[0] * mr[0]
                    re[N / 2] = lr[N / 2] * ml[N / 2]; im[N / 2] = rr[N / 2] * mr[N / 2]
                    fft.run(re, im, true)
                    val a = acc[p]
                    val off = tl * H
                    for (i in 0 until N) {
                        a[2 * (off + i)] += re[i] * win[i]
                        a[2 * (off + i) + 1] += im[i] * win[i]
                    }
                }
            }

            // done: everything before where the next block's first frame begins (all of it, after the last)
            val last = b == blocks - 1
            val doneUpTo = if (last) N + total else minOf(N + total, (t0 + T) * H)
            val from = b * T * H // acc[0] is this padded sample
            for (p in 0 until 2) {
                val a = acc[p]
                var n = 0
                for (s in emitted until doneUpTo) {
                    val k = s - from
                    val w = wsum[s % H]
                    out[2 * n] = a[2 * k] / w
                    out[2 * n + 1] = a[2 * k + 1] / w
                    n++
                }
                if (n > 0) part(p, out, n)
                // the overlap moves to the front for the next block
                System.arraycopy(a, 2 * T * H, a, 0, 2 * (span - T * H))
                java.util.Arrays.fill(a, 2 * (span - T * H), 2 * span, 0f)
            }
            emitted = maxOf(emitted, doneUpTo)
            block(b + 1, blocks, (spent / (b + 1) * (blocks - b - 1)).toInt())
        }
    }
}

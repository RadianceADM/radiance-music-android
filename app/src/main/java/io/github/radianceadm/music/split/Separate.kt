package io.github.radianceadm.music.split

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Demucs over a whole song, piece by piece: 7.8 s at a time (the model's
 * fixed piece), each overlapping the last by a quarter, faded across and
 * blended (as Demucs and demucs-onnx do). Each part's sound is handed on as
 * soon as no later piece can change it, so the song never sits in memory.
 * Plain Java underneath (no Android): tested against demucs-onnx itself.
 */
object Separate {
    val PARTS = listOf("drums", "bass", "other", "vocals") // the model's order
    const val N = 343_980 // 7.8 s at 44.1 kHz, fixed when the model was exported
    const val OVERLAP = N / 4
    const val STRIDE = N - OVERLAP

    /** The fade between pieces: in over the overlap, out over the overlap. */
    private val window = FloatArray(N) { i ->
        when {
            i < OVERLAP -> i.toFloat() / (OVERLAP - 1)
            i >= N - OVERLAP -> (N - 1 - i).toFloat() / (OVERLAP - 1)
            else -> 1f
        }
    }

    /**
     * [x]: [total] stereo frames (interleaved) at 44.1 kHz. [part] gets each
     * part's next [frames] frames (interleaved, in its buffer from 0), in
     * order; [piece] how many pieces are done of how many, and the seconds
     * left at this pace.
     */
    fun run(
        env: OrtEnvironment,
        session: OrtSession,
        x: FloatBuffer,
        total: Int,
        part: (index: Int, buf: FloatArray, frames: Int) -> Unit,
        piece: (done: Int, of: Int, secondsLeft: Int) -> Unit = { _, _, _ -> },
        cancelled: () -> Boolean = { false },
        beforePiece: () -> Unit = {},
    ) {
        val chunks = maxOf(1, (total + STRIDE - 1) / STRIDE)
        val carry = Array(PARTS.size) { FloatArray(2 * OVERLAP) } // the overlap with the next piece, faded
        val carryW = FloatArray(OVERLAP)
        val out = FloatArray(2 * N)
        val input = ByteBuffer.allocateDirect(2 * N * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val shape = longArrayOf(1, 2, N.toLong())
        var spent = 0.0
        for (c in 0 until chunks) {
            beforePiece() // waits while the phone needs a rest (see Guard)
            if (cancelled()) return
            val start = c * STRIDE
            val len = minOf(N, total - start)
            // planar for the model: left, then right (silence past the end)
            input.clear()
            for (j in 0 until N) input.put(if (j < len) x.get(2 * (start + j)) else 0f)
            for (j in 0 until N) input.put(if (j < len) x.get(2 * (start + j) + 1) else 0f)
            input.rewind()
            val t0 = System.nanoTime()
            val stems = OnnxTensor.createTensor(env, input, shape).use { mix ->
                session.run(mapOf("mix" to mix)).use { r -> (r[0] as OnnxTensor).floatBuffer }
            }
            spent += (System.nanoTime() - t0) / 1e9
            val last = c == chunks - 1
            val final = if (last) len else minOf(len, STRIDE) // done: up to where the next piece begins
            for (p in PARTS.indices) {
                val base = p * 2 * N // stems: (1, parts, 2, N)
                val cy = carry[p]
                for (j in 0 until final) {
                    val w = window[j]
                    var l = stems.get(base + j) * w
                    var r = stems.get(base + N + j) * w
                    var weight = w
                    if (j < OVERLAP && c > 0) {
                        l += cy[2 * j]; r += cy[2 * j + 1]; weight += carryW[j]
                    }
                    val k = maxOf(weight, 1e-8f)
                    out[2 * j] = l / k
                    out[2 * j + 1] = r / k
                }
                if (!last) for (j in STRIDE until len) {
                    val w = window[j]
                    cy[2 * (j - STRIDE)] = stems.get(base + j) * w
                    cy[2 * (j - STRIDE) + 1] = stems.get(base + N + j) * w
                }
                part(p, out, final)
            }
            if (!last) for (j in STRIDE until len) carryW[j - STRIDE] = window[j]
            piece(c + 1, chunks, (spent / (c + 1) * (chunks - c - 1)).toInt())
        }
    }
}

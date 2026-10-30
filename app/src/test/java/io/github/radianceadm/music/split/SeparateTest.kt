package io.github.radianceadm.music.split

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.log10

/**
 * The phone's splitter against demucs-onnx itself: the same 20 s of a song
 * (made on the build server by split-ref.py), the same model. Skipped where
 * the reference isn't.
 */
class SeparateTest {
    private val dir = File("/srv/build/android/splittest")

    private fun floats(f: File) = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer() }

    @Test fun matchesDemucsOnnx() {
        assumeTrue(File(dir, "ref.f32").exists())
        val x = floats(File(dir, "input.f32"))
        val ref = floats(File(dir, "ref.f32"))
        val total = x.limit() / 2
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4); setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
        val session = env.createSession(File(dir, "model.onnx").absolutePath, opts)
        val got = Array(4) { FloatArray(2 * total) }
        val at = IntArray(4)
        Separate.run(env, session, x, total, { p, buf, frames ->
            System.arraycopy(buf, 0, got[p], 2 * at[p], 2 * frames)
            at[p] += frames
        })
        for (p in 0 until 4) {
            assertTrue("${Separate.PARTS[p]}: ${at[p]} of $total frames", at[p] == total)
            var worst = 0.0
            var signal = 0.0
            var noise = 0.0
            for (i in 0 until 2 * total) {
                val r = ref.get(p * 2 * total + i)
                val d = (got[p][i] - r).toDouble()
                worst = maxOf(worst, abs(d))
                signal += r.toDouble() * r
                noise += d * d
            }
            val sdr = 10 * log10(signal / maxOf(noise, 1e-20))
            println("${Separate.PARTS[p]}: worst ${"%.2e".format(worst)}, ${"%.0f".format(sdr)} dB from demucs-onnx")
            assertTrue("${Separate.PARTS[p]} differs by $worst", worst < 1e-3)
        }
    }
}

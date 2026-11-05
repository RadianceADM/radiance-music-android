package io.github.radianceadm.music.split

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.log10

/**
 * The phone's splitter (Spleeter.kt) against the bench's Python version
 * (spleeter_ort.py, picked by the owner after listening, #43): the same 20 s
 * of a song, the same fp16 models (the copies kept in Gradle's own folder:
 * app/build.gradle.kts), opened mapped as the app opens its downloads.
 * Skipped where either isn't (only the build server has them).
 */
class SpleeterTest {
    private val dir = File("/srv/build/android/splittest")
    private val models = File(System.getProperty("radiance.spleeter") ?: "")

    private fun floats(f: File) = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer() }

    private fun here() = File(dir, "spleeter-ref.f32").exists() && Model.FILES.all { File(models, it.name).length() == it.size }

    private fun session(env: OrtEnvironment, part: Model.Part, opts: OrtSession.SessionOptions): OrtSession {
        val f = File(models, part.name)
        val buf = FileInputStream(f).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }
        return env.createSession(buf, opts)
    }

    @Test fun matchesTheBench() {
        assumeTrue(here())
        val x = floats(File(dir, "input.f32"))
        val ref = floats(File(dir, "spleeter-ref.f32"))
        val total = x.limit() / 2
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4); setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
        val got = Array(2) { FloatArray(2 * total) }
        val at = IntArray(2)
        val began = System.nanoTime()
        session(env, Model.VOCALS, opts).use { v ->
            session(env, Model.MUSIC, opts).use { m ->
                Spleeter.run(env, v, m, x, total, { p, buf, frames ->
                    System.arraycopy(buf, 0, got[p], 2 * at[p], 2 * frames)
                    at[p] += frames
                })
            }
        }
        println("split ${total / 44100} s in ${"%.1f".format((System.nanoTime() - began) / 1e9)} s")
        for (p in 0 until 2) {
            assertEquals("${Spleeter.PARTS[p]}: frames", total, at[p])
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
            println("${Spleeter.PARTS[p]}: worst ${"%.2e".format(worst)}, ${"%.0f".format(sdr)} dB from the bench")
            assertTrue("${Spleeter.PARTS[p]} is $sdr dB from the bench", sdr > 60)
        }
        // the two halves add up to the song
        var signal = 0.0
        var noise = 0.0
        for (i in 0 until 2 * total) {
            val s = x.get(i).toDouble()
            val d = got[0][i] + got[1][i] - s
            signal += s * s
            noise += d * d
        }
        val sum = 10 * log10(signal / maxOf(noise, 1e-20))
        println("vocals + music vs the song: ${"%.0f".format(sum)} dB")
        assertTrue("the halves add up to the song only to $sum dB", sum > 60)
    }

    @Test fun silenceStaysSilentAndShortSongsWork() {
        assumeTrue(here())
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        for (total in listOf(1, 4095, 100_000)) {
            val x = java.nio.FloatBuffer.wrap(FloatArray(2 * total))
            val at = IntArray(2)
            var loudest = 0f
            session(env, Model.VOCALS, opts).use { v ->
                session(env, Model.MUSIC, opts).use { m ->
                    Spleeter.run(env, v, m, x, total, { p, buf, frames ->
                        for (i in 0 until 2 * frames) loudest = maxOf(loudest, abs(buf[i]))
                        at[p] += frames
                    })
                }
            }
            assertEquals(total, at[0])
            assertEquals(total, at[1])
            assertTrue("silence came out at $loudest", loudest < 1e-6f)
        }
    }
}

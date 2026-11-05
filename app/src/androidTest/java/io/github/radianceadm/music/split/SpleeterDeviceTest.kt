package io.github.radianceadm.music.split

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * How fast the phone splits (#43): Spleeter's models downloaded as the app
 * downloads them (checked), then 4 minutes of sound split with 4 cores, as a
 * split here does. The sound is made up (tones and noise): the time doesn't
 * depend on what's playing. The halves must add up to the sound.
 */
@RunWith(AndroidJUnit4::class)
class SpleeterDeviceTest {
    @Test fun splitsFourMinutes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val began = System.nanoTime()
        Model.getAll(context)
        Log.i(TAG, "models here in ${"%.1f".format((System.nanoTime() - began) / 1e9)} s")

        val total = 240 * 44100
        val x = ByteBuffer.allocateDirect(2 * total * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        var seed = 1L
        for (i in 0 until total) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val noise = ((seed ushr 40).toInt() / 16777216f - 0.5f) * 0.1f
            val t = i / 44100.0
            val tone = (0.2 * sin(2 * PI * 220 * t) + 0.1 * sin(2 * PI * 440 * t * (1 + 0.01 * sin(t)))).toFloat()
            x.put(tone + noise)
            x.put(tone * 0.8f + noise)
        }
        x.rewind()

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)
            setInterOpNumThreads(1)
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }
        fun open(p: Model.Part) = FileInputStream(Model.file(context, p)).channel.use { env.createSession(it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()), opts) }
        val loadStart = System.nanoTime()
        val at = IntArray(2)
        var worst = 0.0
        val first = FloatArray(2 * total) // vocals, to check the halves add up
        open(Model.VOCALS).use { v ->
            open(Model.MUSIC).use { m ->
                val loaded = (System.nanoTime() - loadStart) / 1e9
                val start = System.nanoTime()
                Spleeter.run(env, v, m, x, total, { p, buf, frames ->
                    if (p == 0) System.arraycopy(buf, 0, first, 2 * at[0], 2 * frames)
                    else for (i in 0 until 2 * frames) worst = maxOf(worst, abs((first[2 * at[1] + i] + buf[i] - x.get(2 * at[1] + i)).toDouble()))
                    at[p] += frames
                })
                val took = (System.nanoTime() - start) / 1e9
                Log.i(TAG, "models loaded in ${"%.1f".format(loaded)} s; 240 s of sound split in ${"%.1f".format(took)} s (${"%.0f".format(240 / took)}x real time), 4 cores; halves vs sound worst ${"%.1e".format(worst)}")
            }
        }
        assertEquals(total, at[0])
        assertEquals(total, at[1])
        assertTrue("the halves don't add up: $worst", worst < 1e-3)
    }

    private companion object { const val TAG = "radiance-split-test" }
}

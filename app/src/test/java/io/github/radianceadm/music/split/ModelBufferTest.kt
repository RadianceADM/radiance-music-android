package io.github.radianceadm.music.split

import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

/** The splitter opens its model mapped into memory (as the app maps it from inside the APK), not from a file path. */
class ModelBufferTest {
    @Test fun opensMapped() {
        // the copy the build fetched (app/build.gradle.kts), on the build server
        val f = File("/srv/build/android/gradle-home/radiance/htdemucs_fp16weights.onnx")
        assumeTrue(f.exists())
        val buf = FileInputStream(f).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }
        val env = OrtEnvironment.getEnvironment()
        env.createSession(buf).use { s ->
            assertEquals(1, s.numInputs)
            println("mapped model: inputs ${s.inputNames}, outputs ${s.outputNames}")
        }
    }
}

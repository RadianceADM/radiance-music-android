package io.github.radianceadm.music.split

import android.app.Application
import io.github.radianceadm.music.net.Plain
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.net.SocketException
import java.security.MessageDigest
import kotlin.random.Random

/**
 * The splitter's model, downloaded the first time the app opens: going on
 * from where a dropped download stopped, never keeping a file that isn't the
 * published one, and the setup due while it isn't here. A pretend server (no
 * network): a few MB of made-up bytes stand in for the model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bytes = Random(7).nextBytes(3_000_000)
    private val size = bytes.size.toLong()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val url = "https://models.example/htdemucs_fp16weights.onnx"

    /** What the pretend server was asked for (each request's Range, or "" for all of it). */
    private val asked = mutableListOf<String>()

    /**
     * A server sending [body] (the model's bytes, or others) as asked, the
     * Range honoured unless [ranges] is false; [dropAt]: the connection drops
     * once this many bytes of the file have gone (the first time only).
     */
    private fun server(body: ByteArray = bytes, ranges: Boolean = true, dropAt: Int? = null): OkHttpClient {
        var drop = dropAt
        return OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val range = req.header("Range").orEmpty()
            asked += range
            val from = if (ranges && range.isNotEmpty()) range.removePrefix("bytes=").substringBefore('-').toInt() else 0
            val cut = drop?.also { drop = null }
            val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
            if (from >= body.size) return@Interceptor b.code(416).message("Range Not Satisfiable").body(ByteArray(0).toResponseBody()).build()
            if (from > 0) b.code(206).message("Partial Content").header("Content-Range", "bytes $from-${body.size - 1}/${body.size}") else b.code(200).message("OK")
            b.body(if (cut != null) dropping(body, from, cut) else body.copyOfRange(from, body.size).toResponseBody()).build()
        }).build()
    }

    /** [body] from [from], the connection dropping at [cut]. */
    private fun dropping(body: ByteArray, from: Int, cut: Int) = object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength() = (body.size - from).toLong()
        override fun source(): BufferedSource = object : Source {
            var at = from
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (at >= cut) throw SocketException("Connection reset")
                val n = minOf(byteCount, (cut - at).toLong()).toInt()
                sink.write(body, at, n)
                at += n
                return n.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() {}
        }.buffer()
    }

    private fun get(file: File, client: OkHttpClient, progress: (Long) -> Unit = {}) = Model.get(file, url, size, sha, client, progress)

    @Test fun missingModelMeansSetupIsDue() {
        val context = RuntimeEnvironment.getApplication()
        val file = Model.file(context)
        file.delete()
        assertTrue(Model.due(context))
        Setup.check(context)
        assertTrue(Setup.state.value is Setup.State.Getting)
        // half a download isn't the model either
        file.parentFile!!.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(Model.SIZE / 2) }
        assertTrue(Model.due(context))
        // whole (only ever put there checked): ready, nothing to download
        RandomAccessFile(file, "rw").use { it.setLength(Model.SIZE) }
        assertFalse(Model.due(context))
        Setup.check(context)
        assertEquals(Setup.State.Ready, Setup.state.value)
        file.delete()
        Setup.check(context)
        assertTrue(Setup.state.value is Setup.State.Getting)
    }

    @Test fun downloadsChecksAndPutsInPlace() {
        val file = File(tmp.root, "splitter/model.onnx")
        var last = 0L
        get(file, server()) { last = it }
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(Model.partOf(file).exists())
        assertEquals(size, last)
        assertEquals(listOf(""), asked)
        assertFalse(Model.due(file, size))
    }

    @Test fun goesOnFromWhatAnEarlierTryLeft() {
        val file = File(tmp.root, "splitter/model.onnx")
        file.parentFile!!.mkdirs()
        Model.partOf(file).writeBytes(bytes.copyOfRange(0, 1_000_000))
        get(file, server())
        assertEquals(listOf("bytes=1000000-"), asked) // only the rest was asked for
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(Model.partOf(file).exists())
    }

    @Test fun aDroppedConnectionGoesOnWhereItStopped() {
        val file = File(tmp.root, "splitter/model.onnx")
        val client = server(dropAt = 1_234_567)
        try {
            get(file, client)
            fail("the connection dropped: it isn't all here")
        } catch (e: SocketException) {
            // what came before the drop is kept, and nothing's in place yet
        }
        assertEquals(1_234_567L, Model.partOf(file).length())
        assertFalse(file.exists())
        get(file, client)
        assertEquals(listOf("", "bytes=1234567-"), asked)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun aServerThatSendsItAllAgainStartsOver() {
        val file = File(tmp.root, "splitter/model.onnx")
        file.parentFile!!.mkdirs()
        Model.partOf(file).writeBytes(ByteArray(500_000) { 1 }) // left from before, and not even right
        get(file, server(ranges = false))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun aMismatchIsThrownAway() {
        val file = File(tmp.root, "splitter/model.onnx")
        val spoiled = bytes.copyOf().also { it[2_000_000] = (it[2_000_000] + 1).toByte() } // the right size, one byte off
        try {
            get(file, server(spoiled))
            fail("a file that isn't the published one was kept")
        } catch (e: Plain) {
            assertEquals("The splitter didn't download right. Try again.", e.words)
        }
        assertFalse(file.exists())
        assertFalse(Model.partOf(file).exists()) // deleted: the next try starts clean
        assertTrue(Model.due(file, size))
    }

    @Test fun aWrongSizedEndIsNeverKept() {
        val file = File(tmp.root, "splitter/model.onnx")
        try {
            get(file, server(bytes + ByteArray(10)))
            fail("a file longer than the model was kept")
        } catch (e: Plain) {
            assertEquals("The splitter didn't download right. Try again.", e.words)
        }
        assertFalse(file.exists())
        assertFalse(Model.partOf(file).exists())
    }
}

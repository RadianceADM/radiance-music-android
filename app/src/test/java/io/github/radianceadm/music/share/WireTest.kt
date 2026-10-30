package io.github.radianceadm.music.share

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import kotlin.random.Random

/** Two devices on one computer: songs go across, and what the receiver has already never does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WireTest {
    private fun bytes(n: Int, seed: Int) = Random(seed).nextBytes(n)

    /** Two songs: A (song + four parts + cover), B (song only). */
    private val data = mapOf(
        "yt:A" to mapOf("song" to bytes(900_000, 1), "drums" to bytes(300_000, 2), "bass" to bytes(300_001, 3), "other" to bytes(299_999, 4), "vocals" to bytes(300_000, 5), "cover" to bytes(40_000, 6)),
        "yt:B" to mapOf("song" to bytes(1_234_567, 7)),
    )

    private val offer = object : Wire.Offer {
        override fun items() = data.map { (key, files) ->
            Wire.Item(key, JSONObject().put("title", "Song $key"), files.mapValues { (kind, b) -> Wire.FileInfo("$key.$kind", b.size.toLong()) })
        }
        override fun open(key: String, kind: String): InputStream? = data[key]?.get(kind)?.inputStream()
    }

    /** Has song A's file already (saved), nothing else. */
    private class Store(val already: Map<String, Set<String>>) : Wire.Store {
        val got = HashMap<Pair<String, String>, ByteArray>()
        val done = ArrayList<String>()
        override fun have(item: Wire.Item) = already[item.key] ?: emptySet()
        override fun write(item: Wire.Item, kind: String, input: InputStream, size: Long) {
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            assertEquals(size, out.size().toLong())
            got[item.key to kind] = out.toByteArray()
        }
        override fun done(item: Wire.Item) { done += item.key }
    }

    @Test fun pullTakesOnlyWhatsMissing() {
        val server = Wire.Server("get", offer, null, InetAddress.getLoopbackAddress()).start()
        val store = Store(mapOf("yt:A" to setOf("song")))
        var last: Wire.Progress? = null
        val n = Wire.pull("http://127.0.0.1:${server.port}", server.key, store) { last = it }
        server.close()
        assertEquals(2, n)
        assertEquals(listOf("yt:A", "yt:B"), store.done)
        assertTrue("A's song was sent again", ("yt:A" to "song") !in store.got)
        for ((key, files) in data) for ((kind, b) in files) {
            if (key == "yt:A" && kind == "song") continue
            assertTrue("$key $kind differs", store.got[key to kind]!!.contentEquals(b))
        }
        val expected = data.values.sumOf { f -> f.values.sumOf { it.size.toLong() } } - 900_000
        assertEquals(expected, last!!.totalBytes)
        assertEquals(expected, last!!.bytes)
    }

    @Test fun pushSendsOnlyWhatsMissing() {
        val store = Store(mapOf("yt:B" to setOf("song"), "yt:A" to setOf("drums", "bass", "other", "vocals")))
        val server = Wire.Server("put", null, store, InetAddress.getLoopbackAddress()).start()
        val n = Wire.push("http://127.0.0.1:${server.port}", server.key, offer)
        server.close()
        assertEquals(1, n) // B is all there already
        assertEquals(listOf("yt:A"), store.done)
        assertEquals(setOf("yt:A" to "song", "yt:A" to "cover"), store.got.keys)
        assertTrue(store.got["yt:A" to "song"]!!.contentEquals(data.getValue("yt:A").getValue("song")))
    }

    @Test fun theWrongKeyGetsNothing() {
        val server = Wire.Server("get", offer, null, InetAddress.getLoopbackAddress()).start()
        val r = runCatching { Wire.pull("http://127.0.0.1:${server.port}", "0".repeat(32), Store(emptyMap())) }
        server.close()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("403"))
    }

    @Test fun ticketsRoundTrip() {
        val t = Wire.Ticket("get", "192.168.1.23", 40123, Wire.newKey(), "Bassi's S24 Ultra", "DIRECT-RM-ab12", "p4ss word&=", "192.168.49.1")
        assertEquals(t, Wire.Ticket.parse(t.toUri()))
        assertEquals(null, Wire.Ticket.parse("https://example.com"))
    }
}

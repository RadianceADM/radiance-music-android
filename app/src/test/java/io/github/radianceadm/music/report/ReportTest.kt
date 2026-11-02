package io.github.radianceadm.music.report

import io.github.radianceadm.music.feedback.Api
import io.github.radianceadm.music.feedback.Refused
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Report a Problem on the phone: what goes, and what each answer means (radianceph#43). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportTest {
    private val server = MockWebServer()
    private val api by lazy {
        server.start()
        Api(server.url("/").toString(), OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build())
    }
    private val what = Report.What(version = "0.5.17", system = "Android 16, samsung SM-S928B", install = "00000000-0000-4000-8000-000000000000", log = "11-02 18:00:00.000 I/Radiance: hello\n")

    @After fun stop() = server.close()

    private fun json(code: Int, body: String) = MockResponse(code, headersOf("Content-Type", "application/json"), body)

    @Test fun emailAddressesAreTakenOut() {
        val s = Report.scrub("signed in as maria.cruz@example.com, then ana+music@mail.co.uk failed")
        assertFalse(s, s.contains("@"))
        assertEquals("signed in as (email), then (email) failed", s)
    }

    @Test fun onlyTheNewestWholeLinesGo() {
        val text = "one\ntwo\nthree\nfour\n"
        assertEquals(text, Report.newest(text, 100))
        assertEquals("four\n", Report.newest(text, 9))
    }

    @Test fun theBodyIsWhatTheAdminTakes() {
        val empty = what.body("   ")
        assertFalse(empty.has("note"))
        for (k in listOf("platform", "version", "system", "log", "install")) assertTrue(k, empty.has(k))
        assertEquals("android", empty.getString("platform"))
        assertEquals(Report.MOST_NOTE, what.body("a".repeat(3000)).getString("note").length)
    }

    @Test fun sendingGivesTheCodeAndSaysWhenItIsSwitchedOff() {
        server.enqueue(json(201, """{"id": 7, "code": "RM-0007"}"""))
        server.enqueue(json(201, """{"id": 12}"""))
        server.enqueue(json(503, "{}"))
        assertEquals("RM-0007", api.report(what.body("it stopped")))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/music/reports", req.url.encodedPath)
        val sent = JSONObject(req.body!!.utf8())
        assertEquals("it stopped", sent.getString("note"))
        assertEquals("android", sent.getString("platform"))
        assertEquals("RM-0012", api.report(what.body("")))
        try {
            api.report(what.body(""))
            fail("a 503 should say reports are switched off")
        } catch (e: Refused.ReportsOff) {
            assertTrue(e.words, e.words.contains("isn't taking these right now"))
        }
    }
}

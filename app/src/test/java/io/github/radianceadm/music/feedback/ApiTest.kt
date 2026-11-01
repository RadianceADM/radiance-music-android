package io.github.radianceadm.music.feedback

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The admin's feedback API (radianceph#42's contract) against a pretend admin:
 * Google's ID token swapped for the app's token, the answers exactly as the
 * admin takes them, and each "no" (401, 422, 429, 503) in plain words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ApiTest {
    private val server = MockWebServer()
    private lateinit var api: Api

    @Before fun start() {
        server.start()
        api = Api(server.url("/").toString(), OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build())
    }

    @After fun stop() = server.close()

    private fun json(body: String, code: Int = 200, vararg headers: String) =
        MockResponse(code, headersOf("Content-Type", "application/json", *headers), body)

    private val all = Answers(enjoying = true, comment = "  Love the Mixer!  ").let { a -> Area.entries.foldIndexed(a) { i, acc, area -> acc.rate(area, 5 - i % 3) } }

    @Test fun googleSignInSwapsGooglesTokenForTheAppsOwn() {
        server.enqueue(json("""{"token": "rmu_abc", "name": "Ana Reyes", "email": "ana@example.com"}"""))
        val s = api.google("google-id-token")
        assertEquals(Saved("rmu_abc", "Ana Reyes", "ana@example.com"), s)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/music/google", r.url.encodedPath)
        assertEquals("google-id-token", JSONObject(r.body!!.utf8()).getString("id_token"))
        assertNull(r.headers["Authorization"]) // no token yet
        assertEquals("application/json", r.headers["Accept"])
    }

    @Test fun theAnswersGoExactlyAsTheAdminTakesThem() {
        server.enqueue(json("""{"ok": true, "share": true}"""))
        assertTrue(api.feedback("rmu_abc", all.json("0.5.16")))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/music/feedback", r.url.encodedPath)
        assertEquals("Bearer rmu_abc", r.headers["Authorization"])
        val body = JSONObject(r.body!!.utf8())
        assertEquals(setOf("enjoying", "ratings", "comment", "app_version", "platform"), body.keys().asSequence().toSet())
        assertEquals(true, body.getBoolean("enjoying"))
        val ratings = body.getJSONObject("ratings")
        // exactly the five, as whole numbers 1 to 5
        assertEquals(setOf("overall", "sound", "mixer", "lyrics", "sharing"), ratings.keys().asSequence().toSet())
        assertEquals(listOf(5, 4, 3, 5, 4), listOf("overall", "sound", "mixer", "lyrics", "sharing").map { ratings.getInt(it) })
        assertEquals("Love the Mixer!", body.getString("comment"))
        assertEquals("0.5.16", body.getString("app_version"))
        assertEquals("android", body.getString("platform"))
    }

    @Test fun aBlankCommentIsntSentAndShareFalseComesBack() {
        server.enqueue(json("""{"ok": true, "share": false}"""))
        assertFalse(api.feedback("rmu_abc", all.copy(enjoying = false).write("   ").json("0.5.16")))
        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertFalse(body.has("comment"))
        assertEquals(false, body.getBoolean("enjoying"))
    }

    @Test fun answersAreCompleteOnlyWithEnjoyingAndAllFiveStars() {
        assertFalse(Answers().complete)
        assertEquals(Area.entries, Answers().missing)
        val some = Answers(enjoying = false).rate(Area.OVERALL, 2).rate(Area.LYRICS, 9)
        assertEquals(5, some.ratings[Area.LYRICS]) // kept within 1 to 5
        assertEquals(listOf(Area.SOUND, Area.MIXER, Area.SHARING), some.missing)
        assertFalse(some.complete)
        assertTrue(all.complete)
        assertFalse(all.copy(enjoying = null).complete)
        assertEquals(Answers.MAX_COMMENT, Answers().write("a".repeat(5000)).comment.length)
    }

    @Test fun aTokenThatStoppedWorkingIsSignedOut() {
        server.enqueue(json("""{"error": "unauthenticated"}""", 401, "WWW-Authenticate", "Bearer"))
        try {
            api.feedback("rmu_old", all.json("0.5.16"))
            fail("401 should refuse")
        } catch (e: Refused.SignedOut) {
            assertEquals(Refused.SIGNED_OUT, e.words)
        }
    }

    @Test fun googlesTokenNotTakenIsSaidAsSignInNotSignedOut() {
        server.enqueue(json("""{"error": "invalid_token"}""", 401))
        try {
            api.google("bad")
            fail("401 should refuse")
        } catch (e: Refused.GoogleSaidNo) {
            assertEquals("Google sign-in didn't go through. Try again.", e.words)
        }
    }

    @Test fun anAnswerNotTakenShowsTheAdminsMessage() {
        server.enqueue(json("""{"message": "The ratings.sound field must be between 1 and 5.", "errors": {"ratings.sound": ["The ratings.sound field must be between 1 and 5."]}}""", 422))
        try {
            api.feedback("rmu_abc", all.json("0.5.16"))
            fail("422 should refuse")
        } catch (e: Refused.Check) {
            assertEquals("The ratings.sound field must be between 1 and 5.", e.words)
            assertEquals(listOf("The ratings.sound field must be between 1 and 5."), e.errors["ratings.sound"])
        }
    }

    @Test fun tooManyTriesSaysWhenToTryAgain() {
        server.enqueue(json("""{"message": "Too Many Attempts."}""", 429, "Retry-After", "120"))
        try {
            api.feedback("rmu_abc", all.json("0.5.16"))
            fail("429 should refuse")
        } catch (e: Refused.TooMany) {
            assertEquals(120L, e.waitSeconds)
            assertEquals("Too many tries just now. Try again in 2 minutes.", e.words)
        }
    }

    @Test fun retryAfterInWords() {
        assertEquals("Try again in a few minutes.", Api.tryAgain(null))
        assertEquals("Try again in a minute.", Api.tryAgain(0))
        assertEquals("Try again in a minute.", Api.tryAgain(45))
        assertEquals("Try again in a minute.", Api.tryAgain(60))
        assertEquals("Try again in 2 minutes.", Api.tryAgain(61))
        assertEquals("Try again in 59 minutes.", Api.tryAgain(3540))
        assertEquals("Try again in an hour.", Api.tryAgain(3600))
        assertEquals("Try again in 2 hours.", Api.tryAgain(3601))
        // as seconds, or as a date
        val now = 1_780_000_000_000L
        assertEquals(30L, Api.waitSeconds("30", now))
        assertEquals(90L, Api.waitSeconds(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(now + 90_000).atZone(java.time.ZoneOffset.UTC)), now))
        assertNull(Api.waitSeconds(null, now))
        assertNull(Api.waitSeconds("soon", now))
    }

    @Test fun signInNotSetUpOnTheAdminAndTheSiteDown() {
        server.enqueue(json("""{"error": "not_configured"}""", 503))
        try { api.google("x"); fail() } catch (e: Refused.NotReady) { assertTrue(e.words.startsWith("Sign-in isn't ready yet")) }
        server.enqueue(json("""{"error": "google_unavailable"}""", 503))
        try { api.google("x"); fail() } catch (e: Refused.GoogleBusy) { assertEquals("Google isn't answering right now. Try again in a few minutes.", e.words) }
        server.enqueue(MockResponse(502, headersOf(), "<html>Bad gateway</html>"))
        try { api.feedback("rmu_abc", all.json("0.5.16")); fail() } catch (e: Refused.Down) { assertEquals("Radiance Music's site isn't answering right now. Try again in a few minutes.", e.words) }
    }

    @Test fun whoIsSignedInAndSigningOut() {
        server.enqueue(json("""{"name": "Ana Reyes", "email": "ana@example.com"}"""))
        assertEquals("Ana Reyes" to "ana@example.com", api.me("rmu_abc"))
        val me = server.takeRequest()
        assertEquals("GET", me.method)
        assertEquals("/api/music/me", me.url.encodedPath)
        assertEquals("Bearer rmu_abc", me.headers["Authorization"])
        server.enqueue(json("""{"ok": true}"""))
        api.signOut("rmu_abc")
        val out = server.takeRequest()
        assertEquals("DELETE", out.method)
        assertEquals("/api/music/token", out.url.encodedPath)
        assertEquals("Bearer rmu_abc", out.headers["Authorization"])
    }
}

package io.github.radianceadm.music.feedback

import io.github.radianceadm.music.net.Connectivity
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Send, start to end, against a pretend admin: signed out it signs in with
 * Google first and keeps the token; signed in it just sends; a token that
 * stopped working is dropped and you're asked to sign in again; offline, too
 * many tries, an answer not taken, sign-in not ready or cancelled: each said,
 * and nothing lost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SenderTest {
    private val server = MockWebServer()
    private lateinit var api: Api
    private val store = MemoryStore()
    private var signIns = 0
    private val steps = mutableListOf<Step>()

    private val answers = Area.entries.fold(Answers(enjoying = true)) { a, area -> a.rate(area, 5) }

    @Before fun start() {
        server.start()
        api = Api(server.url("/").toString(), OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build())
    }

    @After fun stop() {
        runCatching { server.close() }
        Connectivity.pretend(true)
    }

    private fun json(body: String, code: Int = 200, vararg headers: String) =
        MockResponse(code, headersOf("Content-Type", "application/json", *headers), body)

    private fun sender(clientId: String = "web-client.apps.googleusercontent.com", signIn: suspend () -> String = { signIns++; "google-id-token" }) =
        Sender(api, store, clientId, signIn)

    private fun send(s: Sender = sender(), a: Answers = answers) = runBlocking { s.send(a, "0.5.16") { steps += it } }

    @Test fun signedOutItSignsInWithGoogleFirstThenSends() {
        server.enqueue(json("""{"token": "rmu_new", "name": "Ana Reyes", "email": "ana@example.com"}"""))
        server.enqueue(json("""{"ok": true, "share": true}"""))
        assertEquals(Step.Thanks(share = true), send())
        assertEquals(1, signIns)
        assertEquals(listOf(Step.SigningIn, Step.Sending), steps)
        assertEquals("/api/music/google", server.takeRequest().url.encodedPath)
        val sent = server.takeRequest()
        assertEquals("/api/music/feedback", sent.url.encodedPath)
        assertEquals("Bearer rmu_new", sent.headers["Authorization"])
        assertEquals(5, JSONObject(sent.body!!.utf8()).getJSONObject("ratings").getInt("overall"))
        // kept for next time
        assertEquals(Saved("rmu_new", "Ana Reyes", "ana@example.com"), store.get())
    }

    @Test fun signedInItJustSends() {
        store.put(Saved("rmu_kept", "Ana Reyes", "ana@example.com"))
        server.enqueue(json("""{"ok": true, "share": false}"""))
        assertEquals(Step.Thanks(share = false), send())
        assertEquals(0, signIns)
        assertEquals(listOf<Step>(Step.Sending), steps)
        assertEquals("Bearer rmu_kept", server.takeRequest().headers["Authorization"])
    }

    @Test fun aTokenThatStoppedWorkingIsDroppedAndSignInIsAskedAgain() {
        store.put(Saved("rmu_removed", "Ana Reyes", "ana@example.com"))
        server.enqueue(json("""{"error": "unauthenticated"}""", 401, "WWW-Authenticate", "Bearer"))
        assertEquals(Step.Problem(Kind.SIGN_IN, Refused.SIGNED_OUT), send())
        assertNull(store.get())
        // Send again: Google's sign-in, a new token, sent
        server.enqueue(json("""{"token": "rmu_new", "name": "Ana Reyes", "email": "ana@example.com"}"""))
        server.enqueue(json("""{"ok": true, "share": true}"""))
        assertEquals(Step.Thanks(share = true), send())
        assertEquals(1, signIns)
    }

    @Test fun noClientIdYetSaysSignInIsntReadyAndAsksNothing() {
        assertEquals(Step.Problem(Kind.NOT_READY, Feedback.NOT_READY), send(sender(clientId = "")))
        assertEquals(0, signIns)
        assertEquals(0, server.requestCount)
    }

    @Test fun signInClosedSendsNothing() {
        val r = send(sender { throw SignInCancelled() })
        assertEquals(Kind.SIGN_IN, (r as Step.Problem).kind)
        assertEquals("You didn't sign in, so nothing was sent. Your answers are still here.", r.words)
        assertEquals(0, server.requestCount)
    }

    @Test fun notAllAnsweredYetSaysWhatsMissing() {
        assertEquals(Step.Problem(Kind.MISSING, Feedback.MISSING), send(a = answers.copy(enjoying = null)))
        assertEquals(0, server.requestCount)
    }

    @Test fun offlineSaysSoAndKeepsTheAnswers() {
        store.put(Saved("rmu_kept", "Ana Reyes", "ana@example.com"))
        Connectivity.pretend(false)
        server.close() // nothing answers
        assertEquals(Step.Problem(Kind.OFFLINE, Feedback.OFFLINE), send())
        assertEquals("rmu_kept", store.get()?.token) // still signed in
    }

    @Test fun cantConnectWhileOnlineSaysTryAgain() {
        store.put(Saved("rmu_kept", "Ana Reyes", "ana@example.com"))
        server.close()
        val r = send() as Step.Problem
        assertEquals(Kind.OTHER, r.kind)
        assertEquals("Couldn't connect. Check your Wi-Fi or mobile data. Your answers are still here.", r.words)
    }

    @Test fun tooManyTriesSaysWhen() {
        store.put(Saved("rmu_kept", "Ana Reyes", "ana@example.com"))
        server.enqueue(json("""{"message": "Too Many Attempts."}""", 429, "Retry-After", "40"))
        assertEquals(Step.Problem(Kind.LATER, "Too many tries just now. Try again in a minute."), send())
        assertEquals("rmu_kept", store.get()?.token)
    }

    @Test fun anAnswerNotTakenShowsTheMessage() {
        store.put(Saved("rmu_kept", "Ana Reyes", "ana@example.com"))
        server.enqueue(json("""{"message": "The comment may not be greater than 2000 characters.", "errors": {"comment": ["The comment may not be greater than 2000 characters."]}}""", 422))
        assertEquals(Step.Problem(Kind.CHECK, "The comment may not be greater than 2000 characters."), send())
    }

    @Test fun googleSignInNotSetUpOnTheAdmin() {
        server.enqueue(json("""{"error": "not_configured"}""", 503))
        val r = send() as Step.Problem
        assertEquals(Kind.NOT_READY, r.kind)
        assertNull(store.get())
    }
}

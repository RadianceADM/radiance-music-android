package io.github.radianceadm.music.feedback

import io.github.radianceadm.music.net.Http
import io.github.radianceadm.music.net.Plain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Who's signed in for sending feedback: the admin's app [token] (`rmu_…`, it doesn't expire) and whose it is. */
data class Saved(val token: String, val name: String, val email: String)

/**
 * The admin's side of Radiance Music feedback (admin.radianceph.com, the
 * radianceph#42 contract): Google sign-in → the app's token, the answers, who's
 * signed in, signing out. [base] is another address for the tests.
 */
class Api(base: String = BASE, private val client: OkHttpClient = Http.client) {
    private val base = base.trimEnd('/')

    /** A Google ID token (Credential Manager's, for the Web client) for the app's own token. */
    fun google(idToken: String): Saved {
        val j = call("POST", "/api/music/google", null, JSONObject().put("id_token", idToken), signingIn = true)
        return Saved(j.getString("token"), j.optString("name"), j.optString("email"))
    }

    /** Sends [body] (Answers.json): whether to offer sharing the app (4 or 5 stars overall). */
    fun feedback(token: String, body: JSONObject): Boolean = call("POST", "/api/music/feedback", token, body).optBoolean("share")

    /** Who [token] is: name and email. */
    fun me(token: String): Pair<String, String> = call("GET", "/api/music/me", token, null).let { it.optString("name") to it.optString("email") }

    /** Signs this phone out (the token stops working). */
    fun signOut(token: String) {
        call("DELETE", "/api/music/token", token, null)
    }

    private fun call(method: String, path: String, token: String?, body: JSONObject?, signingIn: Boolean = false): JSONObject {
        val req = Request.Builder().url(base + path)
            .header("Accept", "application/json")
            .header("User-Agent", "RadianceMusic-Android")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .method(method, body?.toString()?.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { r ->
            val j = runCatching { JSONObject(r.body.string()) }.getOrNull()
            if (r.isSuccessful) return j ?: JSONObject()
            throw refused(r.code, j, r.header("Retry-After"), signingIn)
        }
    }

    companion object {
        const val BASE = "https://admin.radianceph.com"
        private val JSON = "application/json".toMediaType()

        /** The admin's answer when it says no, in plain words ([signingIn]: the Google step, where a 401 is Google's token). */
        fun refused(code: Int, j: JSONObject?, retryAfter: String?, signingIn: Boolean, now: Long = System.currentTimeMillis()): Refused {
            val error = j?.optString("error").orEmpty()
            return when {
                code == 401 && signingIn -> Refused.GoogleSaidNo(error)
                code == 401 -> Refused.SignedOut()
                code == 422 -> Refused.Check(j?.optString("message")?.takeIf { it.isNotBlank() } ?: Refused.CHECK, errors(j))
                code == 429 -> Refused.TooMany(waitSeconds(retryAfter, now))
                code == 503 && error == "not_configured" -> Refused.NotReady()
                code == 503 && error == "google_unavailable" -> Refused.GoogleBusy()
                else -> Refused.Down(code, j?.optString("message")?.takeIf { it.isNotBlank() })
            }
        }

        /** 422's `errors`: each field's reasons. */
        private fun errors(j: JSONObject?): Map<String, List<String>> {
            val e = j?.optJSONObject("errors") ?: return emptyMap()
            return e.keys().asSequence().associateWith { k ->
                e.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOfNotNull(e.optString(k).takeIf { it.isNotBlank() })
            }
        }

        /** Retry-After in seconds, either as seconds or as a date; null if it's not there (or not readable). */
        fun waitSeconds(header: String?, now: Long): Long? {
            val h = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            h.toLongOrNull()?.let { return it.coerceAtLeast(0) }
            return runCatching {
                val at = ZonedDateTime.parse(h, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                ((at - now + 999) / 1000).coerceAtLeast(0)
            }.getOrNull()
        }

        /** When to try again, in plain words ("Try again in 3 minutes."). */
        fun tryAgain(seconds: Long?): String = when {
            seconds == null -> "Try again in a few minutes."
            seconds <= 60 -> "Try again in a minute."
            seconds < 3600 -> "Try again in ${(seconds + 59) / 60} minutes."
            else -> ((seconds + 3599) / 3600).let { h -> if (h == 1L) "Try again in an hour." else "Try again in $h hours." }
        }
    }
}

/** The admin said no: [words] are said to you as they are (Errors.say passes them through). */
sealed class Refused(words: String, detail: String) : Plain(words, detail) {
    /** Any call with a token: it doesn't work any more (signed out elsewhere, or removed). Drop it and sign in again. */
    class SignedOut : Refused(SIGNED_OUT, "401 unauthenticated")
    /** Google's sign-in token wasn't taken. */
    class GoogleSaidNo(error: String) : Refused("Google sign-in didn't go through. Try again.", "401 $error")
    /** An answer the admin won't take: its own message. */
    class Check(message: String, val errors: Map<String, List<String>>) : Refused(message, "422 ${errors.keys}")
    /** Too many tries: when to try again. */
    class TooMany(val waitSeconds: Long?) : Refused("Too many tries just now. ${Api.tryAgain(waitSeconds)}", "429 $waitSeconds s")
    /** Google sign-in isn't set up on the admin yet. */
    class NotReady : Refused("Sign-in isn't ready yet on our side. Your answers are kept: try again later.", "503 not_configured")
    /** The admin couldn't reach Google just now. */
    class GoogleBusy : Refused("Google isn't answering right now. Try again in a few minutes.", "503 google_unavailable")
    /** Anything else (the site down, a 500): try later. */
    class Down(code: Int, message: String?) : Refused(
        if (code in 500..599) "Radiance Music's site isn't answering right now. Try again in a few minutes." else message ?: "That didn't go through. Try again.",
        "HTTP $code",
    )

    companion object {
        const val SIGNED_OUT = "You've been signed out. Sign in again to send: your answers are still here."
        const val CHECK = "Something in your answers wasn't taken. Check them and send again."
    }
}

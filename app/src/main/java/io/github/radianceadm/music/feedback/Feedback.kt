package io.github.radianceadm.music.feedback

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import io.github.radianceadm.music.App
import io.github.radianceadm.music.net.Connectivity
import io.github.radianceadm.music.net.Errors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import io.github.radianceadm.music.net.Plain
import io.github.radianceadm.music.ui.Notice
import io.github.radianceadm.music.update.Updates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Where the feedback pop-up is. */
sealed interface Step {
    /** The questions. */
    data object Form : Step
    /** Google's sign-in is showing (Send, signed out). */
    data object SigningIn : Step
    data object Sending : Step
    /** It didn't go: why, in plain words; the answers are still there. */
    data class Problem(val kind: Kind, val words: String) : Step
    /** Sent: thanks ([share]: 4 or 5 stars overall, so "Share Radiance Music" too). */
    data class Thanks(val share: Boolean) : Step
}

/** What kind of problem, for its icon and the button under it. */
enum class Kind {
    /** Not all answered yet (the stars still to tap show). */
    MISSING,
    OFFLINE,
    /** Signed out (or sign-in didn't finish): Send signs in again. */
    SIGN_IN,
    /** The admin wouldn't take an answer (422): its message. */
    CHECK,
    /** Too many tries (429): when to try again. */
    LATER,
    /** Sign-in isn't set up yet. */
    NOT_READY,
    OTHER,
}

/**
 * Sending your answers: signed out, Google's sign-in first ([signIn] gives its
 * ID token, swapped for the app's own token, kept in [store]); then the answers.
 * A token that stopped working (401) is dropped, and you're asked to sign in
 * again. The answers are never lost here: whatever happens, they stay in the pop-up.
 */
class Sender(
    private val api: Api,
    private val store: TokenStore,
    private val clientId: String,
    private val signIn: suspend () -> String,
) {
    /** Sends [answers] (from app [version]): the step it ends on (Thanks, or a Problem). [step]: each step on the way. */
    suspend fun send(answers: Answers, version: String, step: (Step) -> Unit = {}): Step {
        if (!answers.complete) return Step.Problem(Kind.MISSING, Feedback.MISSING)
        var saved = store.get()
        if (saved == null) {
            if (clientId.isBlank()) return Step.Problem(Kind.NOT_READY, Feedback.NOT_READY)
            step(Step.SigningIn)
            val id = try {
                signIn()
            } catch (e: SignInCancelled) {
                return Step.Problem(Kind.SIGN_IN, "You didn't sign in, so nothing was sent. Your answers are still here.")
            } catch (e: Exception) {
                return problem(e)
            }
            saved = try {
                api.google(id)
            } catch (e: Exception) {
                return problem(e)
            }
            store.put(saved)
        }
        step(Step.Sending)
        return try {
            Step.Thanks(api.feedback(saved.token, answers.json(version)))
        } catch (e: Refused.SignedOut) {
            store.clear()
            Step.Problem(Kind.SIGN_IN, e.words)
        } catch (e: Exception) {
            problem(e)
        }
    }

    companion object {
        /** [e] as the pop-up says it ([online]: as the phone says now). */
        fun problem(e: Throwable, online: Boolean = Connectivity.online.value): Step.Problem {
            if (e is CancellationException) throw e
            return when (e) {
                is Refused.SignedOut, is Refused.GoogleSaidNo -> Step.Problem(Kind.SIGN_IN, e.words)
                is Refused.Check -> Step.Problem(Kind.CHECK, e.words)
                is Refused.TooMany -> Step.Problem(Kind.LATER, e.words)
                is Refused.NotReady -> Step.Problem(Kind.NOT_READY, e.words)
                is Refused -> Step.Problem(Kind.OTHER, e.words)
                is Plain -> Step.Problem(Kind.OTHER, e.words)
                else -> if (Errors.offline(e, online)) Step.Problem(Kind.OFFLINE, Feedback.OFFLINE)
                else Step.Problem(Kind.OTHER, "${Errors.say(e, !online)} Your answers are still here.")
            }
        }
    }
}

/**
 * The feedback pop-up (owner, 2026-11-01, radiance-music-android#14): "Are you
 * enjoying Radiance Music?", five star ratings, anything to tell us; Send asks
 * for Google sign-in when you're signed out and keeps your answers through it.
 * It asks by itself only now and then (Timing), at a free moment: when the music
 * is paused or a song has just ended, never mid-song, never on the first open,
 * the setup or the how-to guide. Settings → Rate Radiance Music opens it any time.
 */
object Feedback {
    /**
     * Google's "Web" client ID for Radiance Music (the admin's audience), from the
     * owner's Google Cloud project "radiance-music" (2026-11-01). Only the release
     * key's builds get a token (the Android client has its SHA-1); an empty ID
     * makes Send say sign-in isn't ready yet.
     */
    const val WEB_CLIENT_ID = "439537327949-vfktmgtdtamf752f0mc7q3ol47ik44b4.apps.googleusercontent.com"
    const val PRIVACY = "https://music.radianceph.com/privacy"

    const val MISSING = "Pick Yes! or Not really, and tap the stars for all five."
    const val NOT_READY = "Sign-in isn't ready yet in this version of the app. Your answers are kept: send them after the next update."
    const val OFFLINE = "You're offline. Your answers are kept: send them when you're back online."

    private const val PREFS = "feedback"
    /** Paused this long (not a quick pause and play): a free moment. */
    private const val PAUSE_MS = 2_000L

    private val _open = MutableStateFlow(false)
    /** The pop-up is up. */
    val open: StateFlow<Boolean> = _open
    private val _draft = MutableStateFlow(Answers())
    /** Your answers so far (kept through sign-in, and after "Not now", until they're sent). */
    val draft: StateFlow<Answers> = _draft
    private val _step = MutableStateFlow<Step>(Step.Form)
    val step: StateFlow<Step> = _step
    private val ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The admin (another address in the tests). */
    @VisibleForTesting var api = Api()
    private var job: Job? = null

    val busy get() = _step.value.let { it is Step.Sending || it is Step.SigningIn }

    // ---- when it asks ----

    /** A song started playing ([id]): one more played (the same song again, as after a pause, isn't counted twice). */
    fun played(context: Context, id: String?) {
        if (id.isNullOrEmpty()) return
        val p = prefs(context)
        if (p.getString("last_song", null) == id) return
        p.edit().putString("last_song", id).putInt("songs", songs(p) + 1).apply()
    }

    /** A song played to its end and the next began: a free moment. */
    fun songEnded() {
        ended.tryEmit(Unit)
    }

    /** The free moments: the music paused (and still paused a moment later) or a song just ended. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun moments(playing: StateFlow<Boolean>): Flow<Unit> = merge(
        playing.drop(1).transformLatest { if (!it) { delay(PAUSE_MS); emit(Unit) } },
        ended,
    )

    /** What Timing reads. */
    fun seen(context: Context, now: Long = System.currentTimeMillis()): Timing.Seen {
        val p = prefs(context)
        return Timing.Seen(firstUse(context, p, now), songs(p), p.getLong("asked", 0), p.getBoolean("never", false), p.getString("sent", null))
    }

    /**
     * At a free moment: asks now if it's time (and the dice say so), unless something
     * else is up (a listening party, the share sheet, a song that wouldn't play, the
     * update pop-up). Whether it opened.
     */
    fun maybeAsk(context: Context, now: Long = System.currentTimeMillis(), roll: Double = Random.nextDouble()): Boolean {
        if (_open.value) return false
        val app = App.instance
        if (app.party.joined || app.shareOpen.value != null || app.player.problem.value != null) return false
        if (updateAsking(context)) return false
        if (!Timing.asks(seen(context, now), now, Updates.current(context), roll)) return false
        prefs(context).edit().putLong("asked", now).apply()
        show(context, fresh = true)
        return true
    }

    /** Settings → Rate Radiance Music: open now, whatever the timing (a problem from the last try still shows). */
    fun openNow(context: Context) = show(context, fresh = false)

    private fun show(context: Context, fresh: Boolean) {
        if (!busy && (fresh || _step.value is Step.Thanks)) _step.value = Step.Form
        _open.value = true
        App.instance.scope.launch { Account.load(context) } // who's signed in, for the line by Send
    }

    /** "Not now" (or the pop-up swiped away): asks again in 30 days at the soonest; your answers stay. */
    fun notNow(context: Context) {
        prefs(context).edit().putLong("asked", System.currentTimeMillis()).apply()
        close()
    }

    /** "Don't ask again": it never asks by itself again (Settings still opens it). */
    fun never(context: Context) {
        prefs(context).edit().putBoolean("never", true).apply()
        close()
    }

    /** Closed (Done after Thanks, or Not now). */
    fun close() {
        _open.value = false
        if (!busy) _step.value = Step.Form
    }

    // ---- the answers ----

    fun answer(a: Answers) {
        _draft.value = a
        // all answered now: the "tap the stars" note goes
        val s = _step.value
        if (s is Step.Problem && s.kind == Kind.MISSING && a.complete) _step.value = Step.Form
    }

    /** Send: signs in first if needed (over [activity]), then sends. */
    fun send(activity: Context) {
        if (job?.isActive == true) return
        val answers = _draft.value
        val app = activity.applicationContext
        job = App.instance.scope.launch {
            Account.load(app)
            val version = Updates.current(app)
            val sender = Sender(api, Account, WEB_CLIENT_ID) { withContext(Dispatchers.Main) { GoogleSignIn.idToken(activity, WEB_CLIENT_ID) } }
            val end = sender.send(answers, version) { _step.value = it }
            if (end is Step.Thanks) {
                _draft.value = Answers()
                prefs(app).edit().putString("sent", version).putLong("asked", System.currentTimeMillis()).apply()
            }
            _step.value = end
            // the pop-up was closed meanwhile (a tap outside it): how it went, as a notice
            if (!_open.value) App.instance.tell(
                if (end is Step.Thanks) Notice("Thanks! Your feedback was sent.")
                else Notice("Your feedback wasn't sent. ${(end as? Step.Problem)?.words.orEmpty()}", Notice.Action("Open it", Icons.Rounded.Star) { openNow(app) }),
            )
        }
    }

    /** Signs this phone out of feedback (the admin forgets the token; here it's dropped even offline). */
    fun signOut(context: Context) {
        val app = context.applicationContext
        App.instance.scope.launch {
            Account.load(app)
            val token = Account.get()?.token
            Account.clear()
            if (token != null) runCatching { api.signOut(token) }
        }
    }

    // ---- kept on the phone ----

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Songs played: counted from this version on, starting from the songs in History (so it isn't 30 more for someone who's used it for weeks). */
    private fun songs(p: SharedPreferences): Int =
        if (p.contains("songs")) p.getInt("songs", 0) else runCatching { App.instance.library.history.value.size }.getOrDefault(0)

    /** The first day the app was used: the day it went on the phone (kept from the first look). */
    private fun firstUse(context: Context, p: SharedPreferences, now: Long): Long {
        p.getLong("first", 0).takeIf { it > 0 }?.let { return it }
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }.getOrNull()?.takeIf { it > 0 } ?: now
        val first = minOf(installed, now)
        p.edit().putLong("first", first).apply()
        return first
    }

    /** The update pop-up is asking about a newer version: that goes first. */
    private fun updateAsking(context: Context): Boolean = when (val s = Updates.state.value) {
        is Updates.State.Available -> Updates.asks(context, s.release)
        is Updates.State.Ready -> Updates.asks(context, s.release)
        else -> false
    }
}

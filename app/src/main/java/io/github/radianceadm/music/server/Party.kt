package io.github.radianceadm.music.server

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.radianceadm.music.App
import io.github.radianceadm.music.net.Errors
import io.github.radianceadm.music.play.PlaybackService
import io.github.radianceadm.music.play.PlayerHub
import io.github.radianceadm.music.play.mediaItem
import io.github.radianceadm.music.ui.Notice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.abs

/**
 * The listening party this phone is in (owner's choice, 2026-10-31):
 * everyone in it hears the same song at the same moment. The server keeps
 * the clock and the queue and sends every change over a WebSocket; this
 * phone measures its clock's offset to the server's (a few pings), plays the
 * party's song from the server's library, and checks every second where it
 * is against where the party is (PartyClock): a small drift is nudged out
 * with the speed (about 2 %), a big one is a jump. It plays on with the
 * screen off (the player's own background service). Leaving goes back to
 * your own music, where it was.
 */
class Party(private val app: App) {
    /** The party as this phone has it. */
    data class Now(
        val server: Server,
        val state: PartyState,
        /** You, on that server. */
        val me: Person?,
        /** Connected: its changes come at once. False: joining it again. */
        val linked: Boolean = false,
        /** Playing on this phone (you can stop listening here; the party goes on without you). */
        val listening: Boolean = true,
        /** How it's keeping time, in plain words ("In step with the party (+12 ms)"). */
        val sync: String = "Joining the party…",
        /** The clock's error at most (half the quickest ping's round trip), ms; null: not measured yet. */
        val clockMs: Long? = null,
    ) {
        val host get() = me != null && state.host?.id == me.id
        /** You may play, pause, skip, seek and order the queue (the host, or everyone when the host allows). */
        val controls get() = host || state.everyoneControls
        val hostName get() = if (host) "you" else state.host?.name ?: "the host"
    }

    private val _now = MutableStateFlow<Now?>(null)
    val now: StateFlow<Now?> = _now
    val joined get() = _now.value != null

    private val _floats = MutableSharedFlow<Reaction>(extraBufferCapacity = 16)
    /** Reactions as they come (they float up on the party's screen). */
    val floats: SharedFlow<Reaction> = _floats

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = mutableListOf<Job>()
    private var socket: WebSocket? = null
    /** Your own music before the party, to go back to. */
    private var before: PlayerHub.Mine? = null
    /** The server's clock minus this phone's (elapsed time since it started, ms). */
    private var offset = 0L
    /** How much further on a jump lands (the time the player takes to go on), learned. */
    private var lead = 0L
    private var jumpedAt = 0L
    /** The party's song in the player (its entry); [NONE] before the first state: whatever it is, it's loaded then. */
    private var entry: Long? = NONE
    private var starting: Job? = null
    private var retriedAt = 0L
    /** A play or pause that's the party's own (not you on the lock screen, headphones, Bluetooth). */
    private var ours = false
    private var lastReaction = -1L
    /** Each join is a new one: late answers for the last one are dropped. */
    private var round = 0
    private var backoff = 1000L
    private var watched: ExoPlayer? = null

    private fun local() = SystemClock.elapsedRealtime()
    private fun serverNow() = local() + offset

    private fun update(f: (Now) -> Now) {
        _now.value = _now.value?.let(f)
    }

    // ── joining and leaving ──

    /** Starts a party on [s] (you host it; [everyone]: everyone may play, pause and skip), and joins it. On already there: joins it. */
    fun start(s: Server, everyone: Boolean, then: () -> Unit = {}) {
        app.scope.launch {
            try {
                s.rms.startParty(everyone)
            } catch (e: Exception) {
                // one at a time on a server: one's on already, so it's joined
                if (!(e is Refused && e.code == 409)) {
                    Servers.heard(s, e)
                    app.tell(Notice("Couldn't start a party on ${s.name}. ${Errors.server(e, s.name)}", Notice.retry { start(s, everyone, then) }))
                    return@launch
                }
            }
            withContext(Dispatchers.Main) {
                join(s)
                then()
            }
        }
    }

    /** Joins the party on [s]: its song plays here, in step with everyone's. */
    fun join(s: Server) {
        if (_now.value?.server?.base == s.base) return
        if (joined) leave(resume = false)
        val r = ++round
        _now.value = Now(s, PartyState(on = true), s.me)
        app.player.connect {
            if (r != round) return@connect
            before = app.player.mine()
            jobs += app.scope.launch { clock(r) }
            jobs += main.launch {
                while (isActive) {
                    tick()
                    delay(TICK_MS)
                }
            }
            link(r)
        }
    }

    /** Leaves the party (it goes on for the others); [resume]: back to your own music, where it was. */
    fun leave(resume: Boolean = true) {
        if (_now.value == null) return
        round++
        jobs.forEach { it.cancel() }
        jobs.clear()
        starting?.cancel()
        starting = null
        socket?.close(1000, "left")
        socket = null
        watched?.removeListener(watch)
        watched = null
        lead = 0
        jumpedAt = 0
        entry = NONE
        lastReaction = -1
        backoff = 1000
        _now.value = null
        PlaybackService.exo?.let { ours { it.setPlaybackSpeed(1f) } }
        val mine = before
        before = null
        if (resume) app.player.resume(mine)
    }

    // ── what you do in it ──

    /** Listening on this phone, or not (the party goes on either way). */
    fun listenHere(on: Boolean) {
        update { it.copy(listening = on) }
        if (!on) PlaybackService.exo?.let { ours { it.pause() } }
        tick()
    }

    fun add(song: Long) = ask("add the song") { it.addToParty(song) }
    fun play() = ask("play") { it.control("play") }
    fun pause() = ask("pause") { it.control("pause") }
    fun skip() {
        if (_now.value?.controls == true) ask("skip") { it.control("skip") }
    }

    fun seek(ms: Long) {
        if (_now.value?.controls == true) ask("move the song") { it.control("seek", JSONObject().put("position_ms", ms.coerceAtLeast(0))) }
    }

    /** Moves a waiting song to [to] in the queue (0: next). */
    fun reorder(entry: Long, to: Int) = ask("move the song") { it.control("reorder", JSONObject().put("entry", entry).put("to", to.coerceAtLeast(0))) }
    fun remove(entry: Long) = ask("take the song out") { it.control("remove", JSONObject().put("entry", entry)) }
    /** The host lets everyone play, pause and skip, or only themselves. */
    fun allow(everyone: Boolean) = ask("change who controls the party") { it.control("allow", JSONObject().put("everyone", everyone)) }
    fun react(emoji: String) = ask("send the reaction") { it.react(emoji) }
    /** Ends the party for everyone (the host, or the server's owner). */
    fun end() = ask("end the party") { it.endParty() }

    /** [what] asked of the server; its answer is the party's state (the WebSocket brings it too). */
    private fun ask(what: String, call: (Rms) -> PartyState) {
        val s = _now.value?.server ?: return
        val r = round
        app.scope.launch {
            try {
                val p = call(s.rms)
                withContext(Dispatchers.Main) { if (r == round) apply(p) }
            } catch (e: Exception) {
                Servers.heard(s, e)
                app.tell(Notice("Couldn't $what. ${Errors.server(e, s.name)}"))
            }
        }
    }

    // ── the server's clock ──

    /** A few pings to the server's clock (the quickest wins), again every minute (a phone's clock wanders). */
    private suspend fun clock(r: Int) {
        while (r == round) {
            val s = _now.value?.server ?: return
            val pings = (1..PINGS).mapNotNull {
                runCatching {
                    val sent = local()
                    val server = s.rms.time()
                    PartyClock.Ping(sent, server, local())
                }.getOrNull()
            }
            val best = PartyClock.best(pings)
            if (best != null) withContext(Dispatchers.Main) {
                if (r != round) return@withContext
                offset = best.offset
                update { it.copy(clockMs = (best.rtt / 2).coerceAtLeast(1)) }
                tick()
            }
            delay(if (best == null) 5_000 else 60_000)
        }
    }

    // ── the party's changes, over its WebSocket ──

    private fun link(r: Int) {
        val s = _now.value?.server ?: return
        socket = s.rms.partySocket(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.launch {
                    if (r != round) return@launch
                    backoff = 1000
                    update { it.copy(linked = true) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                val p = if (o.optString("type") == "state") PartyState.from(o, s.rms::file) else null
                main.launch {
                    if (r != round) return@launch
                    when (o.optString("type")) {
                        "state" -> p?.let { apply(it) }
                        "removed" -> removed(s)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.launch { if (r == round) lost(r, s, if (response?.code == 401) Refused(401, "removed") else t) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.launch { if (r == round) lost(r, s, null) }
            }
        })
    }

    /** The connection dropped: joining again (more slowly each time), unless this phone was removed. */
    private fun lost(r: Int, s: Server, e: Throwable?) {
        if (e is Refused && e.removed) return removed(s)
        socket = null
        update { it.copy(linked = false, sync = if (Errors.offline(e)) "You're offline: the party plays on here as it was. Joining again once you're back…" else "Lost the party: joining again…") }
        val wait = backoff
        backoff = (backoff * 2).coerceAtMost(15_000)
        jobs += app.scope.launch {
            delay(wait)
            // still joined there? (removed meanwhile: it says so)
            val gone = runCatching { s.rms.me() }.exceptionOrNull()
            withContext(Dispatchers.Main) {
                if (r != round) return@withContext
                if (gone is Refused && gone.removed) removed(s) else link(r)
            }
        }
    }

    private fun removed(s: Server) {
        Servers.heard(s, Refused(401, "removed"))
        app.tell(Notice("This phone was removed from ${s.name}, so it left the party. Ask ${Servers.ownerOf(s)} for a new invite."))
        leave()
    }

    /** The party's state now: the song changes, reactions float up, the player follows. */
    @VisibleForTesting
    internal fun apply(p: PartyState) {
        val now = _now.value ?: return
        // an answer older than what's here already (the WebSocket was quicker)
        if (p.id == now.state.id && p.version in 1 until now.state.version) return
        if (!p.on) {
            app.tell(Notice("The listening party on ${now.server.name} ended."))
            leave()
            return
        }
        // reactions since the last state float up (not the ones from before you came)
        val newest = p.reactions.maxOfOrNull { it.atServerMs } ?: 0L
        if (lastReaction >= 0) p.reactions.filter { it.atServerMs > lastReaction }.forEach { _floats.tryEmit(it) }
        lastReaction = maxOf(lastReaction, newest, 0L)
        _now.value = now.copy(state = p)
        if (p.song?.entry != entry) {
            entry = p.song?.entry
            load(p)
        }
        tick()
    }

    // ── keeping the player in step ──

    /** You paused or played on this phone (lock screen, Bluetooth, headphones out): listening here, or not. */
    private val watch = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (ours || !joined) return
            update { it.copy(listening = playWhenReady) }
            if (playWhenReady) tick()
        }
    }

    private inline fun ours(f: () -> Unit) {
        ours = true
        try {
            f()
        } finally {
            ours = false
        }
    }

    /** The party's new song into the player (it starts in time: [tick]). */
    private fun load(p: PartyState) {
        starting?.cancel()
        starting = null
        jumpedAt = 0
        val exo = PlaybackService.exo ?: return
        val song = p.song
        ours {
            exo.setPlaybackSpeed(1f)
            if (song == null) {
                exo.pause()
                exo.clearMediaItems()
            } else {
                val at = (PartyClock.target(p, serverNow())?.takeIf { _now.value?.clockMs != null } ?: 0L).coerceAtLeast(0)
                exo.playWhenReady = false
                exo.setMediaItem(song.track().mediaItem(app), at)
                exo.prepare()
            }
        }
    }

    private fun speed(exo: ExoPlayer, s: Float) {
        if (abs(exo.playbackParameters.speed - s) > 0.001f) ours { exo.setPlaybackSpeed(s) }
    }

    private fun hold(exo: ExoPlayer) {
        if (exo.playWhenReady) ours { exo.pause() }
        speed(exo, 1f)
    }

    /** Once a second (and at every change): where the party is, where the player is, and what to do about it. */
    private fun tick() {
        val now = _now.value ?: return
        val exo = PlaybackService.exo ?: return
        if (watched !== exo) {
            watched?.removeListener(watch)
            exo.addListener(watch)
            watched = exo
        }
        val p = now.state
        val song = p.song
        fun say(words: String) = update { if (it.sync == words) it else it.copy(sync = words) }
        if (p.id == null) {
            hold(exo)
            return say("Joining the party…")
        }
        if (now.clockMs == null) {
            hold(exo)
            return say("Setting the clock with ${now.server.name}…")
        }
        val target = PartyClock.target(p, serverNow())
        if (song == null || target == null) {
            hold(exo)
            return say(if (p.queue.isEmpty()) "Nothing playing. Add a song: it starts at once." else "Quiet for now.")
        }
        if (exo.currentMediaItem == null) load(p)
        if (!p.playing) {
            hold(exo)
            if (exo.playbackState != Player.STATE_IDLE && abs(exo.currentPosition - target) > 300) ours { exo.seekTo(target) }
            return say("Paused by ${now.hostName}.")
        }
        if (!now.listening) {
            hold(exo)
            return say("Not playing on this phone. Tap Listen here to join in.")
        }
        if (exo.playerError != null) {
            // a dropped stream: tried again every few seconds
            if (local() - retriedAt > 3_000) {
                retriedAt = local()
                ours { exo.prepare() }
            }
            return say("The song stopped loading. Trying again…")
        }
        if (target < 0) {
            // it starts in a moment: ready at 0, started right on time
            hold(exo)
            if (exo.currentPosition > 50) ours { exo.seekTo(0) }
            if (starting == null) starting = main.launch {
                delay(-target)
                starting = null
                val at = PartyClock.target(_now.value?.state ?: return@launch, serverNow()) ?: return@launch
                if (_now.value?.listening != true) return@launch
                ours {
                    exo.seekTo(at.coerceAtLeast(0))
                    exo.play()
                }
                jumpedAt = local()
            }
            return say("Starting…")
        }
        val length = song.durationMs
        if (length != null && target > length) {
            hold(exo)
            return say("Next song in a moment…")
        }
        if (!exo.playWhenReady) {
            ours {
                exo.seekTo(target + lead)
                exo.play()
            }
            speed(exo, 1f)
            jumpedAt = local()
            return say("Catching up…")
        }
        if (exo.playbackState == Player.STATE_BUFFERING) return say("Loading…")
        if (exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) return say("Paused by the phone for a moment (a call?).")
        val at = exo.currentPosition
        // the first check after a jump: how late it landed, learned for the next one
        if (jumpedAt > 0 && local() - jumpedAt > 700) {
            lead = PartyClock.learn(lead, at - target)
            jumpedAt = 0
        }
        when (val move = PartyClock.decide(at, target, lead)) {
            is PartyClock.Move.Jump -> {
                ours { exo.seekTo(move.to) }
                speed(exo, 1f)
                jumpedAt = local()
            }
            is PartyClock.Move.Nudge -> speed(exo, move.speed)
            PartyClock.Move.Steady -> speed(exo, 1f)
        }
        val ms = at - target
        val shown = "${if (ms >= 0) "+" else "−"}${abs(ms)} ms"
        say(if (abs(ms) <= 100) "In step with the party ($shown)" else "Catching up ($shown)")
    }

    /** Where the party's song is now (ms into it), by the server's clock; null: nothing playing, or the clock not set yet. */
    fun position(): Long? = _now.value?.takeIf { it.clockMs != null }?.let { PartyClock.target(it.state, serverNow()) }

    /** For the tests' pictures: in this party, as told (no server, no player). */
    @VisibleForTesting
    fun pretend(now: Now?) {
        _now.value = now
    }

    companion object {
        private const val TICK_MS = 1000L
        private const val NONE = -1L
        private const val PINGS = 6
    }
}

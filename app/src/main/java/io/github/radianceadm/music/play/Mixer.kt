package io.github.radianceadm.music.play

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.radianceadm.music.App
import io.github.radianceadm.music.data.Import
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.server.ServerSongs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The Mixer: a split song played as the whole song, as karaoke (the singing
 * down to [singing]), or as its singing only ([Voice]). Owner, 2026-11-05
 * (android#20): one tap, as Apple Music Sing, no slider per part. A song has
 * its two halves (Music, Vocals: how the phone splits since 0.5.17, #43) or
 * its four parts (split on the computer or a server; a song split in six on
 * the computer also plays its Piano and Guitar, with Other). Four win when a
 * song has both.
 *
 * A split song whose own file is on the phone plays as ONE stream, the file and
 * its parts together (PartsEngine), from its start, so a switch only glides
 * the volumes (owner: "kala ko ba sabay na sila nag peplay then inaadjust
 * nalang yung sound"): the whole song is the file itself, untouched. The
 * player keeps playing the song silently and drives everything (the lock
 * screen, Bluetooth buttons, seeking, the next song); the engine follows it,
 * and is never pulled back in step for small differences (that pull was the
 * "talon" every few seconds). Karaoke stays on from song to song (Ahead splits
 * the next one). A server's song split there plays its parts fetched from the
 * server (ServerSongs); without its own file here, the whole song is the
 * player's again (a fade out, then in, never both at once).
 */
class Mixer(private val app: App) {
    /** What a split song plays as: all of it, karaoke, or just the singing. */
    enum class Voice { Song, Karaoke, VocalsOnly }

    private val _gains = MutableStateFlow((PARTS + HALVES).distinct().associateWith { 1f })
    /** Each part's volume, as the voice wants it (all up: the whole song). */
    val gains: StateFlow<Map<String, Float>> = _gains

    private val prefs = app.getSharedPreferences("mixer", Context.MODE_PRIVATE)
    private val _voice = MutableStateFlow(Voice.Song)
    /** The whole song when the app opens; stays from song to song. */
    val voice: StateFlow<Voice> = _voice
    private val _singing = MutableStateFlow(prefs.getFloat(LEVEL, GUIDE).coerceIn(0f, 1f))
    /**
     * How much of the singing karaoke keeps: some at first, as Apple Music Sing
     * (owner, 2026-11-05: "wag mo i max yung mute ng vocals kase sa apple di
     * naman ganon"); press and slide the mic for more or less. Kept.
     */
    val singing: StateFlow<Float> = _singing

    /** The song playing has its parts on this phone (split here, copied, or fetched from its server). */
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: PartsEngine? = null
    private var watch: Job? = null
    private var song: String? = null
    private var track: Track? = null

    /** How the engine takes over from the player. */
    private enum class Start {
        /** The player is silent already (the song before played here too). */
        Quiet,
        /** A song just began on the player: a quick crossfade. */
        Quick,
        /** Mid-song: the player's sound out, then the engine's in, never both at once. */
        Dip,
    }

    /** The song's parts here: split here or copied, else fetched from its server; empty: none. */
    private fun partsOf(t: Track): Map<String, java.io.File> =
        Import.parts(app, t.key).takeIf { setFor(it.keys) != null } ?: ServerSongs.parts(app, t)

    /** [t] has its parts on this phone (split here, copied, or fetched from its server). */
    fun hasParts(t: Track): Boolean = setFor(partsOf(t).keys) != null

    /** Which set of parts [files] make: the four, else the two halves, else none. */
    private fun setFor(names: Set<String>): List<String>? = when {
        names.containsAll(PARTS) -> PARTS
        names.containsAll(HALVES) -> HALVES
        else -> null
    }

    /** The song's own file on this phone (its saved copy), if it has one. */
    private fun ownFile(t: Track): Uri? = (t.file ?: app.library.best(t).file)?.let { Uri.parse(it) }

    /** The engine plays [t]: karaoke or the singing only, or any split song with its own file here (then a switch is seamless). */
    private fun wanted(t: Track) = _voice.value != Voice.Song || ownFile(t) != null

    private fun look(t: Track?) {
        _available.value = t != null && hasParts(t)
    }

    /** The song playing changed: the parts for it, if it's split. */
    fun songChanged(t: Track?) {
        if (t?.key == song) return
        val old = engine
        watch?.cancel()
        watch = null
        engine = null
        // the song before plays out its last moment (it moved on by itself), or stops (skipped)
        old?.let { e -> if (e.nearEnd()) e.finish() else Thread { e.release() }.start() }
        song = t?.key
        track = t
        look(t)
        if (t != null && _available.value && wanted(t)) engage(if (old != null) Start.Quiet else Start.Quick)
        else app.player.setVolume(1f)
    }

    /** A split just finished (or a server's parts came): the song playing may have its parts now. */
    fun recheck() {
        scope.launch {
            val t = app.player.current.value ?: return@launch
            if (t.key != song || _available.value) return@launch
            look(t)
            // karaoke on: it comes in now; the whole song stays as it plays until the mic is tapped
            if (_available.value && engine == null && _voice.value != Voice.Song) engage(Start.Dip)
        }
    }

    /** The whole song, karaoke, or the singing only: for the four parts and the two halves alike. */
    fun voice(v: Voice) {
        _voice.value = v
        mix()
    }

    /** Karaoke's singing, as the mic's slider moves (karaoke goes on, as Apple Music Sing's does). */
    fun singing(level: Float) {
        _singing.value = level.coerceIn(0f, 1f)
        if (_voice.value != Voice.Karaoke) _voice.value = Voice.Karaoke
        mix()
    }

    /** The slider let go: its level kept for next time. */
    fun singingSet() = prefs.edit().putFloat(LEVEL, _singing.value).apply()

    private fun mix() {
        val v = _voice.value
        val (vocals, rest) = when (v) {
            Voice.Song -> 1f to 1f
            Voice.Karaoke -> _singing.value to 1f
            Voice.VocalsOnly -> 1f to 0f
        }
        _gains.value = (PARTS + HALVES).distinct().associateWith { if (it == "vocals") vocals else rest }
        val e = engine
        if (e != null) {
            for ((name, g) in _gains.value) e.setGain(name, g)
            EXTRAS.forEach { e.setGain(it, rest) } // a song split in six: Piano and Guitar go with Other
        }
        when {
            e == null -> if (_available.value && v != Voice.Song) engage(Start.Dip)
            // the whole song: the file itself, glided to (or the player's, when the file isn't here)
            v == Voice.Song -> if (e.hasSong) e.blend(1f) else leave()
            else -> e.blend(0f)
        }
    }

    /** The parts out and the player's sound back (only when the song's own file isn't here). */
    private fun leave() {
        val e = engine ?: return
        watch?.cancel()
        watch = null
        engine = null
        val hub = app.player
        Log.i(TAG, "the song from the player again at ${hub.position} ms")
        e.fadeTo(0f, DIP_OUT_S)
        scope.launch {
            delay((DIP_OUT_S * 1000).toLong() + e.aheadMs())
            ramp(0f, 1f, DIP_IN_S) { engine == null } // karaoke again meanwhile: that one has the player's volume
            Thread { e.release() }.start()
        }
    }

    /** The player's own volume from [from] to [to] over [seconds], while [still]. */
    private suspend fun ramp(from: Float, to: Float, seconds: Double, still: () -> Boolean) {
        val steps = maxOf(1, (seconds * 1000 / RAMP_STEP_MS).toInt())
        for (i in 1..steps) {
            if (!still()) return
            app.player.setVolume(from + (to - from) * i / steps)
            delay(RAMP_STEP_MS)
        }
    }

    fun playingChanged(on: Boolean) = engine?.setPlaying(on)

    fun seeked(ms: Long) = engine?.seek(ms)

    private fun engage(how: Start) {
        val t = track ?: return
        val files = partsOf(t)
        val set = setFor(files.keys) ?: return
        val hub = app.player
        // split in six on the computer: Piano and Guitar play too, moving with Other (the song stays whole)
        val names = if (set == PARTS) PARTS + EXTRAS.filter { it in files } else set
        val other = _gains.value["other"] ?: 1f
        val parts = names.associateWith { PartsEngine.file(files.getValue(it)) }
        val gains = _gains.value + EXTRAS.associateWith { other }
        val blend = if (_voice.value == Voice.Song) 1f else 0f
        val own = ownFile(t)
        val e = runCatching { PartsEngine(parts, gains, own?.let { PartsEngine.uri(app, it) }, blend) }
            .onFailure { if (own != null) Log.w(TAG, "the song's own file won't open here: the parts alone", it) }
            .recoverCatching { if (own != null) PartsEngine(parts, gains, null, 0f) else throw it }
            .getOrNull() ?: return
        if (!e.hasSong && _voice.value == Voice.Song) {
            Thread { e.release() }.start()
            hub.setVolume(1f)
            return
        }
        engine = e
        Log.i(TAG, "parts in (${names.joinToString()})${if (e.hasSong) " with the song's own file" else ""} at ${hub.position} ms, $how")
        // a moment ahead while it plays (it takes that long to be heard); paused, right where the song is
        val at = hub.position + if (hub.playing.value) START_LEAD_MS else 0L
        when (how) {
            Start.Quiet -> {
                hub.setVolume(0f)
                e.start(hub.position, hub.playing.value)
            }
            Start.Quick -> {
                e.fadeTo(1f, QUICK_S)
                e.start(at, hub.playing.value)
                scope.launch {
                    var waited = 0
                    while (engine === e && !e.started && waited++ < 100) delay(10)
                    ramp(1f, 0f, QUICK_S) { engine === e }
                }
            }
            Start.Dip -> {
                e.fadeTo(0f, 0.001)
                e.start(at, hub.playing.value)
                scope.launch {
                    ramp(1f, 0f, DIP_OUT_S) { engine === e }
                    if (engine === e) e.fadeTo(1f, DIP_IN_S)
                }
            }
        }
        watch = scope.launch {
            var told = false
            while (isActive) {
                delay(2000)
                if (engine !== e || !hub.playing.value || !e.started) continue
                val off = hub.position - e.positionMs()
                if (!told) {
                    Log.i(TAG, "the parts vs the player: $off ms apart")
                    told = true
                }
                // only when something went wrong (a long stall): small differences are left alone, a pull is heard
                if (abs(off) > LOST_MS) {
                    Log.w(TAG, "parts off the song by $off ms at ${hub.position} ms: put back in step")
                    e.seek(hub.position)
                }
            }
        }
    }

    companion object {
        val PARTS = listOf("drums", "bass", "other", "vocals")
        /** A song split in two (on the phone): Music (everything but the voice) and Vocals. */
        val HALVES = listOf("music", "vocals")
        /** A song split in six also has these (the rest of Other). */
        val EXTRAS = listOf("guitar", "piano")
        /** Karaoke keeps this much of the singing at first (owner, 2026-11-05: 10 %; Apple Music Sing doesn't take it all out either). */
        const val GUIDE = 0.1f
        private const val TAG = "radiance-mix"
        /** The singing's level as last slid (a new name with each new default, so the new one applies). */
        const val LEVEL = "singing.level"
        private const val START_LEAD_MS = 150L
        /** Off by more than this: lost, put back in step (the only time it's pulled). */
        private const val LOST_MS = 1500L
        private const val QUICK_S = 0.08
        /** The desktop's fallback switch (owner: "all i care is smoothness"). */
        private const val DIP_OUT_S = 0.6
        private const val DIP_IN_S = 1.2
        private const val RAMP_STEP_MS = 25L
    }
}

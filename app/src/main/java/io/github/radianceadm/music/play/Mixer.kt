package io.github.radianceadm.music.play

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
 * The Mixer: a split song's parts, each as loud as you like: its two halves
 * (Music, Vocals: how the phone splits since 0.5.17, #43) or its four parts
 * (split on the computer or a server; a song split in six on the computer also
 * plays its Piano and Guitar, with Other). Four win when a song has both. The player
 * keeps playing the song itself, silently, and drives everything (the lock
 * screen, Bluetooth buttons, seeking, the next song); the parts follow it,
 * mixed by PartsEngine. Your mix stays from song to song. A song from a
 * Radiance Music Server split there plays its parts fetched from the server
 * (ServerSongs), the same way.
 */
class Mixer(private val app: App) {
    private val _gains = MutableStateFlow((PARTS + HALVES).distinct().associateWith { 1f })
    val gains: StateFlow<Map<String, Float>> = _gains

    /** The sliders the song playing has: [PARTS] or [HALVES] (empty: not split). */
    private val _shown = MutableStateFlow<List<String>>(emptyList())
    val shown: StateFlow<List<String>> = _shown

    /** The song playing has its parts on this phone (split here, copied, or fetched from its server). */
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: PartsEngine? = null
    private var watch: Job? = null
    private var song: String? = null
    private var track: Track? = null

    private fun allUp() = _gains.value.values.all { it >= 0.999f }

    /** The song's parts here: split here or copied, else fetched from its server; empty: none. */
    private fun partsOf(t: Track): Map<String, java.io.File> =
        Import.parts(app, t.key).takeIf { setFor(it.keys) != null } ?: ServerSongs.parts(app, t)

    /** Which set of parts [files] make: the four, else the two halves, else none. */
    private fun setFor(names: Set<String>): List<String>? = when {
        names.containsAll(PARTS) -> PARTS
        names.containsAll(HALVES) -> HALVES
        else -> null
    }

    private fun look(t: Track?) {
        val set = t?.let { setFor(partsOf(it).keys) }
        _shown.value = set ?: emptyList()
        _available.value = set != null
    }

    /** The song playing changed: the parts for it, if it's mixed. */
    fun songChanged(t: Track?) {
        if (t?.key == song) return
        stop()
        song = t?.key
        track = t
        look(t)
        if (_available.value && !allUp()) engage()
    }

    /** A split just finished (or a server's parts came): the song playing may have its parts now. */
    fun recheck() {
        scope.launch {
            val t = app.player.current.value ?: return@launch
            if (t.key != song || _available.value) return@launch
            look(t)
            if (_available.value && !allUp()) engage()
        }
    }

    fun set(name: String, g: Float) {
        _gains.value = _gains.value + (name to g.coerceIn(0f, 1f))
        val e = engine
        if (e != null) {
            e.setGain(name, g)
            if (name == "other") EXTRAS.forEach { e.setGain(it, g) } // a song split in six: Piano and Guitar go with Other
        } else if (_available.value && !allUp()) engage()
    }

    /** Song (all up), No vocals, Vocals only: for the four parts and the two halves alike. */
    fun preset(vocals: Float, rest: Float) {
        for (p in (PARTS + HALVES).distinct()) set(p, if (p == "vocals") vocals else rest)
    }

    fun playingChanged(on: Boolean) = engine?.setPlaying(on)

    fun seeked(ms: Long) = engine?.seek(ms)

    private fun engage() {
        val t = track ?: return
        val files = partsOf(t)
        val set = setFor(files.keys) ?: return
        val hub = app.player
        // split in six on the computer: Piano and Guitar play too, moving with Other (the song stays whole)
        val names = if (set == PARTS) PARTS + EXTRAS.filter { it in files } else set
        val other = _gains.value["other"] ?: 1f
        val e = runCatching { PartsEngine(names.associateWith { files.getValue(it) }, _gains.value + EXTRAS.associateWith { other }) }.getOrNull() ?: return
        engine = e
        // the parts start where the song will be once they're ready (onward, never back)
        e.start(hub.position + START_LEAD_MS, hub.playing.value)
        scope.launch {
            // the song's own sound out over a quarter second, as the parts come in
            for (i in 1..10) {
                if (engine !== e) return@launch
                hub.setVolume(1f - i / 10f)
                delay(25)
            }
        }
        watch = scope.launch {
            while (isActive) {
                delay(1000)
                if (engine !== e || !hub.playing.value) continue
                // kept in step with the player (it's what the lock screen and seeking follow)
                if (abs(hub.position - e.positionMs()) > DRIFT_MS) e.seek(hub.position + START_LEAD_MS)
            }
        }
    }

    private fun stop() {
        watch?.cancel()
        watch = null
        engine?.let { e -> Thread { e.release() }.start() }
        engine = null
        app.player.setVolume(1f)
    }

    companion object {
        val PARTS = listOf("drums", "bass", "other", "vocals")
        /** A song split in two (on the phone): Music (everything but the voice) and Vocals. */
        val HALVES = listOf("music", "vocals")
        /** A song split in six also has these (the rest of Other). */
        val EXTRAS = listOf("guitar", "piano")
        private const val START_LEAD_MS = 150L
        private const val DRIFT_MS = 150L
    }
}

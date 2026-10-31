package io.github.radianceadm.music.split

import android.content.Context
import android.util.Log
import io.github.radianceadm.music.App
import io.github.radianceadm.music.net.Connectivity
import io.github.radianceadm.music.net.Errors
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Getting the splitter ready, the first time the app opens: its model
 * downloads by itself, nothing to press, while the start screen shows how far
 * along it is (owner: "if the pre-reqs are not yet there stay in the loading
 * screen with progress"). Offline or stopped, the start screen says why, with
 * Try again and Open Radiance Music now; it goes on by itself once the phone's
 * back online. It runs in the app's own scope (not the screen's), so it
 * outlives the start screen; a stop anywhere leaves a .part it goes on from,
 * and only a checked file is ever put in place (Model.get).
 */
object Setup {
    sealed interface State {
        /** The model is here: splitting works. */
        data object Ready : State
        /** Downloading: [done] of [total] bytes. */
        data class Getting(val done: Long, val total: Long = Model.SIZE) : State
        /** Downloaded; being checked to be the published file (a second or two). */
        data object Checking : State
        /** Stopped: [why] in plain words; [offline]: it goes on by itself once the phone's back online. */
        data class Stopped(val why: String, val offline: Boolean) : State
    }

    private const val TAG = "radiance-split"

    private val _state = MutableStateFlow<State>(State.Getting(0))
    val state: StateFlow<State> = _state

    private val _later = MutableStateFlow(false)
    /** "Open Radiance Music now" was pressed: the app without splitting for now, the setup going on behind it. */
    val later: StateFlow<Boolean> = _later

    private var job: Job? = null
    /** Try again, pressed. */
    private val again = Channel<Unit>(Channel.CONFLATED)

    /** Ready or not, as the app starts (only looks: nothing downloads until the app opens, [start]). */
    @Synchronized
    fun check(context: Context) {
        if (job?.isActive == true) return
        _state.value = if (Model.due(context)) State.Getting(Model.partOf(Model.file(context)).length()) else State.Ready
    }

    /** The app opens: the setup starts (or goes on) if the model isn't here yet. */
    @Synchronized
    fun start(app: App) {
        if (!Model.due(app)) {
            _state.value = State.Ready
            return
        }
        if (job?.isActive == true) return
        _state.value = State.Getting(Model.partOf(Model.file(app)).length())
        job = app.scope.launch { run(app) }
    }

    /** Try again: now, not waiting to be back online. */
    fun retry(app: App) {
        again.trySend(Unit)
        start(app)
    }

    /** Open Radiance Music now: songs play, splitting waits for the setup. */
    fun openNow() {
        _later.value = true
    }

    /** The start screen waits for it: not ready, and Open Radiance Music now not pressed. */
    fun waiting() = _state.value != State.Ready && !_later.value

    private suspend fun run(app: App) {
        val file = Model.file(app)
        var tries = 0
        while (true) {
            val got = runCatching {
                Model.get(file, progress = { _state.value = State.Getting(it) }, checking = { _state.value = State.Checking })
            }
            if (got.isSuccess) break
            val e = got.exceptionOrNull()!!
            Log.w(TAG, "the splitter's setup stopped", e)
            // the connection dropped (or ended early): a few quick tries more, each from where it stopped
            val connection = e is IOException
            if (connection && Connectivity.online.value && ++tries <= 3) {
                delay(2_000L * tries)
                continue
            }
            tries = 0
            while (again.tryReceive().isSuccess) Unit // a Try again from before this stop
            _state.value = State.Stopped(Errors.say(e), Errors.offline(e))
            // then Try again, or (a connection problem) the phone being online again
            val back = if (connection) Connectivity.online.dropWhile { it }.filter { it }.map { } else emptyFlow()
            merge(again.receiveAsFlow(), back).first()
            _state.value = State.Getting(Model.partOf(file).length())
        }
        Log.i(TAG, "the splitter's model is here")
        _state.value = State.Ready
        app.splits.start() // songs waiting to be split go on
    }
}

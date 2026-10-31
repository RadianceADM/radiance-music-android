package io.github.radianceadm.music.split

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.radianceadm.music.App
import io.github.radianceadm.music.R
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.Errors
import io.github.radianceadm.music.net.fail
import io.github.radianceadm.music.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File

/**
 * The songs waiting to be split, one at a time, on this phone. Kept in a
 * file, so a closed app picks up where it was the next time it opens.
 */
class Splits(private val app: App) {
    data class Now(val track: Track, val step: Splitter.Step)

    private val _queue = MutableStateFlow(load())
    /** Waiting (the one being split first). */
    val queue: StateFlow<List<Track>> = _queue
    private val _now = MutableStateFlow<Now?>(null)
    val now: StateFlow<Now?> = _now
    /** The last split that couldn't be done, and why. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem
    /** Songs split here (the Mixer looks again when one is done). */
    private val _done = MutableStateFlow(0)
    val done: StateFlow<Int> = _done

    @Volatile var stopAsked = false

    private val prefs = app.getSharedPreferences("splitter", Context.MODE_PRIVATE)
    /** How many of the phone's cores the splitter may use. */
    var cores: Int
        get() = prefs.getInt("cores", DEFAULT_CORES).coerceIn(1, MAX_CORES)
        set(v) { prefs.edit().putInt("cores", v.coerceIn(1, MAX_CORES)).apply() }

    private fun file() = File(app.filesDir, "split-queue.json")

    private fun load(): List<Track> = runCatching {
        val a = JSONArray(file().readText())
        (0 until a.length()).mapNotNull { Track.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(list: List<Track>) {
        _queue.value = list
        runCatching { file().writeText(JSONArray(list.map { it.toJson() }).toString()) }
    }

    fun waiting(key: String) = _queue.value.any { it.key == key }

    /** Splits [t] when its turn comes (saved first, if it isn't yet). */
    fun add(t: Track) {
        if (waiting(t.key) || Splitter.isSplit(app, t.key)) return
        _problem.value = null
        save(_queue.value + t)
        start()
    }

    fun remove(key: String) {
        if (_now.value?.track?.key == key) stopAsked = true
        save(_queue.value.filterNot { it.key == key })
    }

    /** Goes on with the queue (when the app opens, a song is added, or the splitter's just got ready). */
    fun start() {
        if (_queue.value.isEmpty()) return
        if (Model.due(app)) return // the splitter isn't here yet: the queue waits for it (Setup starts it then)
        runCatching { ContextCompat.startForegroundService(app, Intent(app, SplitService::class.java)) }
            .onFailure { Log.w("radiance-split", "couldn't start the splitter: ${it.message}") }
    }

    internal fun first(): Track? = _queue.value.firstOrNull()
    internal fun report(t: Track, step: Splitter.Step?) { _now.value = step?.let { Now(t, it) } }
    internal fun finished(t: Track, problem: String?) {
        _now.value = null
        if (problem != null) _problem.value = "Couldn't split “${t.title}”. $problem"
        save(_queue.value.filterNot { it.key == t.key })
        if (problem == null) _done.value += 1
    }

    companion object {
        val MAX_CORES = Runtime.getRuntime().availableProcessors()
        /** Half the cores: quick, and the phone stays cool and smooth (its music too). */
        val DEFAULT_CORES = (MAX_CORES / 2).coerceAtLeast(1)
    }
}

/** Splits the queue in the background, with a notification showing how far along it is. */
class SplitService : Service() {
    private var worker: Thread? = null
    private lateinit var notes: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notes = getSystemService(NotificationManager::class.java)
        notes.createNotificationChannel(NotificationChannel(CHANNEL, "Splitting songs", NotificationManager.IMPORTANCE_LOW).apply {
            description = "A song being split into drums, bass, other and vocals"
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(NOTE, note("Splitting songs", null, null), type)
        if (worker?.isAlive != true) worker = Thread({ run() }, "radiance-split").also { it.start() }
        return START_NOT_STICKY
    }

    private fun note(title: String, text: String?, done: Float?): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply { if (done != null) setProgress(1000, (done * 1000).toInt(), false) else setProgress(0, 0, true) }
            .build()
    }

    private fun run() {
        // below the music: the song playing never stutters for a split
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val app = App.instance
        val splits = app.splits
        val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "radiance:split")
        lock.acquire(6 * 60 * 60 * 1000L)
        try {
            while (true) {
                val t = splits.first() ?: break
                splits.stopAsked = false
                var problem: String? = null
                try {
                    // a liked song still saving: wait for its file
                    var song = app.library.best(t)
                    var waited = 0
                    while (song.file == null && waited < 600 && !splits.stopAsked) {
                        splits.report(t, Splitter.Step("Waiting for the song to save", 0f))
                        Thread.sleep(1000)
                        waited++
                        song = app.library.best(t)
                    }
                    if (song.file == null) fail("It isn't saved on this phone: like it to save it.")
                    var last = 0L
                    Splitter.split(this, song, splits.cores, { step ->
                        splits.report(t, step)
                        val now = System.currentTimeMillis()
                        if (now - last > 1000 || step.done >= 1f) {
                            last = now
                            notes.notify(NOTE, note("Splitting “${t.title}”", listOfNotNull(step.what.takeIf { it != "Splitting" }, "${(step.done * 100).toInt()} %", step.secondsLeft?.let { left(it) }).joinToString(" · "), step.done))
                        }
                    }, { splits.stopAsked })
                } catch (e: Throwable) {
                    Log.e("radiance-split", "split failed", e)
                    problem = Errors.say(e, offline = false) // in plain words (the log has the rest)
                }
                if (splits.stopAsked) problem = null
                splits.finished(t, if (splits.stopAsked) null else problem)
                if (problem == null && !splits.stopAsked) app.mixer.recheck()
            }
        } finally {
            if (lock.isHeld) lock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        private const val CHANNEL = "split"
        private const val NOTE = 21

        fun left(s: Int) = if (s < 60) "under a minute left" else "about ${(s + 30) / 60} min left"
    }
}

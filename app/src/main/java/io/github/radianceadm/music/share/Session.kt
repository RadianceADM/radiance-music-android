package io.github.radianceadm.music.share

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.radianceadm.music.App
import io.github.radianceadm.music.R
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One share at a time: this phone showing a QR (its songs to take, or a
 * place for songs to come), or this phone having scanned one. What the
 * screens show comes from [state].
 */
class Session(private val app: App) {
    sealed class State {
        object Idle : State()
        /** Getting the QR ready (the phone's own network takes a moment). */
        object Opening : State()
        /** Showing [ticket]; [what] the screen says it's for. */
        data class Showing(val ticket: Wire.Ticket, val what: String, val progress: Wire.Progress?) : State()
        data class Connecting(val to: String) : State()
        /** A QR asking for songs was scanned: which to send? */
        data class Choose(val ticket: Wire.Ticket) : State()
        data class Moving(val to: String, val receiving: Boolean, val progress: Wire.Progress) : State()
        data class Done(val message: String) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var server: Wire.Server? = null
    private var group: Direct.Group? = null
    private var job: Job? = null
    @Volatile private var stop = false

    private fun name() = android.provider.Settings.Global.getString(app.contentResolver, "device_name") ?: android.os.Build.MODEL

    /** Shows a QR for [songs] (with their videos if [videos]): the phone scanning it takes what it hasn't got. */
    fun send(songs: List<Track>, videos: Boolean, what: String, direct: Boolean) {
        val offer = PhoneShare.Offer(app, songs, videos)
        open("get", offer, null, what, direct)
    }

    /** Shows a QR for songs to come here (from a phone or computer that scans it). */
    fun receive(direct: Boolean) = open("put", null, PhoneShare.Store(app), "Songs sent here join Liked Songs", direct)

    private fun open(mode: String, offer: Wire.Offer?, store: Wire.Store?, what: String, direct: Boolean) {
        close()
        stop = false
        _state.value = State.Opening
        job = app.scope.launch {
            try {
                val s = Wire.Server(mode, offer, store, null, PhoneShare.pace(app)) { p ->
                    val now = _state.value
                    if (now is State.Showing) _state.value = now.copy(progress = p)
                    ShareService.update(app, if (mode == "get") "Sending songs" else "Receiving songs", p)
                }.start()
                server = s
                val g = if (direct) withContext(Dispatchers.Main) { Direct.open(app) } else null
                group = g
                val host = Direct.wifiAddress()
                if (host == null && g == null) error("this phone isn't on Wi-Fi, and couldn't make its own network")
                val ticket = Wire.Ticket(mode, host, s.port, s.key, name(), g?.name, g?.pass, g?.let { Direct.HOST })
                _state.value = State.Showing(ticket, what, null)
                ShareService.start(app)
            } catch (e: Throwable) {
                Log.e(TAG, "couldn't open a share", e)
                _state.value = State.Failed(e.message ?: "couldn't get ready")
                close(keepState = true)
            }
        }
    }

    /** A scanned QR: songs come here ("get"), or it asks which to send ("put"). */
    fun scanned(text: String) {
        val t = Wire.Ticket.parse(text) ?: run { _state.value = State.Failed("That QR isn't a Radiance Music share."); return }
        if (t.mode == "get") connect(t, null) else _state.value = State.Choose(t)
    }

    /** Sends [songs] to the device whose QR asked for them. */
    fun sendTo(t: Wire.Ticket, songs: List<Track>, videos: Boolean) = connect(t, PhoneShare.Offer(app, songs, videos))

    private fun connect(t: Wire.Ticket, offer: Wire.Offer?) {
        close()
        stop = false
        _state.value = State.Connecting(t.name)
        job = app.scope.launch {
            try {
                // the same Wi-Fi first; else the other phone's own network
                var base = t.host?.let { "http://$it:${t.port}" }?.takeIf { Wire.reachable(it, t.key, t.mode) }
                if (base == null && t.group != null && t.pass != null) {
                    _state.value = State.Connecting("${t.name} (its own Wi-Fi)")
                    group = withContext(Dispatchers.Main) { Direct.join(app, t.group, t.pass) }
                    if (group != null) {
                        val gh = "http://${t.groupHost ?: Direct.HOST}:${t.port}"
                        // a moment for the address to come up
                        for (i in 0 until 10) { if (Wire.reachable(gh, t.key, t.mode)) { base = gh; break }; Thread.sleep(700) }
                    }
                }
                if (base == null) error("couldn't reach ${t.name}. Are both on the same Wi-Fi? (Or let this phone join its own: allow Nearby devices.)")
                ShareService.start(app)
                val receiving = offer == null
                val report = { p: Wire.Progress ->
                    _state.value = State.Moving(t.name, receiving, p)
                    ShareService.update(app, if (receiving) "Receiving from ${t.name}" else "Sending to ${t.name}", p)
                }
                val n = if (receiving) Wire.pull(base, t.key, PhoneShare.Store(app), PhoneShare.pace(app), { stop }, report)
                else Wire.push(base, t.key, offer!!, PhoneShare.pace(app), { stop }, report)
                _state.value = State.Done(
                    when {
                        stop -> "Stopped."
                        n == 0 -> if (receiving) "Nothing new: this phone has all of it already." else "Nothing new: ${t.name} has all of it already."
                        receiving -> "$n song${if (n == 1) "" else "s"} from ${t.name}, in Liked Songs."
                        else -> "$n song${if (n == 1) "" else "s"} sent to ${t.name}."
                    },
                )
            } catch (e: Throwable) {
                Log.e(TAG, "share failed", e)
                _state.value = State.Failed(e.message ?: "it stopped")
            } finally {
                close(keepState = true)
            }
        }
    }

    fun cancel() {
        stop = true
        close()
    }

    /** Lets go of everything (the QR stops working). */
    fun close(keepState: Boolean = false) {
        server?.close(); server = null
        group?.close(); group = null
        if (!keepState) { job?.cancel(); job = null; _state.value = State.Idle }
        ShareService.stop(app)
    }

    companion object { const val TAG = "radiance-share" }
}

/** Keeps a share going with the screen off, with a notification showing how far along it is. */
class ShareService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Sharing songs", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTE, note(this, "Sharing songs", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "radiance:share").apply { acquire(3 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        if (wifi == null) wifi = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "radiance:share").apply { acquire() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "share"
        private const val NOTE = 31
        private var last = 0L

        fun start(c: Context) { runCatching { ContextCompat.startForegroundService(c, Intent(c, ShareService::class.java)) } }
        fun stop(c: Context) { runCatching { c.stopService(Intent(c, ShareService::class.java)) } }

        fun update(c: Context, title: String, p: Wire.Progress) {
            val now = System.currentTimeMillis()
            if (now - last < 1000 && p.bytes < p.totalBytes) return
            last = now
            runCatching { c.getSystemService(NotificationManager::class.java).notify(NOTE, note(c, title, p)) }
        }

        private fun note(c: Context, title: String, p: Wire.Progress?) = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(p?.let { describe(it) })
            .setContentIntent(PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .apply { if (p != null && p.totalBytes > 0) setProgress(1000, (p.bytes * 1000 / p.totalBytes).toInt(), false) }
            .build()

        fun describe(p: Wire.Progress): String = buildString {
            if (p.totalSongs > 0) append("${p.songs} of ${p.totalSongs} songs") else if (p.bytes > 0) append(mb(p.bytes))
            if (p.totalBytes > 0) append(" · ${mb(p.bytes)} of ${mb(p.totalBytes)}")
            p.now?.let { append(" · “$it”") }
        }

        fun mb(b: Long) = if (b >= 1L shl 30) "%.1f GB".format(b / 1073741824.0) else "${b shr 20} MB"
    }
}

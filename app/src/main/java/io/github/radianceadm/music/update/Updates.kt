package io.github.radianceadm.music.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.IntentCompat
import io.github.radianceadm.music.App
import io.github.radianceadm.music.R
import io.github.radianceadm.music.net.Http
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Updates over the air (owner, 2026-10-31: "OTA"): the newest release on GitHub,
 * looked up at start and on coming back to the app (at most every 6 hours) and
 * from About; downloaded into the app's own cache on "Update now" (music goes on
 * playing), checked to be the release's own file (SHA-256), this app, signed with
 * the same key and newer, then installed by Android on "Restart to update"
 * (which asks you to confirm, unless it updated this app before).
 *
 * The pop-up (owner, 2026-10-31: "a modal ... when there's an update, with a
 * remind me later option"): "Remind me later" hides it for a day, "Skip this
 * version" until the next one.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/RadianceADM/radiance-music-android/releases/latest"
    private const val PREFS = "updates"
    private const val EVERY_MS = 6 * 60 * 60 * 1000L
    /** "Remind me later": a day (owner, 2026-10-31). */
    const val LATER_MS = 24 * 60 * 60 * 1000L
    private const val TAG = "RadianceUpdates"
    /** The count of downloads (owner, 2026-10-31: analytics of downloads): one more update, the version only. */
    private const val COUNT = "https://admin.radianceph.com/api/music/count"

    /** [sha256]: the file's own fingerprint, as GitHub gives it (empty for files put up before it did). */
    data class Release(val version: String, val notes: String, val apk: String, val size: Long, val page: String, val sha256: String = "")

    sealed interface State {
        /** Not looked yet. */
        data object Unknown : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class Available(val release: Release, val note: String? = null) : State
        data class Downloading(val release: Release, val done: Float) : State
        /** Downloaded and checked: "Restart to update" puts it on. */
        data class Ready(val release: Release, val note: String? = null) : State
        /** Downloaded: Android is installing it, or asking you to. */
        data class Installing(val release: Release) : State
        /** Android needs your yes first: Install unknown apps, for Radiance Music. */
        data class Allow(val release: Release) : State
        data class Failed(val message: String, val release: Release?) : State
    }

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun current(context: Context): String = info(context).versionName.orEmpty()

    /** At start: what the last look found, then a new look if it's been 6 hours. */
    fun atStart(app: App) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        clean(app)
        remembered(app)?.let { if (newer(it.version, current(app))) _state.value = if (file(app, it).exists()) State.Ready(it) else State.Available(it) }
        if (System.currentTimeMillis() - prefs.getLong("checked", 0) < EVERY_MS) return
        app.scope.launch {
            delay(8_000) // after the start, not during it
            check(app)
        }
    }

    /** Whether the pop-up asks about [r] now: not the version you skipped, not within a day of "Remind me later". */
    fun asks(context: Context, r: Release): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return asks(r.version, prefs.getString("skipped", null), prefs.getLong("later", 0), System.currentTimeMillis())
    }

    fun asks(version: String, skipped: String?, laterUntil: Long, now: Long) = version != skipped && now >= laterUntil

    /** "Remind me later": the pop-up again in a day (the update stays in Settings). */
    fun remindLater(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("later", System.currentTimeMillis() + LATER_MS).apply()
    }

    /** "Skip this version": no pop-up about [r] (the next version asks again). */
    fun skip(context: Context, r: Release) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("skipped", r.version).apply()
    }

    /** Looks for a newer release now. */
    fun check(context: Context) {
        val app = context.applicationContext as App
        if (job?.isActive == true) return
        val before = _state.value
        if (before is State.Downloading || before is State.Ready || before is State.Installing || before is State.Allow) return // one's under way
        job = app.scope.launch {
            if (before !is State.Available) _state.value = State.Checking
            val found = runCatching { latest() }
            found.onSuccess { r ->
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("checked", System.currentTimeMillis())
                    .putString("release", r.toJson().toString())
                    .apply()
                _state.value = when {
                    !newer(r.version, current(app)) -> State.UpToDate(current(app))
                    file(app, r).exists() -> State.Ready(r)
                    else -> State.Available(r)
                }
            }.onFailure { e ->
                Log.w(TAG, "couldn't look for updates", e)
                _state.value = if (before is State.Available) before else State.Failed("Couldn't look for updates: ${e.message ?: "no connection"}", null)
            }
        }
    }

    /** Downloads [r] (picking up where it stopped) and checks it: then it's Ready to restart onto. */
    fun update(context: Context, r: Release) {
        val app = context.applicationContext as App
        if (job?.isActive == true) return
        job = app.scope.launch {
            runCatching {
                val file = download(app, r)
                if (!isActive) return@launch
                runCatching { verify(app, file, r) }.onFailure { file.delete() }.getOrThrow()
                _state.value = State.Ready(r)
            }.onFailure { e ->
                Log.w(TAG, "couldn't update", e)
                _state.value = State.Failed("Couldn't update: ${e.message ?: "the download stopped"}", r)
            }
        }
    }

    /** "Restart to update": Android puts the downloaded [r] on; the app closes, and a notification opens the new one. */
    fun restart(context: Context, r: Release) {
        val app = context.applicationContext as App
        val f = file(app, r)
        if (!f.exists()) return update(context, r)
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = State.Allow(r)
            return
        }
        if (job?.isActive == true) return
        job = app.scope.launch {
            runCatching {
                _state.value = State.Installing(r)
                verify(app, f, r) // still the one checked (nothing changed it since)
                counted(r)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("restarting", r.version).commit()
                install(app, f)
            }.onFailure { e ->
                Log.w(TAG, "couldn't install", e)
                f.delete()
                _state.value = State.Failed("Couldn't update: ${e.message ?: "the download was spoiled"}", r)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        (_state.value as? State.Downloading)?.let { _state.value = State.Available(it.release) }
    }

    /** Android's page where Radiance Music may be allowed to install its updates. */
    fun allowIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Back in the app: go on if installing is allowed now (back from that page), or look again if it's been 6 hours. */
    fun resumed(context: Context) {
        val s = _state.value
        if (s is State.Allow) {
            if (context.packageManager.canRequestPackageInstalls()) {
                if (file(context, s.release).exists()) restart(context, s.release) else update(context, s.release)
            }
            return
        }
        val checked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("checked", 0)
        if (System.currentTimeMillis() - checked >= EVERY_MS) check(context)
    }

    /** On mobile data (or another metered network): the size is worth saying. */
    fun metered(context: Context) = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    private fun latest(): Release {
        val req = Request.Builder().url(LATEST).header("User-Agent", "RadianceMusic-Android").header("Accept", "application/vnd.github+json").build()
        val body = Http.client.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "GitHub answered ${r.code}" }
            r.body.string()
        }
        val j = JSONObject(body)
        val assets = j.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .let { all -> all.firstOrNull { it.getString("name") == "RadianceMusic.apk" } ?: all.firstOrNull { it.getString("name").endsWith(".apk") } }
            ?: error("the newest release has no app file")
        return Release(
            version = j.getString("tag_name").removePrefix("v"),
            notes = j.optString("body"),
            apk = apk.getString("browser_download_url"),
            size = apk.optLong("size"),
            page = j.optString("html_url"),
            sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:").orEmpty(),
        )
    }

    private suspend fun download(app: App, r: Release): File {
        val done = file(app, r).apply { parentFile?.mkdirs() }
        if (done.length() > 0 && (r.size <= 0 || done.length() == r.size)) return done
        val part = File(done.parentFile, done.name + ".part")
        var tries = 0
        while (true) {
            val from = part.length()
            _state.value = State.Downloading(r, if (r.size > 0) from.toFloat() / r.size else 0f)
            val req = Request.Builder().url(r.apk).header("User-Agent", "RadianceMusic-Android")
                .apply { if (from > 0) header("Range", "bytes=$from-") }.build()
            val ok = runCatching {
                Http.client.newCall(req).execute().use resp@{ resp ->
                    if (resp.code == 416) return@resp // all here already
                    check(resp.isSuccessful) { "the download stopped (HTTP ${resp.code})" }
                    val append = resp.code == 206
                    FileOutputStream(part, append).use { out ->
                        val src = resp.body.byteStream()
                        val buf = ByteArray(256 * 1024)
                        var have = if (append) from else 0L
                        var shown = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive() // Cancel stops it here
                            val n = src.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            if (have - shown > 1_000_000 && r.size > 0) {
                                shown = have
                                _state.value = State.Downloading(r, have.toFloat() / r.size)
                            }
                        }
                    }
                }
            }
            if (ok.isSuccess && (r.size <= 0 || part.length() == r.size)) break
            if (++tries >= 5) throw ok.exceptionOrNull() ?: IllegalStateException("the download came short")
            delay(2_000L * tries) // a dropped connection: go on from here
        }
        check(part.renameTo(done)) { "couldn't keep the download" }
        return done
    }

    /** Adds one to the count of updates (no name, no address: the platform and the version). Never in the way. */
    private fun counted(r: Release) {
        runCatching {
            val req = Request.Builder().url("$COUNT?event=update&platform=android&version=${r.version}").header("User-Agent", "RadianceMusic-Android").build()
            Http.client.newCall(req).execute().close()
        }
    }

    /** It is the release's own file, this app, newer, and signed with the same key (else Android refuses it anyway, later). */
    private fun verify(context: Context, file: File, r: Release) {
        if (r.sha256.isNotEmpty()) check(sha256(file).equals(r.sha256, ignoreCase = true)) { "the download isn't the release's file; try again" }
        val pm = context.packageManager
        val got = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: run { file.delete(); error("the download isn't a whole app; try again") }
        check(got.packageName == context.packageName) { "the download is another app" }
        val mine = info(context)
        check(got.longVersionCode > mine.longVersionCode) { "the download isn't newer than this app" }
        val ours = mine.signingInfo?.apkContentsSigners
        val theirs = got.signingInfo?.apkContentsSigners
        if (ours != null && theirs != null) {
            check(theirs.any { t -> ours.any { it == t } }) { "this copy of the app was signed by someone else: update it from where you got it" }
        }
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("RadianceMusic.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 20) }
                s.fsync(out)
            }
            val back = Intent(context, Installed::class.java).setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(context, id, back, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pi.intentSender)
        }
    }

    /** Android's answer about an install: asks you, done, or why not. */
    class Installed : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val r = (_state.value as? State.Installing)?.release
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val ask = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                    runCatching { context.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // the new version takes over (this one closes)
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("restarting").apply()
                    _state.value = r?.let { State.Ready(it, "Not installed: restart to update any time.") } ?: State.Unknown
                }
                else -> {
                    val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "install failed: $status $why")
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("restarting").apply()
                    _state.value = State.Failed("Android didn't install it${why?.let { ": $it" } ?: ""}", r)
                }
            }
        }
    }

    /**
     * The new version is on (Android closed the old one to put it on): if it was our
     * "Restart to update", a quiet notification opens it again with one tap (Android
     * doesn't let an app open itself from the background).
     */
    class Updated : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString("restarting", null) ?: return // put on some other way (adb, a download): nothing to say
            prefs.edit().remove("restarting").apply()
            val notes = context.getSystemService(NotificationManager::class.java) ?: return
            if (!notes.areNotificationsEnabled()) return
            notes.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When an update is on and ready to open"
                setSound(null, null) // shown, never heard
                enableVibration(false)
            })
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            val pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Radiance Music ${current(context)} is ready")
                .setContentText("Updated. Tap to open it again.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { notes.notify(NOTE_ID, n) }
        }
    }

    private const val CHANNEL = "updates"
    private const val NOTE_ID = 7_013

    /** Downloads of versions that are on now (or older) aren't needed any more. */
    private fun clean(context: Context) {
        val now = current(context)
        File(context.cacheDir, "update").listFiles()?.forEach { f ->
            val v = f.name.removePrefix("RadianceMusic-").removeSuffix(".part").removeSuffix(".apk")
            if (!newer(v, now)) f.delete()
        }
    }

    /** Where [r]'s download is kept (whole and checked; a ".part" beside it while it downloads). */
    private fun file(context: Context, r: Release) = File(File(context.cacheDir, "update"), "RadianceMusic-${r.version}.apk")

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun remembered(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("release", null)
        ?.let { runCatching { JSONObject(it).toRelease() }.getOrNull() }

    @Suppress("DEPRECATION")
    private fun info(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    /** Whether version [a] comes after [b] (0.5.10 after 0.5.9). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun Release.toJson() = JSONObject().put("version", version).put("notes", notes).put("apk", apk).put("size", size).put("page", page).put("sha256", sha256)
    private fun JSONObject.toRelease() = Release(getString("version"), optString("notes"), getString("apk"), optLong("size"), optString("page"), optString("sha256"))
}

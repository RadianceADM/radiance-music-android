package io.github.radianceadm.music.update

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

/**
 * Updates over the air (owner, 2026-10-31: "OTA"): the newest release on GitHub,
 * looked up at start (at most every 6 hours) and from About; downloaded into the
 * app's own cache, checked to be this app, signed with the same key and newer,
 * then installed by Android (which asks you to confirm, unless it updated this
 * app before).
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/RadianceADM/radiance-music-android/releases/latest"
    private const val PREFS = "updates"
    private const val EVERY_MS = 6 * 60 * 60 * 1000L
    private const val TAG = "RadianceUpdates"
    /** The count of downloads (owner, 2026-10-31: analytics of downloads): one more update, the version only. */
    private const val COUNT = "https://admin.radianceph.com/api/music/count"

    data class Release(val version: String, val notes: String, val apk: String, val size: Long, val page: String)

    sealed interface State {
        /** Not looked yet. */
        data object Unknown : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class Available(val release: Release, val note: String? = null) : State
        data class Downloading(val release: Release, val done: Float) : State
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
        remembered(app)?.let { if (newer(it.version, current(app))) _state.value = State.Available(it) }
        if (System.currentTimeMillis() - prefs.getLong("checked", 0) < EVERY_MS) return
        app.scope.launch {
            delay(8_000) // after the start, not during it
            check(app)
        }
    }

    /** Looks for a newer release now. */
    fun check(context: Context) {
        val app = context.applicationContext as App
        if (job?.isActive == true) return
        job = app.scope.launch {
            val before = _state.value
            _state.value = State.Checking
            val found = runCatching { latest() }
            found.onSuccess { r ->
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("checked", System.currentTimeMillis())
                    .putString("release", r.toJson().toString())
                    .apply()
                _state.value = if (newer(r.version, current(app))) State.Available(r) else State.UpToDate(current(app))
            }.onFailure { e ->
                Log.w(TAG, "couldn't look for updates", e)
                _state.value = if (before is State.Available) before else State.Failed("Couldn't look for updates: ${e.message ?: "no connection"}", null)
            }
        }
    }

    /** Downloads [r] (picking up where it stopped), checks it, and hands it to Android. */
    fun update(context: Context, r: Release) {
        val app = context.applicationContext as App
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = State.Allow(r)
            return
        }
        if (job?.isActive == true) return
        job = app.scope.launch {
            runCatching {
                val file = download(app, r)
                if (!isActive) return@launch
                verify(app, file)
                counted(r)
                _state.value = State.Installing(r)
                install(app, file)
            }.onFailure { e ->
                Log.w(TAG, "couldn't update", e)
                _state.value = State.Failed("Couldn't update: ${e.message ?: "the download stopped"}", r)
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

    /** Back from that page: go on if it's allowed now. */
    fun resumed(context: Context) {
        val s = _state.value as? State.Allow ?: return
        if (context.packageManager.canRequestPackageInstalls()) update(context, s.release)
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
        )
    }

    private suspend fun download(app: App, r: Release): File {
        val dir = File(app.cacheDir, "update").apply { mkdirs() }
        val done = File(dir, "RadianceMusic-${r.version}.apk")
        if (done.length() > 0 && (r.size <= 0 || done.length() == r.size)) return done
        val part = File(dir, done.name + ".part")
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

    /** It is this app, newer, and signed with the same key (else Android refuses it anyway, later). */
    private fun verify(context: Context, file: File) {
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
                PackageInstaller.STATUS_FAILURE_ABORTED ->
                    _state.value = r?.let { State.Available(it, "Not installed: you can update any time.") } ?: State.Unknown
                else -> {
                    val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "install failed: $status $why")
                    _state.value = State.Failed("Android didn't install it${why?.let { ": $it" } ?: ""}", r)
                }
            }
        }
    }

    /** Downloads of versions that are on now (or older) aren't needed any more. */
    private fun clean(context: Context) {
        val now = current(context)
        File(context.cacheDir, "update").listFiles()?.forEach { f ->
            val v = f.name.removePrefix("RadianceMusic-").removeSuffix(".part").removeSuffix(".apk")
            if (!newer(v, now)) f.delete()
        }
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

    private fun Release.toJson() = JSONObject().put("version", version).put("notes", notes).put("apk", apk).put("size", size).put("page", page)
    private fun JSONObject.toRelease() = Release(getString("version"), optString("notes"), getString("apk"), optLong("size"), optString("page"))
}

package io.github.radianceadm.music.server

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import io.github.radianceadm.music.App
import io.github.radianceadm.music.data.Import
import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.Connectivity
import io.github.radianceadm.music.net.Errors
import io.github.radianceadm.music.net.Plain
import io.github.radianceadm.music.split.Splitter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Songs split on this phone go up to the servers you joined, by themselves (owner, 2026-11-09, for
 * every app: "kung sila yung nag split ng kanta sa device nila … dapat ma uupload paren sa server
 * ko"; only servers you were invited to: "pag ka ininvite ko nalang sila sa server"). The song with
 * its cover and lyrics, and its four parts when it has them here (copied from the computer, or from
 * a server); the phone's own two halves aren't a server's split, so the server is asked to split it
 * its own way ("never mag kakaroon ng kanta sa server na hindi na iisplit"). The next phone that
 * plays it gets it from there (ServerFirst).
 *
 * Away from the server (offline, out of the house) they wait here, kept, and go when it answers
 * again: when the phone's back online, when a split's done, and every few minutes while the app
 * runs. On Wi-Fi (or any network that isn't metered) only: a hundred songs aren't sent over
 * mobile data. Nothing's said on the screen: it's the server's work.
 */
object ServerUploads {
    /** A song (its key) still to go to a server (its first address). */
    internal data class Item(val key: String, val server: String) {
        val id get() = "$server|$key"
    }

    private const val FILE = "server-uploads.json"
    /** How often the waiting songs are tried while the app runs (a phone that came home). */
    private const val EVERY_MS = 10 * 60_000L

    private val lock = Mutex()
    private val running = Mutex()
    private var waiting = listOf<Item>()
    /** Songs a server has now (sent, or it had them): not asked about again. */
    private var done = setOf<String>()
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(file(context).readText())
            val w = o.optJSONArray("waiting") ?: JSONArray()
            waiting = (0 until w.length()).mapNotNull { i -> w.optJSONObject(i)?.let { e -> e.optString("key").takeIf { it.isNotEmpty() }?.let { Item(it, e.optString("server")) } } }
            val d = o.optJSONArray("done") ?: JSONArray()
            done = (0 until d.length()).map { d.optString(it) }.toSet()
        }
    }

    private fun save(context: Context) {
        runCatching {
            val o = JSONObject()
                .put("waiting", JSONArray(waiting.map { JSONObject().put("key", it.key).put("server", it.server) }))
                .put("done", JSONArray(done.toList()))
            file(context).writeText(o.toString())
        }.onFailure { Log.w("radiance-uploads", "couldn't keep the songs waiting to go up", it) }
    }

    /** The songs still to go up, and to which servers (for the tests and the logs). */
    internal suspend fun waiting(context: Context): List<Item> = lock.withLock { load(context); waiting }

    /** What's to be added for [keys] on [servers]: the ones not waiting already, not there already. */
    internal fun wanted(keys: List<String>, servers: List<Server>, waiting: List<Item>, done: Set<String>): List<Item> {
        val have = waiting.map { it.id }.toSet() + done
        return keys.flatMap { k -> servers.filter { it.can.add }.map { Item(k, it.first) } }.filter { it.id !in have }.distinctBy { it.id }
    }

    /** The song's split here (or its four parts were copied here): it's one to send. */
    internal fun splitHere(context: Context, t: Track): Boolean =
        t.file != null && Import.parts(context, t.key).keys.let { it.containsAll(Splitter.PARTS) || it.containsAll(Splitter.FOUR) }

    /** [t] was split here: it goes up to every server you joined (that lets you add songs), now or when it can. */
    fun queue(app: App, t: Track) {
        if (t.source == Source.SERVER) return // a server's own song: it's there
        app.scope.launch {
            lock.withLock {
                load(app)
                val add = wanted(listOf(t.key), Servers.list.value, waiting, done)
                if (add.isEmpty()) return@withLock
                waiting = waiting + add
                save(app)
            }
            send(app)
        }
    }

    /** Watches from the app's start: the songs split here before go up too, then each time they can. */
    fun start(app: App) {
        app.scope.launch {
            delay(20_000) // the app opens first
            // your liked songs split here (before this, or before you joined): to every server you joined
            combine(app.library.liked, Servers.list) { liked, servers -> liked to servers }.collectLatest { (liked, servers) ->
                if (servers.isEmpty()) return@collectLatest
                delay(30_000) // Liked Songs settles first (a hundred songs coming back change it a hundred times)
                // only the songs not sent (or waiting) for some server are looked at
                val unsent = lock.withLock { load(app); liked.filter { it.source != Source.SERVER && wanted(listOf(it.key), servers, waiting, done).isNotEmpty() } }
                val keys = unsent.filter { splitHere(app, it) }.map { it.key }
                lock.withLock {
                    val add = wanted(keys, servers, waiting, done)
                    if (add.isNotEmpty()) {
                        waiting = waiting + add
                        save(app)
                    }
                }
                app.scope.launch { send(app) } // on its own: a change to Liked Songs doesn't stop a song half sent
            }
        }
        app.scope.launch { Connectivity.online.drop(1).collect { if (it) send(app) } }
        app.scope.launch {
            while (true) {
                delay(EVERY_MS)
                send(app)
            }
        }
    }

    private fun unmetered(context: Context): Boolean =
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false }.getOrDefault(false)

    /** What became of one song: there now, never (drop it), or not now (try later). */
    internal enum class Went { THERE, NEVER, LATER }

    /** How [e] (a server's answer, or none) leaves the song: never for "no" to this song, later for anything that may pass. */
    internal fun after(e: Throwable): Went = when {
        Errors.offline(e) -> Went.LATER
        e is Refused && (e.code == 408 || e.code == 429 || e.code >= 500) -> Went.LATER
        e is Refused -> Went.NEVER // it said no (too big, not allowed for you, this phone removed): not asked again
        e is Plain -> Went.NEVER // this phone can't send it (not saved here, a kind of file servers don't take)
        else -> Went.LATER // not answering (away from home): later
    }

    /** The next song to send: the first waiting whose server answered this time round. */
    internal fun next(waiting: List<Item>, quiet: Set<String>): Item? = waiting.firstOrNull { it.server !in quiet }

    /** Sends the waiting songs, one at a time; a server not answering keeps its songs for later, the others' go on. */
    suspend fun send(app: App) {
        if (!running.tryLock()) return
        try {
            val quiet = mutableSetOf<String>()
            while (true) {
                if (!Connectivity.online.value || !unmetered(app)) return
                val item = lock.withLock { load(app); next(waiting, quiet) } ?: return
                val s = Servers.byFirst(item.server)
                val t = app.library.liked.value.firstOrNull { it.key == item.key }
                val went = when {
                    s == null || t == null || !s.can.add -> Went.NEVER // left that server, unliked it, or not yours to add to
                    !splitHere(app, t) -> Went.NEVER
                    else -> runCatching { one(app, s, t); Went.THERE }.getOrElse { e ->
                        Log.w("radiance-uploads", "“${t.title}” didn't go to ${s.name} now", e)
                        after(e)
                    }
                }
                lock.withLock {
                    when (went) {
                        Went.LATER -> quiet.add(item.server)
                        Went.THERE -> { waiting = waiting.filterNot { it.id == item.id }; done = done + item.id; save(app) }
                        Went.NEVER -> { waiting = waiting.filterNot { it.id == item.id }; save(app) }
                    }
                }
            }
        } finally {
            running.unlock()
        }
    }

    /**
     * [t] to [s]: there already and split, nothing goes; there without its parts, its four
     * go (when it has them here) or it's asked to split it; not there, the song goes (with its
     * cover, lyrics, and four parts if it has them), and it's asked to split it.
     */
    internal fun one(app: App, s: Server, t: Track) {
        val copy = ServerFirst.match(Servers.page(s, q = ServerFirst.query(t)).songs, t)
        val there = when {
            copy == null -> ServerSongs.upload(app, s, t).let { got -> got.parts?.song ?: got.song.song }
            copy.split -> return
            ServerSongs.phoneParts(app, t) != null -> ServerSongs.sendParts(app, s, t, copy)?.song ?: copy
            else -> copy
        }
        Log.i("radiance-uploads", "“${t.title}” is on ${s.name}")
        // the phone's two halves aren't a server's split: it splits it its own way
        if (there != null && !there.split && s.can.split) runCatching { s.rms.split(there.id) }.onFailure { Log.w("radiance-uploads", "${s.name} didn't take “${t.title}” to split", it) }
    }
}

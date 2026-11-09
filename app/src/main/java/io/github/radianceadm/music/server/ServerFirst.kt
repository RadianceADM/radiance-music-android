package io.github.radianceadm.music.server

import android.util.Log
import io.github.radianceadm.music.App
import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.model.fold
import io.github.radianceadm.music.model.plain
import io.github.radianceadm.music.net.Http
import io.github.radianceadm.music.split.Splitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Server first (owner, 2026-11-09, for every app: "kung naka connect sa server automatic na dun sa
 * server ipupull yung kanta kung existing na at pre splitted na … pero kung hindi sa youtube agad"):
 * a song a server you joined has already comes from that server, its file, its parts and its lyrics,
 * not from YouTube, and it isn't split here. The server is asked quickly; it doesn't have the song,
 * or doesn't answer in time: YouTube at once. Every song a server has gets split there ("never mag
 * kakaroon ng kanta sa server na hindi na iisplit"): one it hasn't split yet is asked to, and its
 * parts come here when they're done.
 */
object ServerFirst {
    /** How long a server has to say whether it has a song, before YouTube is asked instead. */
    private const val QUICK_S = 3L
    /** What a server said about a song is believed this long (a song added there meanwhile is found after; one gone, missed). */
    internal const val NOT_THERE_MS = 10 * 60_000L
    /** A server that didn't answer isn't asked again for this long (away from home: YouTube, no wait). */
    internal const val QUIET_MS = 60_000L
    /** How long the server's split is waited for, before this phone splits the song itself. */
    private const val SPLIT_WAIT_MS = 45 * 60_000L
    private const val SPLIT_LOOK_MS = 20_000L

    private val quick by lazy { Http.client.newBuilder().callTimeout(QUICK_S, TimeUnit.SECONDS).build() }

    /** What a server said about a song (by its key): its copy there (the server's first address and the song), or none. */
    internal data class Asked(val first: String?, val song: ServerSong?, val atMs: Long)

    private val asked = ConcurrentHashMap<String, Asked>()
    /** Servers that didn't answer lately (by their base address): when. */
    private val quiet = ConcurrentHashMap<String, Long>()
    /** The YouTube songs queued to play, by their YouTube id (the player's "yt:ID" carries nothing else). */
    private val queued = ConcurrentHashMap<String, Track>()
    /** Songs whose parts are coming from their server (it's splitting them): not split here meanwhile. */
    private val coming: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Songs their server couldn't split since the app opened: split here (not asked there again till then). */
    private val couldnt: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** [t] is in the player's queue: its stream can be its server's copy. */
    fun queued(t: Track) {
        if (t.source == Source.YOUTUBE) queued[t.id] = t
    }

    /** The words a server is searched with: the song's title (its own search: every word in the artist, title or file name). */
    internal fun query(t: Track): String = t.title.replace(Regex("[()\\[\\]]"), " ").trim()

    /** The letters and digits of [text], brackets and all: "Dilaw (Acoustic)" isn't "Dilaw" (accents, case and spaces aside). */
    internal fun whole(text: String): String = fold(text).lowercase().filter { it.isLetterOrDigit() }

    /**
     * [t]'s copy among [songs]: the same artist and the same title, word for word (accents,
     * case and spaces aside), so another version ("(Acoustic)", "(Live)") never plays for it.
     */
    internal fun match(songs: List<ServerSong>, t: Track): ServerSong? =
        songs.firstOrNull { whole(it.title) == whole(t.title) && plain(it.artist) == plain(t.artist) }

    /** What's known about a song still holds (it was asked lately). */
    internal fun fresh(a: Asked?, now: Long): Boolean = a != null && now - a.atMs < NOT_THERE_MS

    /** The servers to ask: joined, not removed, not silent a moment ago. */
    internal fun askable(list: List<Server>, health: Map<String, Health>, quiet: Map<String, Long>, now: Long): List<Server> =
        list.filter { health[it.base] !is Health.Removed && (quiet[it.base]?.let { at -> now - at >= QUIET_MS } ?: true) }

    /**
     * [t]'s copy on a server you joined (the same artist and title), if one has it: the
     * first one joined first. Not a server's song, no server joined, or none answers in
     * time: null at once. Waits for the network (a few seconds at most a server).
     */
    fun copy(t: Track): Pair<Server, ServerSong>? {
        if (t.source == Source.SERVER) return null
        val joined = Servers.list.value
        if (joined.isEmpty()) return null
        val now = System.currentTimeMillis()
        asked[t.key]?.takeIf { fresh(it, now) }?.let { a ->
            if (a.song == null) return null
            // its server's copy, unless that server's gone quiet since (away from home): asked again below
            Servers.byFirst(a.first!!)?.takeIf { askable(listOf(it), Servers.health.value, quiet, now).isNotEmpty() }?.let { return it to a.song }
        }
        for (s in askable(joined, Servers.health.value, quiet, now)) {
            val page = try {
                Rms(s.base, s.token, quick, s.first).page(q = query(t))
            } catch (e: Exception) {
                quiet[s.base] = System.currentTimeMillis()
                Servers.heard(s, e)
                continue
            }
            quiet.remove(s.base)
            Servers.heard(s, null)
            Servers.remember(s, page.songs, whole = false)
            match(page.songs, t)?.let { song ->
                asked[t.key] = Asked(s.first, song, now)
                return s to song
            }
        }
        asked[t.key] = Asked(null, null, now)
        return null
    }

    /** Its copy there turned out not to be (gone, or it wouldn't send it): asked again next time. */
    fun forget(t: Track) {
        asked.remove(t.key)
    }

    /** The YouTube songs playing from a server's copy now (their YouTube ids): which server's. */
    private val served = ConcurrentHashMap<String, String>()

    /** A YouTube song the player is starting ("yt:ID"): its server's copy's address (with this phone's key), or null (YouTube). */
    fun stream(youTubeId: String): String? {
        val t = queued[youTubeId] ?: return null
        val (s, song) = copy(t) ?: run { served.remove(youTubeId); return null }
        served[youTubeId] = s.base
        return Servers.sign(song.url)
    }

    /**
     * The server's copy of [youTubeId] wouldn't play (it stopped answering: out of the house,
     * say): that server's left alone a moment, and the song plays from YouTube. True: it was a
     * server's copy, so try it again (from YouTube now).
     */
    fun wouldntPlay(youTubeId: String): Boolean {
        val base = served.remove(youTubeId) ?: return false
        quiet[base] = System.currentTimeMillis()
        queued[youTubeId]?.let { asked.remove(it.key) }
        return true
    }

    /** [key]'s parts are coming from its server (it's splitting the song): not split here meanwhile. */
    fun coming(key: String): Boolean = key in coming

    /**
     * [t]'s copy on [s] isn't split yet: asks [s] to split it, and brings its parts here when
     * they're done (the Mixer plays the whole song meanwhile). [s] won't split for you, or
     * can't: split here, as before.
     */
    fun splitThere(app: App, s: Server, song: ServerSong, t: Track) {
        if (song.split || !s.can.split || t.key in couldnt) return
        if (!coming.add(t.key)) return
        app.scope.launch(Dispatchers.IO) {
            var got = false
            try {
                s.rms.split(song.id)
                val until = System.currentTimeMillis() + SPLIT_WAIT_MS
                var gone = 0
                while (System.currentTimeMillis() < until) {
                    delay(SPLIT_LOOK_MS)
                    val now = runCatching { Servers.fresh(s, song.id) }.getOrNull() ?: continue
                    if (now.split) {
                        ServerSongs.keepParts(app, now, t)
                        asked[t.key] = Asked(s.first, now, System.currentTimeMillis())
                        withContext(Dispatchers.Main) { app.mixer.recheck() }
                        got = true
                        break
                    }
                    // not split, and not in its queue any more (it couldn't): stop waiting after a second look
                    val open = runCatching { s.rms.queue().of(song.id) != null }.getOrDefault(true)
                    if (!open && ++gone >= 2) break
                }
            } catch (e: Exception) {
                Log.w("radiance-server-first", "${s.name} didn't split “${t.title}”", e)
                Servers.heard(s, e)
            } finally {
                coming.remove(t.key)
            }
            // the server couldn't: this phone splits it, as before
            if (!got) {
                couldnt += t.key
                if (!Splitter.isSplit(app, t.key) && app.library.isLiked(t.key)) withContext(Dispatchers.Main) { app.splits.add(t) }
            }
        }
    }

    /**
     * A liked song still to be split here ([SplitLiked], karaoke's [Ahead]): its server's
     * copy first. True: it's being taken care of there (its parts saved here, or coming), so
     * it isn't split here. Waits for the network.
     */
    fun takeOver(app: App, t: Track): Boolean {
        val (s, song) = copy(t) ?: return false
        if (song.split) {
            return runCatching { ServerSongs.keepParts(app, song, t) }
                .onFailure { Log.w("radiance-server-first", "couldn't bring “${t.title}”'s parts from ${s.name}", it); forget(t) }
                .isSuccess && Splitter.isSplit(app, t.key)
        }
        if (!s.can.split || t.key in couldnt) return false
        splitThere(app, s, song, t)
        return true
    }

    /** For the tests: everything forgotten. */
    internal fun clear() {
        asked.clear(); quiet.clear(); queued.clear(); coming.clear(); couldnt.clear(); served.clear()
    }
}

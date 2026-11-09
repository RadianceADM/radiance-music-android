package io.github.radianceadm.music.split

import io.github.radianceadm.music.App
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.play.Mixer
import io.github.radianceadm.music.server.ServerFirst
import io.github.radianceadm.music.server.ServerSongs
import io.github.radianceadm.music.server.Servers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Karaoke (or Vocals only) on: the song playing and the next one get their
 * parts, so each starts that way (owner, 2026-11-05, android#20: "stays on,
 * splits ahead"). Split here, one at a time (the song playing first; a song
 * not saved yet is saved first, as Split this song does), or, for a server's
 * song it split already, its parts fetched from there. A server that splits
 * for you is asked from the Mixer (it's that server's work), not from here.
 * The whole song: nothing new is split.
 */
class Ahead(private val app: App) {
    /** A server's songs whose parts are on their way here. */
    private val fetching: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Which songs need their parts, the song playing first: none for the whole song. */
    internal fun wanted(voice: Mixer.Voice, playing: Track?, next: Track?, ready: (Track) -> Boolean): List<Track> =
        if (voice == Mixer.Voice.Song) emptyList()
        else listOfNotNull(playing, next).distinctBy { it.key }.filterNot(ready)

    /** Watches what's playing and karaoke from now on (the app's start). */
    fun start() {
        app.scope.launch {
            combine(app.mixer.voice, app.player.current, app.player.around, app.splits.done) { v, playing, around, _ -> Triple(v, playing, around.second) }
                .collect { (v, playing, next) -> for (t in wanted(v, playing, next, ::ready)) get(t) }
        }
    }

    /** Its parts are here, or on their way (split here, or fetched). */
    private fun ready(t: Track): Boolean =
        app.mixer.hasParts(t) || app.splits.waiting(t.key) || app.splits.now.value?.track?.key == t.key || t.key in fetching || ServerFirst.coming(t.key)

    private suspend fun get(t: Track) {
        val server = Servers.of(t)
        when {
            server == null -> withContext(Dispatchers.Main) { splitHere(t) }
            ServerSongs.splitThere(t) -> if (fetching.add(t.key)) app.scope.launch {
                try {
                    runCatching { ServerSongs.fetchParts(app, t) }.onSuccess { withContext(Dispatchers.Main) { app.mixer.recheck() } }
                } finally {
                    fetching.remove(t.key)
                }
            }
            !server.can.split -> withContext(Dispatchers.Main) { splitHere(t) } // its owner turned off splitting there for you: here, as the Mixer does
            else -> Unit // the Mixer asks that server
        }
    }

    private fun splitHere(t: Track) {
        if (!app.library.isLiked(t.key)) app.like(t)
        app.splits.add(t, soon = true) // before the liked songs split by themselves (SplitLiked)
    }
}

/**
 * Like it, save it, split it: every liked song is split by itself once it's saved, as its
 * cover, lyrics and video are (owner, 2026-11-06: "dapat masama den yung split files moving
 * forwards"), so karaoke is ready whenever you want it. After the app was installed again
 * (Android deletes its splits with it), the songs that come back are split again the same
 * way. One at a time, behind the song playing with karaoke on (Ahead); the phone's heat is
 * watched as for any split (Guard). A server's songs are left to that server.
 */
class SplitLiked(private val app: App) {
    /** The liked songs to split now: saved here, not split or waiting, not a server's, not failed since the app opened. */
    internal fun wanted(liked: List<Track>, ready: (Track) -> Boolean, server: (Track) -> Boolean, failed: (String) -> Boolean): List<Track> =
        liked.filter { it.file != null && !server(it) && !failed(it.key) && !ready(it) }

    /** Watches Liked Songs from now on (the app's start). */
    fun start() {
        app.scope.launch {
            combine(app.library.liked, app.splits.done) { liked, _ -> liked }.collectLatest { liked ->
                delay(1500) // Liked Songs settles first (a hundred songs coming back change it a hundred times)
                // (a song a server you joined is splitting: its parts come from there, ServerFirst)
                val ready = { t: Track -> app.mixer.hasParts(t) || app.splits.waiting(t.key) || app.splits.now.value?.track?.key == t.key || ServerFirst.coming(t.key) }
                val todo = wanted(liked, ready, { Servers.of(it) != null }, app.splits::failed)
                if (todo.isNotEmpty()) withContext(Dispatchers.Main) { todo.forEach { app.splits.add(it) } }
            }
        }
    }
}

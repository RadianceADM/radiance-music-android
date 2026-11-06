package io.github.radianceadm.music.play

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.radianceadm.music.App
import io.github.radianceadm.music.data.Saver
import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.server.Servers
import io.github.radianceadm.music.source.YouTube
import io.github.radianceadm.music.ui.Notice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A song as the player holds it (the song itself rides along in its extras). */
fun Track.mediaItem(context: Context): MediaItem {
    val cover = Saver.coverFile(context, this)
    // a server's cover: this phone's key in its address (the lock screen's picture can't send one otherwise)
    val art = if (cover.exists()) Uri.fromFile(cover) else art.takeIf { it.isNotEmpty() }?.let { Uri.parse(Servers.sign(it)) }
    val meta = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album.ifEmpty { null })
        .setArtworkUri(art)
        .setExtras(Bundle().apply { putString("track", toJson().toString()) })
        .build()
    return MediaItem.Builder()
        .setMediaId(key)
        .setUri(playUri)
        .setMediaMetadata(meta)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(playUri)).build())
        .build()
}

/**
 * A song's music video as the player holds it (the Video button on its cover): the same
 * song (its key and details, so lyrics, the heart and the queue stay as they are), played
 * from [address]: its kept file, or "ytv:ID" for YouTube's (PlaybackService).
 */
fun Track.videoItem(context: Context, address: String): MediaItem {
    val song = mediaItem(context)
    val meta = song.mediaMetadata.buildUpon()
        .setExtras(Bundle(song.mediaMetadata.extras ?: Bundle()).apply { putBoolean("video", true) })
        .build()
    return song.buildUpon()
        .setUri(address)
        .setMediaMetadata(meta)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(address)).build())
        .build()
}

/** It's a song's music video (see videoItem). */
fun MediaItem.isVideo(): Boolean = mediaMetadata.extras?.getBoolean("video") == true

fun trackOf(item: MediaItem): Track? = item.mediaMetadata.extras?.getString("track")?.let { runCatching { Track.fromJson(JSONObject(it)) }.getOrNull() }

/**
 * The app's side of the player: commands to the background service, and what
 * the screens show (the song, playing or not, the queue).
 */
class PlayerHub(private val context: Context) {
    private var controller: MediaController? = null

    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue
    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle
    private val _repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeat: StateFlow<Int> = _repeat
    /** The songs either side of this one, as the player goes (shuffled, repeating): the cover you swipe to. */
    private val _around = MutableStateFlow<Pair<Track?, Track?>>(null to null)
    val around: StateFlow<Pair<Track?, Track?>> = _around
    /** Play pressed and the song not here yet (finding it, loading it): the Play button spins. */
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering
    /** Why the music stopped (a song that wouldn't play), until one plays again; null: all's well. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem
    private val _onVideo = MutableStateFlow(false)
    /** The song playing plays as its music video now (the Video button on its cover). */
    val onVideo: StateFlow<Boolean> = _onVideo
    /** Songs that wouldn't play, in a row (the player skips them, up to a point: PlaybackService). */
    val streak = Streak()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()

        // a seek, or the same song again (repeat one, or twice in a row): the Mixer's sound goes there too
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
            val same = oldPosition.mediaItemIndex == newPosition.mediaItemIndex ||
                (oldPosition.mediaItem?.let { trackOf(it)?.key } ?: "?") == newPosition.mediaItem?.let { trackOf(it)?.key }
            if (same) App.instance.mixer.seeked(newPosition.positionMs)
        }
    }

    fun connect(then: () -> Unit = {}) {
        if (controller != null) return then()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(listener)
            restore()
            sync()
            then()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Where you left off, if nothing plays yet (paused, at the same spot). */
    private fun restore() {
        val c = controller ?: return
        if (c.mediaItemCount > 0) return
        val (queue, index, position) = App.instance.library.session() ?: return
        c.setMediaItems(queue.map { it.mediaItem(context) }, index.coerceIn(0, queue.size - 1), position)
        c.prepare()
    }

    private fun sync() {
        val c = controller ?: return
        _queue.value = (0 until c.mediaItemCount).mapNotNull { trackOf(c.getMediaItemAt(it)) }
        _index.value = c.currentMediaItemIndex
        _current.value = c.currentMediaItem?.let { trackOf(it) }
        _onVideo.value = c.currentMediaItem?.isVideo() == true
        val was = _playing.value
        _playing.value = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        _buffering.value = c.playWhenReady && c.playbackState == Player.STATE_BUFFERING
        if (c.isPlaying) _problem.value = null
        // a music video plays its own sound: the parts stand aside
        App.instance.mixer.songChanged(if (_onVideo.value) null else _current.value)
        if (was != _playing.value) App.instance.mixer.playingChanged(_playing.value)
        _shuffle.value = c.shuffleModeEnabled
        _repeat.value = c.repeatMode
        val at = { i: Int -> if (i == C.INDEX_UNSET || i == c.currentMediaItemIndex) null else trackOf(c.getMediaItemAt(i)) }
        _around.value = at(c.previousMediaItemIndex) to at(c.nextMediaItemIndex)
    }

    val position: Long get() = controller?.currentPosition ?: 0
    val duration: Long get() = controller?.duration?.takeIf { it > 0 } ?: ((_current.value?.seconds ?: 0) * 1000L)

    /** What's in the player now, to come back to (a listening party plays in its place for a while). */
    data class Mine(val queue: List<Track>, val index: Int, val positionMs: Long, val playing: Boolean)

    fun mine(): Mine? {
        val c = controller ?: return null
        return Mine(_queue.value, c.currentMediaItemIndex, c.currentPosition, _playing.value).takeIf { it.queue.isNotEmpty() }
    }

    /** Back to [m] (the music before the party), playing again if it was. Nothing to go back to: the player stops. */
    fun resume(m: Mine?) = connect {
        controller?.run {
            setPlaybackSpeed(1f)
            if (m == null) {
                stop()
                clearMediaItems()
                return@run
            }
            val lib = App.instance.library
            setMediaItems(m.queue.map { lib.best(it).mediaItem(context) }, m.index.coerceIn(0, m.queue.size - 1), m.positionMs)
            prepare()
            if (m.playing) play()
        }
    }

    /** Plays [list] from [start], as the songs are now (saved copies when liked). In a party: you leave it first. */
    fun play(list: List<Track>, start: Int) = connect {
        val lib = App.instance.library
        if (App.instance.party.joined) App.instance.party.leave(resume = false)
        picked()
        controller?.run {
            setMediaItems(list.map { lib.best(it).mediaItem(context) }, start.coerceIn(0, list.size - 1), 0)
            prepare()
            play()
        }
    }

    /**
     * [t] now, then songs like it (YouTube Music's "Up next" for it), added once they come.
     * Owner, 2026-11-06: a song from search played the other search results next
     * ("bakit yung nag auto queue hindi related sa previous songs").
     */
    fun playWithRelated(t: Track) {
        play(listOf(t), 0)
        App.instance.scope.launch {
            val seed = if (t.source == Source.YOUTUBE) t.id else runCatching { YouTube.search("${t.artist} ${t.title}").firstOrNull()?.id }.getOrNull()
            val more = seed?.let { runCatching { YouTube.related(it) }.getOrNull() }.orEmpty().filter { it.key != t.key }.take(RELATED)
            if (more.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                connect {
                    controller?.run {
                        // only while the pick still plays (moved on: dropped); songs added meanwhile stay, these after them (owner, radiance-music#47)
                        if (currentMediaItem?.mediaId == t.key) addMediaItems(more.map { App.instance.library.best(it).mediaItem(context) })
                    }
                }
            }
        }
    }

    fun playNext(t: Track) = connect {
        controller?.run {
            if (mediaItemCount == 0) play(listOf(t), 0) else addMediaItem(currentMediaItemIndex + 1, App.instance.library.best(t).mediaItem(context))
        }
    }

    fun enqueue(t: Track) = connect {
        controller?.run { if (mediaItemCount == 0) play(listOf(t), 0) else addMediaItem(App.instance.library.best(t).mediaItem(context)) }
    }

    /** The song's own sound (the Mixer turns it down while it plays the parts). */
    fun setVolume(v: Float) {
        controller?.volume = v.coerceIn(0f, 1f)
    }

    fun toggle() = controller?.run {
        // in a party: listening here or not (the party goes on)
        if (App.instance.party.joined) return@run App.instance.party.listenHere(!isPlaying)
        if (isPlaying) pause()
        else {
            // stopped by a song that wouldn't play: Play tries it again
            if (playerError != null) { streak.retry(); _problem.value = null }
            if (playbackState == Player.STATE_IDLE) prepare()
            play()
        }
    }
    // in a party the host moves it along (the party's own screen): Next skips there if you may, Previous and seeking stay with it
    fun next() = controller?.run { if (App.instance.party.joined) return@run App.instance.party.skip(); picked(); seekToNextMediaItem(); wake() }
    fun previous() = controller?.run { if (App.instance.party.joined) return@run; picked(); if (currentPosition > 4000 || !hasPreviousMediaItem()) seekTo(0) else seekToPreviousMediaItem(); wake() }
    /** The song before, whatever the place in this one (a swipe on the cover). */
    fun previousSong() = controller?.run { if (App.instance.party.joined) return@run; picked(); if (hasPreviousMediaItem()) seekToPreviousMediaItem() else seekTo(0); wake() }
    fun seek(ms: Long) = controller?.run { if (App.instance.party.joined) App.instance.party.seek(ms) else seekTo(ms) }
    fun playAt(i: Int) = controller?.run { picked(); seekTo(i, 0); wake(); play() }

    /** You picked a song: whatever wouldn't play before is forgotten. */
    private fun picked() {
        streak.picked()
        _problem.value = null
    }

    // stopped by a song that wouldn't play: the one moved to gets ready (and plays, if it was playing)
    private fun MediaController.wake() {
        if (playbackState == Player.STATE_IDLE && mediaItemCount > 0) prepare()
    }

    /** Tries the song that wouldn't play again ([key]: that one, wherever it is in the queue now; else this one). */
    fun retry(key: String? = null) = connect {
        controller?.run {
            val at = key?.let { k -> (0 until mediaItemCount).firstOrNull { getMediaItemAt(it).mediaId == k } }
            streak.retry()
            _problem.value = null
            if (at != null && at != currentMediaItemIndex) seekTo(at, 0)
            if (playbackState == Player.STATE_IDLE) prepare()
            play()
        }
    }

    /**
     * A song wouldn't play ([why]: in plain words; [then]: what the player did
     * about it): said, with Retry. Stopped, the reason stays on the player too.
     */
    fun couldnt(song: Track?, why: String, then: Streak.Then) {
        val name = song?.let { "“${it.title}”" } ?: "The song"
        val again = Notice.retry { retry(song?.key) }
        when (then) {
            Streak.Then.NEXT -> App.instance.tell(Notice("Skipped $name. $why", again))
            Streak.Then.STOP -> {
                _problem.value = "Couldn't play. $why"
                App.instance.tell(Notice("$name couldn't play. $why", again))
            }
            Streak.Then.TOO_MANY -> {
                _problem.value = "Couldn't play. $why"
                App.instance.tell(Notice("${streak.inARow} songs in a row couldn't play, so the music stopped. $why", again))
            }
        }
    }
    fun remove(i: Int) = controller?.removeMediaItem(i)
    fun move(from: Int, to: Int) = controller?.moveMediaItem(from, to)
    fun toggleShuffle() = controller?.run { shuffleModeEnabled = !shuffleModeEnabled }
    fun cycleRepeat() = controller?.run {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /**
     * The song playing as its music video ([on], from [address]) or as the song again: from the
     * same spot and still playing, as the computer's Video button (owner, 2026-11-06: "bakit
     * hindi kasing smooth at nag papause pag mag siswitch between video and music").
     */
    fun switchVideo(on: Boolean, address: String? = null) = controller?.run {
        if (App.instance.party.joined) return@run
        val item = currentMediaItem ?: return@run
        if (item.isVideo() == on) return@run
        val t = trackOf(item) ?: return@run
        val other = if (on) t.videoItem(context, address ?: return@run) else App.instance.library.best(t).mediaItem(context)
        val i = currentMediaItemIndex
        val at = currentPosition
        replaceMediaItem(i, other)
        seekTo(i, at)
        if (playbackState == Player.STATE_IDLE) prepare()
    }

    /** A liked song saved: the queue's copy plays the file from now on. */
    fun refresh(t: Track) {
        val c = controller ?: return
        for (i in 0 until c.mediaItemCount) {
            if (c.getMediaItemAt(i).mediaId == t.key && i != c.currentMediaItemIndex) c.replaceMediaItem(i, t.mediaItem(context))
        }
    }

    fun saveSession() {
        val c = controller ?: return
        if (App.instance.party.joined) return // where you left off is your own music, not the party's
        App.instance.library.saveSession(_queue.value, c.currentMediaItemIndex, c.currentPosition)
    }

    private companion object {
        /** How many songs like it come after a song from search. */
        const val RELATED = 30
    }
}

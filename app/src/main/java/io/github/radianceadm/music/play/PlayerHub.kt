package io.github.radianceadm.music.play

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.radianceadm.music.App
import io.github.radianceadm.music.data.Saver
import io.github.radianceadm.music.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** A song as the player holds it (the song itself rides along in its extras). */
fun Track.mediaItem(context: Context): MediaItem {
    val cover = Saver.coverFile(context, this)
    val art = if (cover.exists()) Uri.fromFile(cover) else art.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
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

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()

        // a seek: the Mixer's parts go there too
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex == newPosition.mediaItemIndex) App.instance.mixer.seeked(newPosition.positionMs)
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
        val was = _playing.value
        _playing.value = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        App.instance.mixer.songChanged(_current.value)
        if (was != _playing.value) App.instance.mixer.playingChanged(_playing.value)
        _shuffle.value = c.shuffleModeEnabled
        _repeat.value = c.repeatMode
    }

    val position: Long get() = controller?.currentPosition ?: 0
    val duration: Long get() = controller?.duration?.takeIf { it > 0 } ?: ((_current.value?.seconds ?: 0) * 1000L)

    /** Plays [list] from [start], as the songs are now (saved copies when liked). */
    fun play(list: List<Track>, start: Int) = connect {
        val lib = App.instance.library
        controller?.run {
            setMediaItems(list.map { lib.best(it).mediaItem(context) }, start.coerceIn(0, list.size - 1), 0)
            prepare()
            play()
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

    fun toggle() = controller?.run { if (isPlaying) pause() else { if (playbackState == Player.STATE_IDLE) prepare(); play() } }
    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.run { if (currentPosition > 4000 || !hasPreviousMediaItem()) seekTo(0) else seekToPreviousMediaItem() }
    fun seek(ms: Long) = controller?.seekTo(ms)
    fun playAt(i: Int) = controller?.run { seekTo(i, 0); play() }
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

    /** A liked song saved: the queue's copy plays the file from now on. */
    fun refresh(t: Track) {
        val c = controller ?: return
        for (i in 0 until c.mediaItemCount) {
            if (c.getMediaItemAt(i).mediaId == t.key && i != c.currentMediaItemIndex) c.replaceMediaItem(i, t.mediaItem(context))
        }
    }

    fun saveSession() {
        val c = controller ?: return
        App.instance.library.saveSession(_queue.value, c.currentMediaItemIndex, c.currentPosition)
    }
}

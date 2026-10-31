package io.github.radianceadm.music.play

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.radianceadm.music.App
import io.github.radianceadm.music.net.Errors
import io.github.radianceadm.music.net.Http
import io.github.radianceadm.music.server.Servers
import io.github.radianceadm.music.source.YouTube
import io.github.radianceadm.music.ui.MainActivity

/**
 * Plays in the background: the notification, the lock screen, headphones'
 * and Bluetooth buttons all come from its media session. A YouTube song is
 * queued as "yt:ID" and its stream is found only when it starts (YouTube's
 * addresses expire, so a long queue never goes stale). A song on a Radiance
 * Music Server you joined gets this phone's key in its address (`?t=`) only
 * as it starts (it's never kept with the song).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val http = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.USER_AGENT)
        val upstream = DefaultDataSource.Factory(this, http)
        val resolving = ResolvingDataSource.Factory(upstream) { spec ->
            if (spec.uri.scheme == "yt") spec.withUri(Uri.parse(YouTube.audio(spec.uri.schemeSpecificPart).url))
            else Servers.sign(spec.uri.toString()).let { signed -> if (signed == spec.uri.toString()) spec else spec.withUri(Uri.parse(signed)) }
        }
        // every song it plays goes through the effects (Low cut, High cut, Reverb)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf(EffectsProcessor()))
                    .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true) // headphones out: it pauses
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo = player
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.let { trackOf(it) }?.let { App.instance.library.played(it) }
            }

            // a song that won't play: why, in plain words, and on to the next one (not past three in a row)
            override fun onPlayerError(error: PlaybackException) {
                Log.w("radiance-play", "a song wouldn't play", error)
                val hub = App.instance.player
                val song = player.currentMediaItem?.let { trackOf(it) }
                val then = hub.streak.failed(player.hasNextMediaItem())
                if (then == Streak.Then.NEXT) {
                    player.seekToNextMediaItem()
                    player.prepare() // still meant to play: it plays
                }
                // a song on a server you joined: why that server didn't send it
                hub.couldnt(song, song?.let { Servers.of(it) }?.let { Errors.server(error, it.name) } ?: Errors.say(error), then)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) App.instance.player.streak.played()
            }
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // songs added from the app come with their address in requestMetadata
                override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(mediaItems.map { it.buildUpon().setUri(it.requestMetadata.mediaUri ?: it.localConfiguration?.uri).build() }.toMutableList())
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    companion object {
        /**
         * The player itself while the service is up (the app's own process):
         * the listening party keeps it in step to the millisecond, where a
         * controller's place is only an estimate. Main thread only.
         */
        @Volatile var exo: ExoPlayer? = null
            private set
    }

    /** Swiped away from recent apps: it plays on if it's playing, else it goes. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        exo = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

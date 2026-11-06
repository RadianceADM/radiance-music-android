package io.github.radianceadm.music.play

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.radianceadm.music.App
import io.github.radianceadm.music.data.Videos
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.Connectivity
import io.github.radianceadm.music.net.Http
import io.github.radianceadm.music.source.YouTube
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A song's music video, to watch in the app (the button on the song's cover,
 * android#26): the one kept on the phone when it's here, else the one on
 * YouTube (not saved yet, or the app was installed again).
 */
object MusicVideo {
    /** Each song's music video on YouTube, looked up once: its id, or "" for none. */
    private val found = ConcurrentHashMap<String, String>()

    /** [t]'s video kept on the phone (and still there), if it is. */
    fun kept(context: Context, t: Track): String? {
        val v = App.instance.library.best(t).video ?: Videos.kept(context, t.key)?.let { Videos.address(it) } ?: return null
        val u = Uri.parse(v)
        return if (u.scheme == "file" && !File(u.path.orEmpty()).exists()) null else v
    }

    /** [t]'s music video on YouTube, or null (worker thread; asked once per song while online). */
    fun online(t: Track): String? {
        found[t.key]?.let { return it.ifEmpty { null } }
        if (!Connectivity.online.value) return null
        val id = YouTube.counterpart(t)?.id
        // counterpart finds nothing offline too: "none" is only kept when it looked online
        if (id != null || Connectivity.online.value) found[t.key] = id.orEmpty()
        return id
    }

    /** Has a music video to watch: kept here, or found on YouTube (worker thread). */
    fun has(context: Context, t: Track): Boolean = kept(context, t) != null || online(t) != null

    /** What the video player plays for [t], or null when it has no music video (worker thread). */
    @OptIn(UnstableApi::class)
    fun source(context: Context, t: Track): MediaSource? {
        val http = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.USER_AGENT)
        val files = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context, http))
        kept(context, t)?.let { return files.createMediaSource(MediaItem.fromUri(it)) }
        val id = online(t) ?: return null
        val web = ProgressiveMediaSource.Factory(http)
        // its picture (up to 720p) and its sound, as Saver saves them; else the one stream with both (360p)
        YouTube.videoParts(id)?.let { (picture, sound) ->
            return MergingMediaSource(web.createMediaSource(MediaItem.fromUri(picture.url)), web.createMediaSource(MediaItem.fromUri(sound.url)))
        }
        return YouTube.videoWithSound(id)?.let { web.createMediaSource(MediaItem.fromUri(it.url)) }
    }
}

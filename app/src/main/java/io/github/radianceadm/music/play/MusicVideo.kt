package io.github.radianceadm.music.play

import android.content.Context
import android.net.Uri
import io.github.radianceadm.music.App
import io.github.radianceadm.music.data.Videos
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.Connectivity
import io.github.radianceadm.music.net.fail
import io.github.radianceadm.music.source.YouTube
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A song's music video, to watch in the app (the button on the song's cover,
 * android#26): the one kept on the phone when it's here, else the one on
 * YouTube (not saved yet, or the app was installed again). It plays in the
 * song's place in the player, from the same spot (PlayerHub.switchVideo).
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

    /** What the player plays as [t]'s music video: its kept file, else "ytv:ID" (YouTube's, found as it starts); null: none (worker thread). */
    fun address(context: Context, t: Track): String? = kept(context, t) ?: online(t)?.let { "ytv:$it" }

    /** YouTube's picture (up to 720p, H.264) and sound for the video [id], as Saver saves them (PlaybackService). */
    fun parts(id: String): Pair<YouTube.Stream, YouTube.Stream> = YouTube.videoParts(id) ?: fail("YouTube didn't give this music video.")
}

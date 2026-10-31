package io.github.radianceadm.music.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.Http
import io.github.radianceadm.music.net.fail
import io.github.radianceadm.music.source.Lyrics
import io.github.radianceadm.music.source.LyricsFinder
import io.github.radianceadm.music.source.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Like it, save it: a liked song goes into Music/Radiance Music (Opus from
 * YouTube, FLAC from the Internet Archive), its music video into
 * Movies/Radiance Music (720p, picture and sound joined without re-encoding),
 * its cover and lyrics into the app's own folder.
 */
object Saver {
    private fun name(t: Track) = "${t.artist} - ${t.title}".map { if (it in "/\\:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("").trim().take(150)

    private fun safe(key: String) = key.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

    fun coverFile(context: Context, t: Track) = File(context.filesDir, "covers/${safe(t.key)}.jpg")
    fun lyricsFile(context: Context, t: Track) = File(context.filesDir, "lyrics/${safe(t.key)}.lrc")

    /** Saves what the song still lacks (worker thread; a minute or two). The song with its files. */
    suspend fun save(context: Context, t: Track, say: (String) -> Unit = {}): Track = withContext(Dispatchers.IO) {
        var out = t
        if (out.file == null) {
            say("Saving “${t.title}”…")
            out = out.copy(file = saveSong(context, t).toString())
        }
        runCatching { saveCover(context, out) }
        runCatching { lyrics(context, out) }
        if (out.source == Source.YOUTUBE && out.video == null) {
            runCatching { saveVideo(context, out, say) }.getOrNull()?.let { out = out.copy(video = it.toString()) }
        }
        out
    }

    private fun insert(context: Context, collection: Uri, display: String, mime: String, folder: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, display)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection, values) ?: fail("Couldn't make the file in $folder.")
    }

    private fun finish(context: Context, uri: Uri) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    /**
     * The song into Music/Radiance Music. From YouTube: its Opus sound, moved
     * from WebM into an MP4 (.m4a) as it is (Android's music library won't
     * take WebM); if that can't be done here, its AAC sound. From the
     * Internet Archive: the FLAC itself.
     */
    private suspend fun saveSong(context: Context, t: Track): Uri {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        if (t.source != Source.YOUTUBE) {
            return keep(context, collection, "${name(t)}.flac", "audio/flac", "Music/Radiance Music") { out -> Http.download(t.id, out, null) }
        }
        val cache = File(context.cacheDir, "song-${safe(t.key)}").apply { mkdirs() }
        try {
            val s = YouTube.audio(t.id)
            val got = File(cache, "sound.${s.ext}")
            got.outputStream().use { Http.download(s.url, it, s.size) }
            val m4a = if (s.ext == "m4a") got else File(cache, "sound.m4a").also { out ->
                runCatching { export(context, Composition.Builder(EditedMediaItemSequence.Builder(audioOnly(got)).build()).build(), out) }.getOrElse {
                    // not possible on this phone: YouTube's AAC version instead
                    val aac = YouTube.aac(t.id) ?: throw it
                    out.outputStream().use { o -> Http.download(aac.url, o, aac.size) }
                }
            }
            return keep(context, collection, "${name(t)}.m4a", "audio/mp4", "Music/Radiance Music") { out -> m4a.inputStream().use { it.copyTo(out) } }
        } finally {
            cache.deleteRecursively()
        }
    }

    /** A song that came from another device, into Music/Radiance Music. */
    fun keepSong(context: Context, display: String, mime: String, write: (java.io.OutputStream) -> Unit): Uri =
        keep(context, MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), display, mime, "Music/Radiance Music", write)

    /** A music video that came from another device, into Movies/Radiance Music. */
    fun keepVideo(context: Context, display: String, mime: String, write: (java.io.OutputStream) -> Unit): Uri =
        keep(context, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), display, mime, "Movies/Radiance Music", write)

    /** A new file in [folder], written by [write]; a half-written one isn't kept. */
    private fun keep(context: Context, collection: Uri, display: String, mime: String, folder: String, write: (java.io.OutputStream) -> Unit): Uri {
        val uri = insert(context, collection, display, mime, folder)
        try {
            context.contentResolver.openOutputStream(uri)!!.use(write)
            finish(context, uri)
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun audioOnly(f: File) = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f))).setRemoveVideo(true).build()

    private fun saveCover(context: Context, t: Track) {
        val f = coverFile(context, t)
        if (f.exists() || t.art.isEmpty()) return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".part")
        tmp.outputStream().use { Http.download(t.art, it, null) }
        tmp.renameTo(f)
    }

    /** The song's lyrics: kept ones first, else looked up (and kept, for a liked song). */
    fun lyrics(context: Context, t: Track): Lyrics {
        val f = lyricsFile(context, t)
        if (f.exists()) return LyricsFinder.fromFile(f.readText())
        val found = LyricsFinder.find(t)
        if (t.file != null) found.toFile()?.let { text ->
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
        return found
    }

    private suspend fun saveVideo(context: Context, t: Track, say: (String) -> Unit): Uri? {
        val video = YouTube.counterpart(t) ?: return null
        say("Saving “${t.title}”'s music video…")
        val cache = File(context.cacheDir, "video-${safe(t.key)}").apply { mkdirs() }
        try {
            val joined = File(cache, "joined.mp4")
            val parts = YouTube.videoParts(video.id)
            if (parts != null) {
                val (picture, sound) = parts
                val (p, s) = File(cache, "picture.mp4") to File(cache, "sound.m4a")
                p.outputStream().use { Http.download(picture.url, it, picture.size) }
                s.outputStream().use { Http.download(sound.url, it, sound.size) }
                join(context, p, s, joined)
            } else {
                val one = YouTube.videoWithSound(video.id) ?: return null
                joined.outputStream().use { Http.download(one.url, it, one.size) }
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = insert(context, collection, "${name(t)}.mp4", "video/mp4", "Movies/Radiance Music")
            context.contentResolver.openOutputStream(uri)!!.use { out -> joined.inputStream().use { it.copyTo(out) } }
            finish(context, uri)
            return uri
        } finally {
            cache.deleteRecursively()
        }
    }

    /** The picture and the sound in one MP4, copied as they are (no re-encoding). */
    private suspend fun join(context: Context, picture: File, sound: File, out: File) {
        val v = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(picture))).setRemoveAudio(true).build()
        export(context, Composition.Builder(EditedMediaItemSequence.Builder(v).build(), EditedMediaItemSequence.Builder(audioOnly(sound)).build()).build(), out)
    }

    /** Writes [composition] to [out] (an MP4), copying the streams as they are when it can. */
    private suspend fun export(context: Context, composition: Composition, out: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(composition, out.absolutePath)
            cont.invokeOnCancellation { transformer.cancel() }
        }
    }
}

package io.github.radianceadm.music.data

import io.github.radianceadm.music.source.Art
import android.graphics.BitmapFactory
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
import io.github.radianceadm.music.server.Server
import io.github.radianceadm.music.server.ServerFirst
import io.github.radianceadm.music.server.ServerSong
import io.github.radianceadm.music.server.ServerSongs
import io.github.radianceadm.music.source.Lyrics
import io.github.radianceadm.music.source.LyricsFinder
import io.github.radianceadm.music.source.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Like it, save it: a liked song goes into Music/Radiance Music (Opus from
 * YouTube, FLAC from the Internet Archive); its cover and its lyrics into the
 * app's own folders. Never its music video: phones have none (owner,
 * 2026-11-08, android#32; see Videos).
 */
object Saver {
    /** The name a saved song's file gets ("Artist - Title"; Found reads it back). */
    internal fun name(t: Track) = "${t.artist} - ${t.title}".map { if (it in "/\\:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("").trim().take(150)

    internal fun safe(key: String) = key.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

    fun coverFile(context: Context, t: Track) = File(context.filesDir, "covers/${safe(t.key)}.jpg")

    /** Whether each song has a cover kept here, looked at once (the lists ask often); forgotten when one is kept anew. */
    private val kept = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * The song's cover kept on this phone, as an address a picture loads (null: none kept): what the screens show first
     * (android#41: songs that came without a cover's address showed none, though their cover was here).
     */
    fun keptCover(context: Context, t: Track): String? {
        val f = coverFile(context, t)
        return if (kept.getOrPut(t.key) { f.exists() }) f.path else null
    }

    /** A cover was just kept (or replaced) for [key]: looked at again next time. */
    fun coverKept(key: String) {
        kept.remove(key)
    }
    fun lyricsFile(context: Context, t: Track) = File(context.filesDir, "lyrics/${safe(t.key)}.lrc")

    private val _got = MutableStateFlow<Pair<String, Float>?>(null)
    /** The song being saved now (its key) and how much of it is here, 0-1: a split waiting for it shows that. */
    val got: StateFlow<Pair<String, Float>?> = _got

    /**
     * Saves what the song still lacks (worker thread; a minute or two). The song with its files.
     * [songSaved]: the song itself is here (before its cover and lyrics), so a split
     * waiting for it starts at once.
     */
    suspend fun save(context: Context, t: Track, say: (String) -> Unit = {}, songSaved: (Track) -> Unit = {}): Track = withContext(Dispatchers.IO) {
        var out = t
        if (out.file == null) {
            say("Saving “${t.title}”…")
            _got.value = t.key to 0f
            try {
                out = out.copy(file = saveSong(context, t).toString())
            } finally {
                _got.value = null
            }
            songSaved(out)
        }
        runCatching { cover(context, out) }
        runCatching { lyrics(context, out) }
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
        if (t.source == Source.SERVER) {
            // from a Radiance Music Server you joined: its file as it is there (Opus, FLAC or AAC), its key going along
            val ext = t.id.substringAfterLast('/').substringBefore('?').substringAfterLast('.', "m4a").lowercase().filter { it.isLetterOrDigit() }.take(5)
            val mime = when (ext) { "flac" -> "audio/flac"; "opus", "ogg" -> "audio/ogg"; else -> "audio/mp4" }
            return keep(context, collection, "${name(t)}.$ext", mime, "Music/Radiance Music") { out -> Http.download(t.id, out, null) }
        }
        if (t.source != Source.YOUTUBE) {
            return keep(context, collection, "${name(t)}.flac", "audio/flac", "Music/Radiance Music") { out -> Http.download(t.id, out, null) }
        }
        // server first: a server you joined has it, so it comes from there (with its parts), not YouTube
        ServerFirst.copy(t)?.let { (s, song) ->
            try {
                return fromServer(context, collection, t, s, song)
            } catch (e: Exception) {
                android.util.Log.w("radiance-save", "${s.name} didn't send “${t.title}”: from YouTube instead", e)
                ServerFirst.forget(t)
            }
        }
        val cache = File(context.cacheDir, "song-${safe(t.key)}").apply { mkdirs() }
        try {
            val s = YouTube.audio(t.id)
            val got = File(cache, "sound.${s.ext}")
            got.outputStream().use { Http.download(s.url, it, s.size) { f -> _got.value = t.key to f * 0.85f } }
            val m4a = if (s.ext == "m4a") got else File(cache, "sound.m4a").also { out ->
                runCatching { export(context, Composition.Builder(EditedMediaItemSequence.Builder(audioOnly(got)).build()).build(), out) }.getOrElse {
                    // not possible on this phone: YouTube's AAC version instead
                    val aac = YouTube.aac(t.id) ?: throw it
                    out.outputStream().use { o -> Http.download(aac.url, o, aac.size) }
                }
            }
            _got.value = t.key to 0.95f
            return keep(context, collection, "${name(t)}.m4a", "audio/mp4", "Music/Radiance Music") { out -> m4a.inputStream().use { it.copyTo(out) } }
        } finally {
            cache.deleteRecursively()
        }
    }

    /**
     * [t] (from YouTube) saved from its copy on [s], [song]: its parts and lyrics first (so a
     * split waiting for the song sees them there, and doesn't split it), then the file as it is
     * there. Not split there yet: the server's asked to, and its parts come when they're done.
     */
    private suspend fun fromServer(context: Context, collection: Uri, t: Track, s: Server, song: ServerSong): Uri {
        val app = context.applicationContext as io.github.radianceadm.music.App
        if (ServerSongs.keepParts(context, song, t)) withContext(Dispatchers.Main) { app.mixer.recheck() }
        else ServerFirst.splitThere(app, s, song, t)
        val ext = song.file.substringAfterLast('.', "m4a").lowercase().filter { it.isLetterOrDigit() }.take(5)
        val mime = when (ext) { "flac" -> "audio/flac"; "opus", "ogg" -> "audio/ogg"; else -> "audio/mp4" }
        val size = song.files["song"]?.size?.takeIf { it > 0 }
        return keep(context, collection, "${name(t)}.$ext", mime, "Music/Radiance Music") { out ->
            Http.download(song.url, out, size) { f -> _got.value = t.key to f }
        }
    }

    /** A song that came from another device, into Music/Radiance Music. */
    fun keepSong(context: Context, display: String, mime: String, write: (java.io.OutputStream) -> Unit): Uri =
        keep(context, MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), display, mime, "Music/Radiance Music", write)

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

    /**
     * The song's cover kept here (for a liked song), big ([Art.BIG]), if it isn't yet. One kept small before (YouTube
     * Music's 120 px, the app's first versions) is fetched again big; if that fails, the small one stays.
     */
    fun cover(context: Context, t: Track) {
        val f = coverFile(context, t)
        if (t.art.isEmpty()) return
        val again = f.exists()
        if (again && !(Art.grows(t.art) && small(f))) return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".part")
        try {
            tmp.outputStream().use { Http.download(Art.sized(t.art, Art.BIG), it, null) }
            tmp.renameTo(f)
            coverKept(t.key)
        } catch (e: Exception) {
            tmp.delete()
            if (!again) throw e
        }
    }

    /** A kept cover too small for Now Playing or a story (under 600 px across). */
    fun small(f: File): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return o.outWidth in 1 until 600
    }

    /** The song's lyrics: kept ones first, else looked up (and kept, for a liked song). */
    fun lyrics(context: Context, t: Track): Lyrics {
        val f = lyricsFile(context, t)
        if (f.exists()) return LyricsFinder.fromFile(f.readText())
        // a server's song: its own lyrics there first (each word timed, often)
        val found = (if (t.source == Source.SERVER) ServerSongs.lyrics(t) else null) ?: LyricsFinder.find(t)
        if (t.file != null) found.toFile()?.let { text ->
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
        return found
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

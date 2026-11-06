package io.github.radianceadm.music.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * Where liked songs' music videos are kept: the app's own folder, so Gallery
 * doesn't list them (owner, 2026-11-06: "bakit lumalabas sa gallery yung
 * videos???", android#26). They're watched in the app, from the button on
 * the song's cover. The folder is left out of Android's backup (videos would
 * go past its 25 MB), so uninstalling the app takes them with it; the songs
 * themselves stay in Music/Radiance Music.
 */
object Videos {
    fun dir(context: Context) = File(context.noBackupFilesDir, "videos")

    /** The file for the song [key]'s video. */
    fun fileIn(dir: File, key: String, ext: String = "mp4") = File(dir, "${Saver.safe(key)}.${ext.ifEmpty { "mp4" }}")

    /** How a kept video is written down in a song (as Import writes a file it reads). */
    fun address(f: File) = "file://${f.absolutePath}"

    /** [video] is kept in [dir] (not in Movies/Radiance Music, as before 1.0.2). */
    fun inApp(dir: File, video: String) = video.startsWith("file://${dir.absolutePath}/")

    /** The video kept for [key] already (liked before, unliked, liked again), if there is one. */
    fun kept(context: Context, key: String): File? =
        dir(context).listFiles()?.firstOrNull { it.nameWithoutExtension == Saver.safe(key) && !it.name.endsWith(".part") && it.length() > 0 }

    /** A video written by [write] and kept for [key]; a half-written one isn't. Its address. */
    fun keep(context: Context, key: String, ext: String, write: (OutputStream) -> Unit): String {
        val f = fileIn(dir(context), key, ext).apply { parentFile?.mkdirs() }
        val part = File(f.path + ".part")
        try {
            part.outputStream().use(write)
            if (!part.renameTo(f)) throw java.io.IOException("couldn't put ${f.name} in place")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        return address(f)
    }

    /** [made] (a whole video file in the app's cache) kept for [key]: moved, not copied, when it can be. Its address. */
    fun put(context: Context, key: String, made: File): String {
        val f = fileIn(dir(context), key, made.extension).apply { parentFile?.mkdirs() }
        if (made.renameTo(f)) return address(f)
        return keep(context, key, made.extension) { out -> made.inputStream().use { it.copyTo(out) } }
    }

    /**
     * Liked songs' videos still in Movies/Radiance Music (saved before 1.0.2,
     * or brought back by Found or Import) moved into the app's folder. Old
     * copies this install made are removed from Gallery straight away; those it
     * may not remove (made before a reinstall, or copied over by hand) come
     * back as their MediaStore addresses, for the phone to ask about once.
     * [say]: how many done of how many.
     */
    fun moveIn(context: Context, library: Library, say: (Int, Int) -> Unit = { _, _ -> }): List<Uri> {
        val dir = dir(context)
        val waiting = library.liked.value.filter { t -> t.video?.let { !inApp(dir, it) } == true }
        val left = mutableListOf<Uri>()
        var moved = 0
        waiting.forEachIndexed { i, t ->
            say(i, waiting.size)
            val from = Uri.parse(t.video!!)
            val ext = nameOf(context, from)?.substringAfterLast('.', "")?.lowercase()?.filter { it.isLetterOrDigit() }?.take(5).orEmpty()
            val kept = try {
                val input = context.contentResolver.openInputStream(from) ?: throw FileNotFoundException(t.video)
                input.use { keep(context, t.key, ext) { out -> it.copyTo(out) } }
            } catch (e: FileNotFoundException) {
                // gone (deleted from Gallery, say): the button plays it from YouTube
                Log.i(TAG, "“${t.title}”'s video isn't there any more")
                library.update(t.copy(video = null))
                return@forEachIndexed
            } catch (e: SecurityException) {
                Log.i(TAG, "may not read “${t.title}”'s video yet (no permission): next time")
                return@forEachIndexed
            } catch (e: Exception) {
                Log.w(TAG, "couldn't move “${t.title}”'s video", e)
                return@forEachIndexed
            }
            library.update(t.copy(video = kept))
            moved++
            val media = mediaUri(context, from) ?: return@forEachIndexed
            try {
                context.contentResolver.delete(media, null, null)
            } catch (e: SecurityException) {
                left += media
            }
        }
        if (waiting.isNotEmpty()) {
            say(waiting.size, waiting.size)
            Log.i(TAG, "moved $moved of ${waiting.size} music videos into the app; ${left.size} old copies to ask about")
        }
        return left
    }

    private fun nameOf(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "file") File(uri.path!!).name
        else context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** Where Gallery knows the file from: a content:// address as it is, a file:// one looked up. */
    @Suppress("DEPRECATION") // DATA: still the way to find a file's entry by its path
    private fun mediaUri(context: Context, uri: Uri): Uri? {
        if (uri.scheme == "content") return uri
        val path = uri.path ?: return null
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return runCatching {
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path), null)
                ?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) else null }
        }.getOrNull()
    }

    private const val TAG = "radiance-videos"
}

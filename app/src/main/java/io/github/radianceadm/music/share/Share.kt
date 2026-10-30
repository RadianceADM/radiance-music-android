package io.github.radianceadm.music.share

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.radianceadm.music.model.Source
import io.github.radianceadm.music.model.Track
import java.io.File

/** Sending a song's story card out: Android's share menu (Instagram, Facebook, Messenger…) or the phone's photos. */
object Share {
    /** Where others can hear it: YouTube Music, or the Internet Archive's page for it. */
    fun link(t: Track): String = when (t.source) {
        Source.YOUTUBE -> "https://music.youtube.com/watch?v=${t.id}"
        Source.ARCHIVE -> Regex("archive\\.org/download/([^/]+)").find(t.id)?.groupValues?.get(1)?.let { "https://archive.org/details/$it" } ?: t.id
    }

    fun name(t: Track) = "${t.artist} - ${t.title}".map { if (it in "/\\:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("").trim().take(120)

    /** What apps that take words too get with the story: the song and where to hear it. */
    fun text(t: Track) = "${t.title} — ${t.artist}\n${link(t)}"

    /** [card] as a picture other apps may read (only the latest is kept). */
    fun image(context: Context, t: Track, card: Bitmap): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        val f = File(dir, "${name(t)}.jpg")
        f.outputStream().use { card.compress(Bitmap.CompressFormat.JPEG, 93, it) }
        return uriOf(context, f)
    }

    fun uriOf(context: Context, f: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)

    /** Opens the share menu with [card] (and the song's name and link, for apps that take words too). */
    fun send(context: Context, t: Track, card: Bitmap) {
        val uri = image(context, t, card)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text(t))
            clipData = ClipData.newRawUri(t.title, uri) // lets the app picked see the picture
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share “${t.title}”").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Saves a story [video] in Pictures/Radiance Music, beside the cards. Its address. */
    fun saveVideo(context: Context, t: Track, video: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${name(t)}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Radiance Music")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("the phone wouldn't make the video")
        r.openOutputStream(uri)!!.use { out -> video.inputStream().use { it.copyTo(out) } }
        r.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** Saves [card] in Pictures/Radiance Music. The picture's address. */
    fun save(context: Context, t: Track, card: Bitmap): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${name(t)}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Radiance Music")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("the phone wouldn't make the picture")
        r.openOutputStream(uri)!!.use { card.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        r.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}

package io.github.radianceadm.music.share

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.radianceadm.music.App
import io.github.radianceadm.music.data.Import
import io.github.radianceadm.music.data.Saver
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.model.fold
import io.github.radianceadm.music.net.fail
import io.github.radianceadm.music.split.Splitter
import java.io.File
import java.io.InputStream

/** This phone's songs, as Wire offers them and takes them in. */
object PhoneShare {
    private fun sizeOf(context: Context, uri: String): Long? = runCatching {
        val u = Uri.parse(uri)
        if (u.scheme == "file") File(u.path!!).takeIf { it.exists() }?.length()
        else context.contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()

    private fun nameOf(context: Context, uri: String): String? = runCatching {
        val u = Uri.parse(uri)
        if (u.scheme == "file") File(u.path!!).name
        else context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun openUri(context: Context, uri: String): InputStream? = runCatching {
        val u = Uri.parse(uri)
        if (u.scheme == "file") File(u.path!!).inputStream() else context.contentResolver.openInputStream(u)
    }.getOrNull()

    /** [songs] (saved ones only), each with what it has here: the song, its parts, lyrics, cover, and its video if [videos]. */
    class Offer(private val context: Context, songs: List<Track>, private val videos: Boolean) : Wire.Offer {
        private val list = songs.filter { it.file != null }
        private val opens = HashMap<Pair<String, String>, () -> InputStream?>()

        private val made: List<Wire.Item> by lazy {
            list.mapNotNull { t ->
                val files = LinkedHashMap<String, Wire.FileInfo>()
                val song = t.file!!
                val size = sizeOf(context, song) ?: return@mapNotNull null
                files["song"] = Wire.FileInfo(nameOf(context, song) ?: "song", size)
                opens[t.key to "song"] = { openUri(context, song) }
                for ((part, f) in Import.parts(context, t.key)) if (part in Wire.KINDS) {
                    files[part] = Wire.FileInfo(f.name, f.length())
                    opens[t.key to part] = { f.inputStream() }
                }
                Saver.lyricsFile(context, t).takeIf { it.exists() }?.let { f -> files["lyrics"] = Wire.FileInfo(f.name, f.length()); opens[t.key to "lyrics"] = { f.inputStream() } }
                Saver.coverFile(context, t).takeIf { it.exists() }?.let { f -> files["cover"] = Wire.FileInfo(f.name, f.length()); opens[t.key to "cover"] = { f.inputStream() } }
                if (videos) t.video?.let { v ->
                    sizeOf(context, v)?.let { s -> files["video"] = Wire.FileInfo(nameOf(context, v) ?: "video.mp4", s); opens[t.key to "video"] = { openUri(context, v) } }
                }
                // its details travel, not where it sits on this phone
                Wire.Item(t.key, t.copy(file = null, video = null).toJson(), files)
            }
        }

        override fun items() = made
        override fun open(key: String, kind: String) = opens[key to kind]?.invoke()

        /** All of it, in bytes. */
        fun bytes() = made.sumOf { i -> i.files.values.sumOf { it.size } }
    }

    /** Songs coming in: into Music/ and Movies/ Radiance Music and the app's own folders, then Liked Songs. */
    class Store(private val context: Context) : Wire.Store {
        private val library = App.instance.library
        /** What each song came as so far (its file, its video). */
        private val saved = HashMap<String, Pair<String?, String?>>()

        /** The same song here already: the same key, or the same title by the same artist. */
        private fun mine(item: Wire.Item): Track? {
            val t = Track.fromJson(item.track) ?: return null
            library.liked.value.firstOrNull { it.key == item.key }?.let { return it }
            val name = fold("${t.artist}|${t.title}")
            return library.liked.value.firstOrNull { fold("${it.artist}|${it.title}") == name }
        }

        /** The song's file in Music/Radiance Music by its name (copied by hand, say), if it's there. */
        private fun onPhone(name: String): Uri? = runCatching {
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            context.contentResolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(name, "Music/Radiance Music%"), null,
            )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) else null }
        }.getOrNull()

        override fun have(item: Wire.Item): Set<String> {
            val here = mutableSetOf<String>()
            val t = mine(item)
            val key = t?.key ?: item.key
            if (t?.file != null && sizeOf(context, t.file) != null) here += "song"
            else item.files["song"]?.name?.let { onPhone(it) }?.let { uri -> here += "song"; saved[item.key] = uri.toString() to saved[item.key]?.second }
            here += Import.parts(context, key).keys
            val probe = Track.fromJson(item.track)?.copy() ?: return here
            val local = t ?: probe
            if (Saver.lyricsFile(context, local).exists()) here += "lyrics"
            if (Saver.coverFile(context, local).exists()) here += "cover"
            if (t?.video != null) here += "video"
            return here
        }

        override fun write(item: Wire.Item, kind: String, input: InputStream, size: Long) {
            val name = item.files.getValue(kind).name
            val ext = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5)
            val t = mine(item) ?: Track.fromJson(item.track) ?: fail("A song came with no details.")
            when (kind) {
                "song" -> {
                    val display = safeName(name, ext.ifEmpty { "m4a" })
                    val uri = Saver.keepSong(context, display, mimeOf(ext)) { input.copyTo(it) }
                    saved[item.key] = uri.toString() to saved[item.key]?.second
                }
                "video" -> {
                    val uri = Saver.keepVideo(context, safeName(name, ext.ifEmpty { "mp4" }), mimeOf(ext)) { input.copyTo(it) }
                    saved[item.key] = saved[item.key]?.first to uri.toString()
                }
                "lyrics" -> into(Saver.lyricsFile(context, t), input)
                "cover" -> into(Saver.coverFile(context, t), input)
                else -> into(Splitter.partFileFor(context, t.key, kind, ext.ifEmpty { "m4a" }).also { Splitter.dir(context).mkdirs() }, input)
            }
        }

        /** Written whole, then put in place (a stopped transfer leaves nothing half-there). */
        private fun into(f: File, input: InputStream) {
            f.parentFile?.mkdirs()
            val part = File(f.path + ".part")
            part.outputStream().use { input.copyTo(it) }
            part.renameTo(f)
        }

        override fun done(item: Wire.Item) {
            val (song, video) = saved.remove(item.key) ?: (null to null)
            val have = mine(item)
            val base = have ?: Track.fromJson(item.track) ?: return
            val t = base.copy(file = song ?: base.file, video = video ?: base.video)
            if (t.file == null) return
            if (have == null) library.like(t) else library.update(t)
            App.instance.mixer.recheck()
        }

        private fun safeName(name: String, ext: String): String {
            val stem = name.substringBeforeLast('.').map { if (it in "/\\:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("").trim().take(150).ifEmpty { "song" }
            return "$stem.$ext"
        }

        private fun mimeOf(ext: String) = when (ext) {
            "m4a", "mp4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "opus", "ogg", "oga" -> "audio/ogg"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }

    /**
     * Gentle with Bluetooth: while music plays to Bluetooth headphones or a
     * speaker, songs move at most 3 MB a second (Wi-Fi and Bluetooth share a
     * radio on many phones; a flood of Wi-Fi can make the music stutter).
     */
    fun pace(context: Context) = Wire.Pace {
        val am = context.getSystemService(AudioManager::class.java)
        val bt = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }
        if (bt && am.isMusicActive) 3L shl 20 else 0L
    }
}

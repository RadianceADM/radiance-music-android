package io.github.radianceadm.music.server

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.radianceadm.music.App
import io.github.radianceadm.music.data.Import
import io.github.radianceadm.music.data.Saver
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.model.plain
import io.github.radianceadm.music.net.Errors
import io.github.radianceadm.music.net.Http
import io.github.radianceadm.music.net.fail
import io.github.radianceadm.music.play.Mixer
import io.github.radianceadm.music.source.Lyrics
import io.github.radianceadm.music.source.LyricsFinder
import io.github.radianceadm.music.split.Splitter
import io.github.radianceadm.music.ui.Notice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.InputStream

/**
 * A server's songs on this phone: saved into your library (the song, its
 * parts, lyrics and cover, as a liked song is saved), their parts fetched for
 * the Mixer, their lyrics, and your liked songs added to a server.
 */
object ServerSongs {
    /** Songs being saved from a server now (their keys): their rows say so. */
    private val _saving = MutableStateFlow<Set<String>>(emptySet())
    val saving: StateFlow<Set<String>> = _saving

    /** The parts being fetched for the Mixer: the song's key → the part done, 0-1. */
    private val _fetching = MutableStateFlow<Map<String, Float>>(emptyMap())
    val fetching: StateFlow<Map<String, Float>> = _fetching

    /**
     * Saves [song] from [s] into this phone's library: its lyrics and its
     * parts where the phone keeps them, then the song itself and its cover
     * (into Music/Radiance Music), as a liked song is saved. Said when done.
     */
    fun save(app: App, s: Server, song: ServerSong) {
        val t = song.track()
        if (t.key in _saving.value) return
        _saving.value = _saving.value + t.key
        app.scope.launch {
            try {
                // the server's lyrics (with each word's time) are kept first, so they're the ones saved
                if (!Saver.lyricsFile(app, t).exists()) lyricsText(song)?.let { text -> into(Saver.lyricsFile(app, t)) { it.write(text.toByteArray()) } }
                // its parts, as if split here: the Mixer plays them, Share Songs sends them on
                if (song.split && !Splitter.isSplit(app, t.key)) {
                    // fetched for the Mixer already: copied from there, not downloaded again
                    val fetched = parts(app, t)
                    for (part in Mixer.PARTS) {
                        val f = song.files[part] ?: continue
                        val ext = f.name.substringAfterLast('.', "m4a").lowercase()
                        val to = Splitter.partFileFor(app, t.key, part, ext).also { Splitter.dir(app).mkdirs() }
                        val have = fetched[part]
                        if (have != null) into(to) { out -> have.inputStream().use { it.copyTo(out) } } else download(f.url, to)
                    }
                    app.mixer.recheck()
                }
                if (app.saveLiked(t)) {
                    val with = listOfNotNull("its parts".takeIf { song.split }, "lyrics".takeIf { song.lyrics }).joinToString(" and ")
                    app.tell(Notice("Saved “${song.title}” on this phone${if (with.isEmpty()) "" else ", with $with"}. It's in Liked Songs."))
                }
            } catch (e: Exception) {
                Servers.heard(s, e)
                app.tell(Notice("Couldn't save “${song.title}” from ${s.name}. ${Errors.server(e, s.name)}", Notice.retry { save(app, s, song) }))
            } finally {
                _saving.value = _saving.value - t.key
            }
        }
    }

    /** [url] (on a joined server: its key goes along) into [f], whole or not at all. */
    private fun download(url: String, f: File, progress: (Long) -> Unit = {}) = into(f) { out ->
        Http.client.newCall(Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) Rms.answer(r)
            val src = r.body.byteStream()
            val buf = ByteArray(256 * 1024)
            var have = 0L
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                have += n
                progress(have)
            }
        }
    }

    /** Written beside [f] whole, then put in place (a stopped download leaves nothing half-there). */
    private fun into(f: File, write: (java.io.OutputStream) -> Unit) {
        f.parentFile?.mkdirs()
        val part = File(f.path + ".part")
        try {
            part.outputStream().use(write)
            if (!part.renameTo(f)) fail("Couldn't keep ${f.name}.")
        } finally {
            part.delete()
        }
    }

    /** The song's lyrics from the server as the app keeps them (LRC; a Lyricsfile's timed lines), or null. */
    private fun lyricsText(song: ServerSong): String? {
        val f = song.files["lyrics"] ?: return null
        val text = Http.text(f.url) ?: return null
        val lyrics = if (f.name.endsWith(".yaml")) Import.lyricsfile(text) else LyricsFinder.fromFile(text)
        return lyrics?.toFile()
    }

    /** A server's song's lyrics (playing from the server, not saved): its own, if it has some; null: it has none, or it didn't answer. */
    fun lyrics(t: Track): Lyrics? {
        val (_, song) = Servers.find(t) ?: return null
        return lyricsText(song)?.let { LyricsFinder.fromFile(it) }?.takeIf { it !is Lyrics.None }
    }

    /**
     * A server moved from [old] to [new]: your songs from it follow (Library),
     * and what's kept by each song's key (its cover, its lyrics, its parts)
     * goes under its new key.
     */
    fun rebase(app: App, old: String, new: String) {
        for ((was, now) in app.library.rebase(old, new)) {
            if (was.key == now.key) continue
            Saver.coverFile(app, was).takeIf { it.exists() }?.renameTo(Saver.coverFile(app, now))
            Saver.lyricsFile(app, was).takeIf { it.exists() }?.renameTo(Saver.lyricsFile(app, now))
            for ((part, f) in Splitter.parts(app, was.key)) f.renameTo(Splitter.partFileFor(app, now.key, part, f.extension))
            File(cache(app), safe(was.key)).takeIf { it.exists() }?.renameTo(File(cache(app), safe(now.key)))
        }
    }

    // ── parts for the Mixer, from the server ──

    private fun safe(key: String) = key.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    private fun cache(context: Context) = File(context.cacheDir, "server-parts")

    /** The four parts of a server's song fetched for the Mixer (all four, or none). */
    fun parts(context: Context, t: Track): Map<String, File> {
        val dir = File(cache(context), safe(t.key))
        val files = dir.listFiles()?.filter { !it.name.endsWith(".part") } ?: return emptyMap()
        val found = Mixer.PARTS.mapNotNull { p -> files.firstOrNull { it.name.startsWith("$p.") }?.let { p to it } }.toMap()
        return if (found.size == Mixer.PARTS.size) found else emptyMap()
    }

    /** The song is split on its server (as last listed). */
    fun splitThere(t: Track): Boolean = Servers.song(t)?.second?.split == true

    /**
     * Fetches the four parts of [t] (split on its server) for the Mixer, into
     * the app's cache (the last few songs' are kept). Waits for the network;
     * [fetching] shows how far along.
     */
    fun fetchParts(context: Context, t: Track) {
        if (parts(context, t).isNotEmpty()) return
        val (s, song) = Servers.find(t) ?: fail("Couldn't find this song on its server any more.")
        if (!song.split) fail("${s.name} hasn't split this song yet.")
        val files = Mixer.PARTS.map { p -> p to (song.files[p] ?: fail("${s.name} doesn't have this song's ${p} part.")) }
        val total = files.sumOf { it.second.size }.coerceAtLeast(1)
        val dir = File(cache(context), safe(t.key))
        tidy(context, keep = dir)
        var done = 0L
        _fetching.value = _fetching.value + (t.key to 0f)
        try {
            for ((p, f) in files) {
                val before = done
                val ext = f.name.substringAfterLast('.', "ogg").lowercase()
                download(f.url, File(dir, "$p.$ext")) { have -> _fetching.value = _fetching.value + (t.key to ((before + have).toFloat() / total).coerceIn(0f, 1f)) }
                done += f.size
            }
        } catch (e: Exception) {
            Servers.heard(s, e)
            throw e
        } finally {
            _fetching.value = _fetching.value - t.key
        }
    }

    /** Only the last few songs' parts stay in the cache (a song's parts can be 100 MB). */
    private fun tidy(context: Context, keep: File) {
        val dirs = cache(context).listFiles()?.filter { it.isDirectory && it != keep }?.sortedByDescending { it.lastModified() } ?: return
        dirs.drop(KEEP_PARTS - 1).forEach { it.deleteRecursively() }
    }

    private const val KEEP_PARTS = 4

    // ── your songs, to a server ──

    /** [t] is on [songs] already (the same artist and title). */
    fun has(songs: List<ServerSong>, t: Track): Boolean = copyOf(songs, t) != null

    /** [t]'s copy among [songs] (the same artist and title), if there is one. */
    fun copyOf(songs: List<ServerSong>, t: Track): ServerSong? = songs.firstOrNull { plain(it.title) == plain(t.title) && plain(it.artist) == plain(t.artist) }

    /** Your songs going to a server now: the song's key → the part sent, 0-1. */
    private val _sending = MutableStateFlow<Map<String, Float>>(emptyMap())
    val sending: StateFlow<Map<String, Float>> = _sending

    /**
     * Adds your liked song [t] to [s] in the background (it goes on if you
     * close the sheet), with its parts when it's split here; [onServer]: its
     * copy there already (only its parts go, if it has none). Said when done;
     * [then]: it's there.
     */
    fun add(app: App, s: Server, t: Track, onServer: ServerSong? = null, then: () -> Unit = {}) {
        if (t.key in _sending.value) return
        _sending.value = _sending.value + (t.key to 0f)
        app.scope.launch {
            try {
                val got = if (onServer == null) upload(app, s, t) { done -> _sending.value = _sending.value + (t.key to done) }
                else Sent(Uploaded(added = false, skipped = true, extras = emptyList(), song = onServer), sendParts(app, s, t, onServer) { done -> _sending.value = _sending.value + (t.key to done) })
                app.tell(Notice(said(s, t, got)))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { then() }
            } catch (e: Exception) {
                app.tell(Notice("Couldn't add “${t.title}” to ${s.name}. ${Errors.server(e, s.name)}", Notice.retry { add(app, s, t, onServer, then) }))
            } finally {
                _sending.value = _sending.value - t.key
            }
        }
    }

    /** What went to a server: the song (or that it was there), and its parts (null: none went). */
    data class Sent(val song: Uploaded, val parts: PartsAdded?)

    /** What's said when it's done. */
    fun said(s: Server, t: Track, got: Sent): String {
        val parts = got.parts?.added?.isNotEmpty() == true
        return when {
            got.song.added && parts -> "Added “${t.title}” to ${s.name}, with its parts. Everyone there can play it and mix it now."
            got.song.added -> "Added “${t.title}” to ${s.name}. Everyone there can play it now."
            parts -> "${s.name} has “${t.title}” already; its parts went to it now, so everyone there can mix it."
            got.song.extras.isNotEmpty() -> "${s.name} has “${t.title}” already: its ${got.song.extras.joinToString(" and ")} went with it."
            else -> "${s.name} has “${t.title}” already."
        }
    }

    /** The kinds of part file a server takes (as the splitters make them). */
    private val PART_TAKES = setOf("ogg", "flac", "m4a")

    /** [t]'s four parts on this phone (split here, or copied from the computer) as a server takes them; null: it isn't split here. */
    fun phoneParts(context: Context, t: Track): Map<String, Rms.Upload>? {
        val files = Import.parts(context, t.key).filterKeys { it in Mixer.PARTS }
        if (!files.keys.containsAll(Mixer.PARTS) || files.values.any { it.extension.lowercase() !in PART_TAKES }) return null
        val clean = cleanName(t)
        return files.mapValues { (part, f) -> Rms.Upload("$clean.$part.${f.extension.lowercase()}", f.length(), partMime(f.extension.lowercase())) { f.inputStream() } }
    }

    /** It's split here, and [song] (its copy on the server) isn't there: its parts can go. */
    fun canSendParts(context: Context, t: Track, song: ServerSong?): Boolean = song != null && !song.split && phoneParts(context, t) != null

    /**
     * [t]'s parts (split on this phone) to its copy on [s], [song]; the
     * server keeps the ones it lacks. null: it isn't split here. Waits for
     * the network.
     */
    fun sendParts(context: Context, s: Server, t: Track, song: ServerSong, progress: (Float) -> Unit = {}): PartsAdded? {
        val parts = phoneParts(context, t) ?: return null
        val lyrics = Saver.lyricsFile(context, t).takeIf { it.exists() }?.let { f -> Rms.Upload("${cleanName(t)}.lrc", f.length(), "text/plain") { f.inputStream() } }
        val total = (parts.values.sumOf { it.size } + (lyrics?.size ?: 0)).coerceAtLeast(1)
        return try {
            s.rms.uploadParts(song.id, parts, lyrics) { sent -> progress((sent.toFloat() / total).coerceIn(0f, 1f)) }
        } catch (e: Exception) {
            Servers.heard(s, e)
            throw e
        }
    }

    private fun cleanName(t: Track) = "${t.artist} - ${t.title}".map { if (it in "/\\:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("").trim().take(150)

    private fun partMime(ext: String) = when (ext) {
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        else -> "audio/mp4"
    }

    /** The kinds of song file a server takes (as the apps save them). */
    private val TAKES = setOf("opus", "flac", "m4a")

    /**
     * Adds your liked song [t] (saved on this phone) to [s], with its cover
     * and lyrics, then its parts when it's split here (and the server's copy
     * isn't). [progress] gets the part sent, 0-1. Waits for the network.
     */
    fun upload(context: Context, s: Server, t: Track, progress: (Float) -> Unit = {}): Sent {
        val file = t.file ?: fail("“${t.title}” isn't saved on this phone yet: wait a moment for it to save.")
        val uri = Uri.parse(file)
        val name = nameOf(context, uri) ?: "song.m4a"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in TAKES) fail("Servers take songs as .opus, .flac or .m4a files; “${t.title}” is a .$ext file.")
        val size = sizeOf(context, uri) ?: -1L
        val clean = cleanName(t)
        val song = Rms.Upload("$clean.$ext", size, mimeOf(ext)) { open(context, uri) ?: fail(Errors.GONE) }
        val cover = Saver.coverFile(context, t).takeIf { it.exists() }?.let { f -> Rms.Upload("$clean.jpg", f.length(), "image/jpeg") { f.inputStream() } }
        val lyrics = Saver.lyricsFile(context, t).takeIf { it.exists() }?.let { f -> Rms.Upload("$clean.lrc", f.length(), "text/plain") { f.inputStream() } }
        val parts = phoneParts(context, t)
        val partsSize = parts?.values?.sumOf { it.size } ?: 0L
        val total = (listOfNotNull(size.takeIf { it > 0 }, cover?.size, lyrics?.size).sum() + partsSize).coerceAtLeast(1)
        return try {
            val got = s.rms.upload(song, t.artist, t.title, t.seconds, cover, lyrics) { sent -> progress((sent.toFloat() / total).coerceIn(0f, 1f)) }
            // split here, and not there: its parts too (after the song: they need its id there)
            val there = got.song
            val sentParts = if (parts != null && there != null && !there.split) {
                val before = total - partsSize
                s.rms.uploadParts(there.id, parts) { sent -> progress(((before + sent).toFloat() / total).coerceIn(0f, 1f)) }
            } else null
            Sent(got, sentParts)
        } catch (e: Exception) {
            Servers.heard(s, e)
            throw e
        }
    }

    private fun open(context: Context, u: Uri): InputStream? = runCatching {
        if (u.scheme == "file") File(u.path!!).inputStream() else context.contentResolver.openInputStream(u)
    }.getOrNull()

    private fun sizeOf(context: Context, u: Uri): Long? = runCatching {
        if (u.scheme == "file") File(u.path!!).takeIf { it.exists() }?.length()
        else context.contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()

    private fun nameOf(context: Context, u: Uri): String? = runCatching {
        if (u.scheme == "file") File(u.path!!).name
        else context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun mimeOf(ext: String) = when (ext) {
        "flac" -> "audio/flac"
        "opus" -> "audio/ogg"
        else -> "audio/mp4"
    }
}

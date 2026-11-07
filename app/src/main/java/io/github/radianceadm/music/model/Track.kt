package io.github.radianceadm.music.model

import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer

/** Where a song comes from: the Internet Archive, YouTube Music, or a Radiance Music Server you joined. */
enum class Source { ARCHIVE, YOUTUBE, SERVER }

/** A song, wherever it comes from (as the desktop app's track.rs). */
data class Track(
    val source: Source,
    /** YouTube: the video id. Archive: the file's download URL. Server: the song's file there (https://HOST/files/NAME, no key in it). */
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val seconds: Int = 0,
    val art: String = "",
    /** "FLAC", "24-bit FLAC", "Opus"… */
    val format: String = "",
    /** The saved copy (a content:// address in Music/Radiance Music), once liked and saved. */
    val file: String? = null,
    // no music video: phones have none (owner, 2026-11-08, android#32); an old library's "video" is left unread
) {
    val key: String get() = when (source) {
        Source.ARCHIVE -> "ia:$id"
        Source.YOUTUBE -> "yt:$id"
        // a server's file address can be long (its name, escaped): its key is short, and the same on every phone
        Source.SERVER -> "rms:" + digest(id)
    }
    val lossless: Boolean get() = source == Source.ARCHIVE

    /** What the player opens: the saved copy, the Archive's file, or "yt:ID" (found when it starts). */
    val playUri: String get() = file ?: if (source == Source.YOUTUBE) "yt:$id" else id

    fun byline(): String = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject()
        .put("source", when (source) { Source.ARCHIVE -> "archive"; Source.YOUTUBE -> "youtube"; Source.SERVER -> "server" })
        .put("id", id).put("title", title).put("artist", artist).put("album", album)
        .put("seconds", seconds).put("art", art).put("format", format)
        .put("file", file ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): Track? {
            val source = when (o.optString("source")) {
                "archive" -> Source.ARCHIVE
                "youtube" -> Source.YOUTUBE
                "server" -> Source.SERVER
                else -> return null
            }
            val id = o.optString("id").ifEmpty { return null }
            fun opt(k: String) = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
            return Track(source, id, o.optString("title"), o.optString("artist"), o.optString("album"), o.optInt("seconds"), o.optString("art"), o.optString("format"), opt("file"))
        }
    }
}

/** The first 16 hex digits of [text]'s SHA-256 (a short name that stays the same). */
private fun digest(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

/** Lowercase letters and digits only, without bracketed extras ("(Remastered)"). */
fun plain(text: String): String {
    var depth = 0
    val out = StringBuilder()
    for (c in fold(text)) {
        when {
            c == '(' || c == '[' -> depth++
            c == ')' || c == ']' -> depth = maxOf(depth - 1, 0)
            depth == 0 && c.isLetterOrDigit() -> out.append(c.lowercaseChar())
        }
    }
    return out.toString()
}

/** Accents aside: "Ñino" -> "Nino". */
fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

/** Every word typed is in the song's title, artist or album (accents, case and spaces aside). */
fun matches(t: Track, query: String): Boolean {
    val hay = fold("${t.title} ${t.artist} ${t.album}").lowercase()
    val joined = hay.filter { it.isLetterOrDigit() }
    return fold(query).lowercase().split(Regex("\\s+")).all { w ->
        val word = w.filter { it.isLetterOrDigit() }
        word.isEmpty() || hay.contains(word) || joined.contains(word)
    }
}

/** 245 -> "4:05". */
fun clock(seconds: Long): String {
    val s = maxOf(seconds, 0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** "A feat. B & C, D" -> [A, B, C, D]. */
fun splitCredit(credit: String): List<String> {
    var parts = listOf(credit)
    for (sep in listOf(" feat. ", " ft. ", " featuring ", " with ", " x ", " X ", " & ", ", ", " and ")) {
        parts = parts.flatMap { it.split(sep) }
    }
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
}

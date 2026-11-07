package io.github.radianceadm.music.report

import android.content.Context
import android.os.Build
import io.github.radianceadm.music.update.Updates
import org.json.JSONObject
import java.util.UUID

/**
 * Report a Problem (owner 2026-11-02, chosen in a dialog; the computer has the same):
 * the person sees exactly what will be sent, may add a note, and presses Send.
 * Nothing leaves without that press. It goes to admin.radianceph.com
 * (POST /api/music/reports, RadianceADM/radianceph#43).
 *
 * The phone keeps no log file of its own: what it wrote to Android's log is read
 * back (an app may read its own lines, crashes included), newest part only, with
 * email addresses taken out.
 */
object Report {
    /** The admin's limits (radianceph#43): the note in characters, the log in UTF-8 bytes (its own is 204,800), the system in characters. */
    const val MOST_NOTE = 2000
    const val MOST_LOG = 200_000
    const val MOST_SYSTEM = 500

    /** Sent instead of an empty log (the admin needs one). */
    const val NO_LOG = "(the app's log is empty)\n"

    /** What will be sent, shown in full before Send. */
    data class What(
        val version: String,
        val system: String,
        val install: String,
        val log: String,
        val platform: String = "android",
    ) {
        /** The body the admin takes (an empty note left out). */
        fun body(note: String): JSONObject {
            val j = JSONObject()
                .put("platform", platform)
                .put("version", version)
                .put("system", system.take(MOST_SYSTEM))
                .put("log", log.ifBlank { NO_LOG })
                .put("install", install)
            val n = note.trim().take(MOST_NOTE)
            if (n.isNotEmpty()) j.put("note", n)
            return j
        }

        /** As shown in the sheet. */
        fun shown(): String = "Version: $version\nInstall: $platform\nSystem: $system\nInstall ID: $install (made once, at random)\n\n" +
            "The app's recent log (email addresses taken out):\n\n" + log.ifBlank { "(empty)" }
    }

    /** Everything but the note (a worker thread: it reads the log). */
    fun gather(context: Context): What = What(
        version = Updates.current(context),
        system = "Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}",
        install = installId(context),
        log = newest(scrub(ownLog()), MOST_LOG),
    )

    /** A random id made once for this install (no personal data). */
    fun installId(context: Context): String {
        val prefs = context.getSharedPreferences("report", Context.MODE_PRIVATE)
        prefs.getString("install", null)?.takeIf { it.length == 36 }?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("install", id).apply()
        return id
    }

    /**
     * The app's own lines in Android's log (an app sees only its own), newest last: Info and above, as the app logs
     * (android#30: a realme phone's screen drawing, D/BufferQueueProducer 60 times a second, filled the 4000 lines).
     */
    internal val LOGCAT = listOf("logcat", "-d", "-v", "time", "-t", "4000", "*:I")

    private fun ownLog(): String = runCatching {
        val p = ProcessBuilder(LOGCAT).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }.also { p.waitFor() }
    }.getOrDefault("")

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+""")

    /** Email addresses → "(email)". */
    fun scrub(text: String): String = EMAIL.replace(text, "(email)")

    /** The last [most] UTF-8 bytes of [text] (a song title in Japanese takes 3 a letter), starting at a whole line. */
    fun newest(text: String, most: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= most) return text
        var from = bytes.size - most
        while (from < bytes.size && (bytes[from].toInt() and 0xC0) == 0x80) from++ // not inside a letter
        val tail = String(bytes, from, bytes.size - from, Charsets.UTF_8)
        val nl = tail.indexOf('\n')
        return if (nl >= 0) tail.substring(nl + 1) else tail
    }
}

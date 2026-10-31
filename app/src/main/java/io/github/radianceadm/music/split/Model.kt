package io.github.radianceadm.music.split

import android.content.Context
import io.github.radianceadm.music.net.Http
import io.github.radianceadm.music.net.fail
import okhttp3.OkHttpClient
import java.io.File
import java.net.SocketException
import java.security.MessageDigest

/**
 * The splitter's model: Demucs (MIT) exported to ONNX by StemSplitio (MIT),
 * weights stored as fp16. Not inside the app (that made it 208 MB): it's
 * downloaded the first time the app opens (owner, 2026-10-31: "download on
 * first open ng app" / "setup"), checked to be exactly the published file, and
 * kept in the app's own files. That file is the one place it lives.
 */
object Model {
    const val URL = "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx"
    const val SIZE = 165_612_636L
    const val SHA256 = "d05c269d0178d2a72ad484b10b11dd370193fc923201c3b27a99f848745db70a"
    private const val NAME = "htdemucs_fp16weights.onnx"

    /** The model, whole and checked (only ever put here after its check). */
    fun file(context: Context) = File(File(context.filesDir, "splitter"), NAME)

    /** The setup is due: the model isn't here yet. */
    fun due(context: Context) = due(file(context))
    fun due(file: File, size: Long = SIZE) = file.length() != size

    /** Where it downloads to, going on from there after a stop. */
    fun partOf(file: File) = File(file.path + ".part")

    /**
     * Gets the model into [file]: downloads it into a .part beside it (going
     * on from what an earlier try left), checks it's exactly the published file
     * (its SHA-256), and only then puts it in place. A file that doesn't match
     * is thrown away. [progress]: bytes so far; [checking]: the download's
     * done, it's being checked.
     */
    fun get(
        file: File,
        url: String = URL,
        size: Long = SIZE,
        sha256: String = SHA256,
        client: OkHttpClient = Http.client,
        progress: (Long) -> Unit = {},
        checking: () -> Unit = {},
    ): File {
        if (!due(file, size)) return file
        val dir = file.parentFile!!.apply { mkdirs() }
        file.delete() // a wrong-sized one (never put here by this)
        val part = partOf(file)
        if (part.length() > size) part.delete() // more than the whole: not this file
        if (part.length() < size) {
            // room for the rest (and a little to spare), else say so before it fills the phone
            if (dir.usableSpace < size - part.length() + 20_000_000) fail("Your phone is full. Radiance Music needs ${size / 1_000_000} MB free for the splitter. Free up some space and try again.")
            Http.resume(url, part, client, progress)
        }
        val got = part.length()
        // short: the connection ended early (the next try goes on from there); long: not this file
        if (got < size) throw SocketException("the download ended at $got of $size bytes")
        if (got > size) {
            part.delete()
            fail("The splitter didn't download right. Try again.", "$got bytes, not $size")
        }
        checking()
        val sum = sha256(part)
        if (!sum.equals(sha256, ignoreCase = true)) {
            part.delete()
            fail("The splitter didn't download right. Try again.", "sha256 $sum")
        }
        if (!part.renameTo(file)) fail("Couldn't keep the splitter. Try again.")
        return file
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val b = ByteArray(1 shl 20)
            while (true) {
                val n = s.read(b)
                if (n < 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

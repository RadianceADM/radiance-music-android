package io.github.radianceadm.music.split

import android.content.Context
import io.github.radianceadm.music.net.Http
import io.github.radianceadm.music.net.fail
import okhttp3.OkHttpClient
import java.io.File
import java.net.SocketException
import java.security.MessageDigest

/**
 * The splitter's models: Spleeter's two (vocals and accompaniment; Deezer,
 * MIT) as ONNX, exported by sherpa-onnx (Apache 2.0), weights stored as fp16,
 * 19.7 MB each. Not inside the app: downloaded the first time the app opens
 * (owner, 2026-10-31: "download on first open ng app" / "setup"), each checked
 * to be exactly the published file, and kept in the app's own files. Pinned to
 * one commit of the Hugging Face copy, so the files can't change under us.
 * Until 0.5.16 it was Demucs (166 MB, four parts); that file is deleted.
 */
object Model {
    /** One of the files: its name here, where it comes from, its size and SHA-256. */
    data class Part(val name: String, val url: String, val size: Long, val sha256: String)

    private const val REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-spleeter-2stems-fp16/resolve/93ba771920ade509f8cbd6825b1a90856c797e08"
    val VOCALS = Part("vocals.fp16.onnx", "$REPO/vocals.fp16.onnx", 19_681_017L, "24cef84aedcd1fe87c0b743ef3370ad34dc1fabf6c9014d6128a75a538c7b668")
    val MUSIC = Part("accompaniment.fp16.onnx", "$REPO/accompaniment.fp16.onnx", 19_681_024L, "d14cea55793cc531a5875f5f4da08207d1c5ab9292e8e0099a104eecb014fcc0")
    val FILES = listOf(VOCALS, MUSIC)
    /** Both together, as the setup shows it. */
    val SIZE = FILES.sumOf { it.size }

    /** The old splitter's file (Demucs, until 0.5.16). */
    private const val OLD = "htdemucs_fp16weights.onnx"

    fun dir(context: Context) = File(context.filesDir, "splitter")

    /** One of the files, whole and checked (only ever put here after its check). */
    fun file(context: Context, part: Part) = File(dir(context), part.name)

    /** The setup is due: a file isn't here yet. */
    fun due(context: Context) = FILES.any { due(file(context, it), it.size) }
    fun due(file: File, size: Long) = file.length() != size

    /** How much is here so far (whole files and what downloads left), for the setup's progress. */
    fun have(context: Context): Long = FILES.sumOf { p ->
        val f = file(context, p)
        if (!due(f, p.size)) p.size else partOf(f).length().coerceAtMost(p.size)
    }

    /** Where it downloads to, going on from there after a stop. */
    fun partOf(file: File) = File(file.path + ".part")

    /** The old splitter's file goes (166 MB back on the phone). */
    fun forgetOld(context: Context) {
        File(dir(context), OLD).delete()
        partOf(File(dir(context), OLD)).delete()
    }

    /** Gets both files in turn ([get]); [progress]: bytes so far of [SIZE]. */
    fun getAll(context: Context, client: OkHttpClient = Http.client, progress: (Long) -> Unit = {}, checking: () -> Unit = {}) {
        var before = 0L
        for (p in FILES) {
            get(file(context, p), p.url, p.size, p.sha256, client, { progress(before + it) }, checking)
            before += p.size
        }
    }

    /**
     * Gets the model into [file]: downloads it into a .part beside it (going
     * on from what an earlier try left), checks it's exactly the published file
     * (its SHA-256), and only then puts it in place. A file that doesn't match
     * is thrown away. [progress]: bytes so far; [checking]: the download's
     * done, it's being checked.
     */
    fun get(
        file: File,
        url: String,
        size: Long,
        sha256: String,
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

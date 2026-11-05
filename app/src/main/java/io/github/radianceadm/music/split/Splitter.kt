package io.github.radianceadm.music.split

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.radianceadm.music.model.Track
import io.github.radianceadm.music.net.fail
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Splits a song in two on this phone: its vocals and its music (Spleeter, see
 * Spleeter.kt), for the Mixer's No vocals (karaoke) and Vocals only. Owner,
 * 2026-11-04/05 (#43): "sa android bawal na ang 4 split model. karaoke mode
 * lang or vocals only"; Demucs' four parts took as long as the song itself.
 * The song goes through it a block (about 12 s) at a time and the parts are
 * saved as they come, so a long song never needs much memory. Songs with four
 * parts (split on the computer or a server, or here before) keep them.
 */
object Splitter {
    private const val TAG = "radiance-split"

    const val RATE = 44100
    /** What a split here makes: the song's two halves. */
    val PARTS = Spleeter.PARTS
    /** A song split in four (on the computer, a server, or here before 0.5.17). */
    val FOUR = listOf("drums", "bass", "other", "vocals")
    /** Every part a song can have here. */
    private val ALL = (FOUR + PARTS + listOf("guitar", "piano")).distinct()

    /**
     * One of the models (Model.kt: downloaded the first time the app opens,
     * checked), mapped read-only from its file: nothing copied into memory,
     * and the mapping outlives the file's opening.
     */
    private fun model(context: Context, part: Model.Part): ByteBuffer {
        val f = Model.file(context, part)
        if (Model.due(f, part.size)) fail("The splitter isn't ready yet. It gets ready when you're back online.")
        return FileInputStream(f).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
    }

    /** Where the phone's own splits go: `<song>.<part>.m4a`. */
    fun dir(context: Context) = File(context.getExternalFilesDir(null), "Splits")
    private fun safe(key: String) = key.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    fun partFile(context: Context, key: String, part: String) = File(dir(context), "${safe(key)}.$part.m4a")

    /** The song's parts here (split here, or sent from another device): part to its file. */
    fun parts(context: Context, key: String): Map<String, File> {
        val prefix = "${safe(key)}."
        val files = dir(context).listFiles { f -> f.name.startsWith(prefix) } ?: return emptyMap()
        return ALL.mapNotNull { p -> files.firstOrNull { it.name.startsWith("$prefix$p.") && !it.name.endsWith(".part") }?.let { p to it } }.toMap()
    }

    /** Where a part sent from another device goes ([ext]: its own kind of file). */
    fun partFileFor(context: Context, key: String, part: String, ext: String) = File(dir(context), "${safe(key)}.$part.$ext")

    /** What the splitter is doing, for the screen and the notification. */
    data class Step(val what: String, val done: Float, val secondsLeft: Int? = null)

    /**
     * Splits [t] (its saved file) with [threads] of the phone's cores. The
     * two parts appear only when both are done (a stopped split leaves nothing).
     */
    fun split(context: Context, t: Track, threads: Int, say: (Step) -> Unit, cancelled: () -> Boolean) {
        val song = t.file ?: fail("The song isn't saved on this phone yet.")
        val work = File(context.cacheDir, "split").apply { deleteRecursively(); mkdirs() }
        val out = dir(context).apply { mkdirs() }
        try {
            say(Step("Getting ready", 0f))
            val delay = Pcm.encoderDelay(context, work)
            val decoded = Pcm.decode(context, Uri.parse(song), File(work, "song.f32")) { say(Step("Reading the song", it * 0.5f / 100)) }
            val audio = Pcm.resample(decoded, RATE, File(work, "song44.f32")) { say(Step("Reading the song", 0.005f + it * 0.5f / 100)) }
            if (audio !== decoded) decoded.file.delete()
            if (cancelled()) return
            val x = audio.map()
            val total = audio.frames.toInt()

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(threads.coerceIn(1, Runtime.getRuntime().availableProcessors()))
                setInterOpNumThreads(1)
                addConfigEntry("session.intra_op.allow_spinning", "0") // cores rest between pieces (battery, heat)
            }
            val began = System.nanoTime()
            say(Step("Loading the splitter", 0.01f))
            val vocals = env.createSession(model(context, Model.VOCALS), opts)
            val music = try { env.createSession(model(context, Model.MUSIC), opts) } catch (e: Throwable) { vocals.close(); throw e }
            Log.i(TAG, "models loaded in ${(System.nanoTime() - began) / 1_000_000} ms, $threads threads")

            val encoders = PARTS.associateWith { Pcm.Encoder(File(work, "$it.m4a"), RATE) }
            var shown = 0.01f
            val skip = IntArray(PARTS.size) { delay.coerceAtLeast(0) } // the encoder's own lead-in, taken off the start
            if (delay < 0) encoders.values.forEach { it.write(FloatArray(-2 * delay), 0, -delay) }
            try {
                Spleeter.run(env, vocals, music, x, total, { p, buf, frames ->
                    val from = minOf(skip[p], frames)
                    skip[p] -= from
                    encoders.getValue(PARTS[p]).write(buf, 2 * from, frames - from)
                }, { done, of, secondsLeft ->
                    Log.i(TAG, "block $done/$of")
                    shown = 0.01f + 0.98f * done / of
                    say(Step("Splitting", shown, secondsLeft + 3))
                }, cancelled, { Guard.rest(context, { why -> say(Step(why, shown)) }, cancelled) })
                if (cancelled()) {
                    encoders.values.forEach { runCatching { it.close() } }
                    return
                }
                encoders.values.forEach { it.finish() }
            } catch (e: Throwable) {
                encoders.values.forEach { runCatching { it.close() } }
                throw e
            } finally {
                vocals.close()
                music.close()
                opts.close()
            }
            Log.i(TAG, "split “${t.title}” in ${"%.0f".format((System.nanoTime() - began) / 1e9)} s")
            // both at once: a song is split, or it isn't
            for (name in PARTS) File(work, "$name.m4a").copyTo(partFile(context, t.key, name), overwrite = true)
            say(Step("Done", 1f, 0))
        } finally {
            work.deleteRecursively()
        }
    }

    /** Split here (in two), or it has its four parts: nothing to split. */
    fun isSplit(context: Context, key: String) = parts(context, key).keys.let { it.containsAll(PARTS) || it.containsAll(FOUR) }
}

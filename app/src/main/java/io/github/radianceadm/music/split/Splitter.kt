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
    // where each step starts on this split's bar (reading ~15%, the split itself the rest)
    private const val READ_FROM = 0.02f
    private const val RESAMPLE_FROM = 0.06f
    private const val LOAD_FROM = 0.14f
    private const val SPLIT_FROM = 0.15f

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

    /**
     * What the splitter is doing, for the screen and the notification: [done] so far, and
     * [next] where this step ends (the bar glides toward it meanwhile, never stuck).
     */
    data class Step(val what: String, val done: Float, val secondsLeft: Int? = null, val next: Float = done)

    /** A song split ahead (not the one playing: the next one, a liked one) uses this many cores, so the music doesn't skip. */
    const val LIGHT_CORES = 1

    /**
     * The cores a block of [threads]-core split uses (owner, 2026-11-07: "kung iisplit mo na agad ang next song yung
     * pinaka mahinang setting lang muna pero kapag sya na ang now playing tsaka sundin ang default core count"): the
     * song playing gets the cores set in Settings, a song split ahead [LIGHT_CORES]; while the phone is warm, at most
     * [Guard.WARM_CORES].
     */
    fun cores(playing: Boolean, warm: Boolean, threads: Int): Int {
        val base = if (playing) threads else LIGHT_CORES
        return (if (warm) minOf(base, Guard.WARM_CORES) else base).coerceAtLeast(1)
    }

    /**
     * Splits [t] (its saved file) with [threads] of the phone's cores. The
     * two parts appear only when both are done (a stopped split leaves nothing).
     */
    fun split(context: Context, t: Track, threads: Int, say: (Step) -> Unit, cancelled: () -> Boolean, from: Float = 0f, playing: () -> Boolean = { true }) {
        // this split's own 0..1 on the bar after [from] (the song's download, when it waited for that)
        fun at(p: Float) = from + (1f - from) * p
        fun step(what: String, p: Float, next: Float = p, left: Int? = null) = say(Step(what, at(p), left, at(maxOf(p, next))))
        val song = t.file ?: fail("The song isn't saved on this phone yet.")
        val work = File(context.cacheDir, "split").apply { deleteRecursively(); mkdirs() }
        val out = dir(context).apply { mkdirs() }
        try {
            step("Getting ready", 0f, READ_FROM)
            val delay = Pcm.encoderDelay(context, work)
            val decoded = Pcm.decode(context, Uri.parse(song), File(work, "song.f32")) { step("Reading the song", READ_FROM + it * (RESAMPLE_FROM - READ_FROM), RESAMPLE_FROM) }
            // at the splitter's rate on all the cores it may use (the same sound, sooner)
            val audio = Pcm.resample(decoded, RATE, File(work, "song44.f32"), cores(playing(), false, threads)) { step("Reading the song", RESAMPLE_FROM + it * (LOAD_FROM - RESAMPLE_FROM), LOAD_FROM) }
            if (audio !== decoded) decoded.file.delete()
            if (cancelled()) return
            val x = audio.map()
            val total = audio.frames.toInt()

            val env = OrtEnvironment.getEnvironment()
            fun options(n: Int) = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(n.coerceIn(1, Runtime.getRuntime().availableProcessors()))
                setInterOpNumThreads(1)
                addConfigEntry("session.intra_op.allow_spinning", "0") // cores rest between pieces (battery, heat)
            }
            // the models on as many cores as this block wants (cores(): the song playing gets the set cores, a song
            // split ahead one, fewer while the phone is warm), each count's pair made the first time it's needed
            val made = HashMap<Int, Pair<OrtSession, OrtSession>>()
            val optsMade = mutableListOf<OrtSession.SessionOptions>()
            fun sessions(n: Int): Pair<OrtSession, OrtSession> = made.getOrPut(n) {
                val o = options(n).also { optsMade += it }
                val v = env.createSession(model(context, Model.VOCALS), o)
                v to try { env.createSession(model(context, Model.MUSIC), o) } catch (e: Throwable) { v.close(); throw e }
            }
            val began = System.nanoTime()
            var warm = false
            var using = cores(playing(), warm, threads)
            step("Loading the splitter", LOAD_FROM, SPLIT_FROM + 0.04f)
            try {
                sessions(using)
                Log.i(TAG, "models loaded in ${(System.nanoTime() - began) / 1_000_000} ms, $using threads")
                val models = {
                    val n = cores(playing(), warm, threads)
                    if (n != using) Log.i(TAG, "on $n cores now (${if (warm) "the phone is warm" else if (playing()) "it's playing" else "split ahead"})")
                    using = n
                    sessions(n)
                }
                val encoders = PARTS.associateWith { Pcm.Encoder(File(work, "$it.m4a"), RATE) }
                var shown = at(SPLIT_FROM)
                val skip = IntArray(PARTS.size) { delay.coerceAtLeast(0) } // the encoder's own lead-in, taken off the start
                if (delay < 0) encoders.values.forEach { it.write(FloatArray(-2 * delay), 0, -delay) }
                try {
                    Spleeter.run(env, models, x, total, { p, buf, frames ->
                        val from = minOf(skip[p], frames)
                        skip[p] -= from
                        encoders.getValue(PARTS[p]).write(buf, 2 * from, frames - from)
                    }, { done, of, secondsLeft ->
                        Log.i(TAG, "block $done/$of")
                        val p = SPLIT_FROM + (0.99f - SPLIT_FROM) * done / of
                        shown = at(p)
                        val what = when {
                            using >= threads -> "Splitting"
                            warm -> "Splitting, slower while the phone is warm"
                            else -> "Splitting ahead, gently"
                        }
                        step(what, p, SPLIT_FROM + (0.99f - SPLIT_FROM) * minOf(done + 1, of) / of, secondsLeft + 3)
                    }, cancelled, {
                        warm = Guard.rest(context, { why -> say(Step(why, shown)) }, cancelled)
                    })
                    if (cancelled()) {
                        encoders.values.forEach { runCatching { it.close() } }
                        return
                    }
                    encoders.values.forEach { it.finish() }
                } catch (e: Throwable) {
                    encoders.values.forEach { runCatching { it.close() } }
                    throw e
                }
            } finally {
                made.values.forEach { (v, m) -> v.close(); m.close() }
                optsMade.forEach { it.close() }
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

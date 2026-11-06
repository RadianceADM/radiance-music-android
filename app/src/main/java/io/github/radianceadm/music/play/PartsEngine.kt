package io.github.radianceadm.music.play

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * A split song played as ONE stream: the song's own file and its parts
 * (drums, bass, other, vocals; or music, vocals) decoded here in step, at the
 * song's own rate, mixed, out through one AudioTrack (owner, 2026-11-05: "kala
 * ko ba sabay na sila nag peplay then inaadjust nalang yung sound"). The whole
 * song is the song's own file, untouched; karaoke is its parts; a switch
 * glides from one to the other in [GLIDE_S] and nothing stops, seeks or
 * reloads (as the desktop's 0.5.25 StemPipe). Without the song's own file on
 * the phone (a server's stream): the parts alone. Parts turned down keep the
 * song as loud (Limiter.makeup), and nothing goes over full scale.
 */
class PartsEngine(parts: Map<String, Source>, gains: Map<String, Float>, song: Source?, blend: Float) {
    /** Where a stream's sound is. */
    fun interface Source { fun open(ex: MediaExtractor) }

    private class Stream(val name: String, source: Source, gain: Float) {
        private val extractor = MediaExtractor()
        private val codec: MediaCodec
        var rate: Int
        var channels: Int
        /** Its decoder said what it gives (the rate is sure). */
        var known = false
        val durationUs: Long
        private var float = false
        private var inputDone = false
        var eos = false
        private var skipUntilUs = -1L
        private var tmp = FloatArray(1 shl 14)
        private var stereo = FloatArray(1 shl 14)
        /** Its sound at the engine's rate, interleaved stereo. */
        private var buf = FloatArray(1 shl 16)
        private var start = 0
        private var end = 0
        var resampler: Resampler? = null
        /** Frames still to drop (to line it up with the song). */
        private var dropping = 0

        @Volatile var gain = gain
        var now = gain
        /** Its loudness (mean power over the last seconds, before its volume) and how much was heard. */
        var power = 0.0
        var heard = 0.0

        init {
            source.open(extractor)
            val i = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(i)
            val format = extractor.getTrackFormat(i)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT) // float when the decoder can
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
        }

        fun available() = (end - start) / 2

        /** Nothing more will come and all of it was taken. */
        fun done() = eos && available() == 0

        private fun room(samples: Int) {
            if (start > 0 && end + samples > buf.size) {
                System.arraycopy(buf, start, buf, 0, end - start)
                end -= start
                start = 0
            }
            if (end + samples > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, end + samples))
        }

        /** [frames] of stereo at its own rate: to the engine's. */
        private fun out(src: FloatArray, frames: Int) {
            val r = resampler
            if (r == null) {
                room(2 * frames)
                System.arraycopy(src, 0, buf, end, 2 * frames)
                end += 2 * frames
            } else {
                r.push(src, 0, frames)
                drain(r, false)
            }
            settle()
        }

        private fun drain(r: Resampler, last: Boolean) {
            while (true) {
                room(2 * 4096)
                val got = r.pull(buf, end / 2, 4096, last)
                end += 2 * got
                if (got < 4096) break
            }
        }

        private fun settle() {
            val d = minOf(dropping, available())
            start += 2 * d
            dropping -= d
        }

        /** Up to [frames] stereo frames into [dst]; how many. */
        fun take(dst: FloatArray, frames: Int): Int {
            val got = minOf(frames, available())
            System.arraycopy(buf, start, dst, 0, 2 * got)
            start += 2 * got
            return got
        }

        /** It was late by [frames]: that many dropped. Negative: it was early, silence goes first. */
        fun shift(frames: Int) {
            if (frames > 0) {
                dropping += frames
                settle()
            } else if (frames < 0) {
                val add = -frames
                val old = buf.copyOfRange(start, end)
                if (buf.size < old.size + 2 * add) buf = FloatArray(old.size + 2 * add + (1 shl 14))
                java.util.Arrays.fill(buf, 0, 2 * add, 0f)
                System.arraycopy(old, 0, buf, 2 * add, old.size)
                start = 0
                end = old.size + 2 * add
            }
        }

        fun seek(us: Long) {
            // a little before, so the decoder has settled at the spot (Opus, AAC)
            extractor.seekTo(maxOf(0L, us - PREROLL_US), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.flush()
            start = 0
            end = 0
            inputDone = false
            eos = false
            skipUntilUs = us
            heard = 0.0
            dropping = 0
            resampler?.reset()
        }

        /** Decoded until there are [frames] (or the end); a bounded number of rounds. */
        fun fill(frames: Int, info: MediaCodec.BufferInfo) {
            var tries = 0
            while (available() < frames && !eos && tries++ < 400) decode(info)
        }

        /** One round of decoding: a packet in, decoded sound out (if ready). */
        fun decode(info: MediaCodec.BufferInfo) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(2_000)
                if (ii >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(ii)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 2_000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                known = true
                return
            }
            if (oi < 0) return
            known = true
            val o = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
            o.position(info.offset)
            o.limit(info.offset + info.size)
            val count = if (float) info.size / 4 else info.size / 2
            if (tmp.size < count) tmp = FloatArray(count)
            if (float) o.asFloatBuffer().get(tmp, 0, count) else {
                val s = o.asShortBuffer()
                for (i in 0 until count) tmp[i] = s.get(i) / 32768f
            }
            var frames = count / channels
            var from = 0
            if (skipUntilUs >= 0 && frames > 0) {
                // the seek landed before the spot: the sound before it is dropped
                val endUs = info.presentationTimeUs + frames * 1_000_000L / rate
                if (endUs <= skipUntilUs) frames = 0 else {
                    from = minOf((((skipUntilUs - info.presentationTimeUs).coerceAtLeast(0)) * rate / 1_000_000L).toInt(), frames)
                    frames -= from
                    skipUntilUs = -1
                }
            }
            if (frames > 0) {
                if (stereo.size < 2 * frames) stereo = FloatArray(2 * frames)
                for (f in 0 until frames) {
                    val i = (from + f) * channels
                    stereo[2 * f] = tmp[i]
                    stereo[2 * f + 1] = if (channels > 1) tmp[i + 1] else tmp[i]
                }
                out(stereo, frames)
            }
            codec.releaseOutputBuffer(oi, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                eos = true
                resampler?.let { drain(it, true); settle() }
            }
        }

        fun release() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
    }

    private val parts = parts.map { (name, s) -> Stream(name, s, gains[name] ?: 1f) }
    private val song = song?.let { Stream("song", it, 1f) }
    private val all = listOfNotNull(this.song) + this.parts
    /** The song's own file plays here too: the whole song is heard from it. */
    val hasSong get() = song != null
    private val lock = Object()
    @Volatile private var blendTo = if (song != null) blend.coerceIn(0f, 1f) else 0f
    @Volatile private var fadeGoal = 1f
    @Volatile private var fadeS = START_FADE_S
    @Volatile private var running = true
    @Volatile private var playing = false
    @Volatile private var finishing = 0L
    @Volatile private var seekMs = -1L
    @Volatile private var startMs = 0L
    @Volatile private var rate = 44100
    /** Parts vs song, as compared off the sound thread: for the stretch since seek number [gen], the lag and how sure. */
    private class Lined(val gen: Int, val lag: Int, val score: Double)
    private val found = java.util.concurrent.atomic.AtomicReference<Lined?>(null)
    /** One more with each seek: a comparison from before it is no good after it. */
    @Volatile private var alignGen = 0
    /** Its first sound went out (after the start). */
    @Volatile var started = false
        private set
    @Volatile private var track: AudioTrack? = null
    private val thread = Thread({ loop() }, "radiance-mixer")
    private val stamp = AudioTimestamp()

    /** Starts at [atMs] into the song, playing or paused. */
    fun start(atMs: Long, playing: Boolean) {
        this.playing = playing
        seekMs = atMs
        thread.start()
    }

    /** Its sound to [goal] (0 silent, 1 heard) over [seconds]. */
    fun fadeTo(goal: Float, seconds: Double) {
        fadeS = maxOf(seconds, 0.001)
        fadeGoal = goal.coerceIn(0f, 1f)
    }

    /** 1: the song's own file; 0: its parts (as their volumes say). Glides there. */
    fun blend(to: Float) {
        if (song != null) blendTo = to.coerceIn(0f, 1f)
    }

    /** How far ahead of what's heard it writes (its buffer), in ms. */
    fun aheadMs(): Long = track?.let { it.bufferSizeInFrames * 1000L / maxOf(rate, 1) } ?: 0L

    fun setGain(name: String, g: Float) {
        parts.firstOrNull { it.name == name }?.gain = g.coerceIn(0f, 1f)
    }

    fun setPlaying(on: Boolean) {
        playing = on
        synchronized(lock) { lock.notifyAll() }
    }

    fun seek(ms: Long) {
        seekMs = ms
        synchronized(lock) { lock.notifyAll() }
    }

    val durationMs: Long get() = ((song ?: parts.firstOrNull())?.durationUs ?: 0L) / 1000

    /** Where the song is, as heard. */
    fun positionMs(): Long {
        val t = track ?: return startMs
        val r = maxOf(rate, 1)
        return runCatching {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING && t.getTimestamp(stamp)) {
                startMs + (stamp.framePosition + (System.nanoTime() - stamp.nanoTime) * r / 1_000_000_000L) * 1000L / r
            } else startMs + t.playbackHeadPosition.toLong() * 1000L / r
        }.getOrDefault(startMs)
    }

    /** In its last seconds (the player moved on by itself, not skipped). */
    fun nearEnd(): Boolean = durationMs > 0 && positionMs() > durationMs - NEAR_END_MS

    /** Plays to its end (what's left of it, at most a few seconds), then lets go by itself. */
    fun finish() {
        finishing = System.nanoTime()
        synchronized(lock) { lock.notifyAll() }
    }

    fun release() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        runCatching { thread.join(1500) }
    }

    private fun makeTrack(rate: Int): AudioTrack {
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min * 2, CHUNK * 2 * 4 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun loop() {
        // as the player's own: sound first, so a split or a busy screen never leaves a gap
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        val info = MediaCodec.BufferInfo()
        val n = CHUNK * 2
        val mix = FloatArray(n)
        val part = FloatArray(n)
        val partsMix = FloatArray(n)
        val own = FloatArray(n)
        var limiter: Limiter? = null
        var effects: EffectsChain? = null
        var fade = 0f // in after each start or seek
        var blendNow = blendTo
        var makeup = 1f
        var underruns = 0
        // lining the parts up with the song: the first loud stretch of both, compared off this thread
        val songMono = FloatArray(ALIGN_FRAMES)
        val partsMono = FloatArray(ALIGN_FRAMES)
        var collected = 0
        var tries = 0
        var waitUntil = 0L
        var lag: Int? = null
        /** How far the parts were moved against the song since the streams last started (each seek starts them again). */
        var total = 0
        var comparing = false
        try {
            // the decoders say their rates first: the engine plays at the song's own, the parts come to it
            for (s in all) {
                var t = 0
                while (!s.known && !s.eos && s.available() == 0 && t++ < 400) s.decode(info)
            }
            rate = (song ?: parts.first()).rate
            for (s in all) if (s.rate != rate) s.resampler = Resampler(s.rate, rate)
            if (all.any { it.resampler != null }) Log.i(TAG, "parts at ${parts.first().rate} Hz, the song at $rate Hz: the parts follow the song's")
            val step = 1f / (GLIDE_S * rate).toFloat()
            val kMakeup = 1f - exp(-(CHUNK.toDouble() / rate) / MAKEUP_GLIDE_S).toFloat()
            val dt = CHUNK.toDouble() / rate
            while (running) {
                val to = seekMs
                if (to >= 0) {
                    seekMs = -1
                    track?.pause()
                    track?.flush()
                    all.forEach { it.seek(to * 1000) }
                    // a seek lands each stream a little differently (the song's file came 220 ms late after one, in step
                    // from the start): as far off as last time, most likely, and checked again (owner test, 2026-11-06)
                    if (total != 0) parts.forEach { it.shift(total) }
                    alignGen++
                    found.set(null)
                    comparing = false
                    lag = null
                    tries = 0
                    waitUntil = 0L
                    effects?.reset()
                    startMs = to
                    fade = 0f
                    if (fadeGoal > 0f) fadeS = START_FADE_S
                    collected = 0
                }
                for (s in all) s.fill(CHUNK, info)
                val t = track ?: makeTrack(rate).also {
                    track = it
                    limiter = Limiter(2, rate)
                    effects = EffectsChain(rate, 2)
                }
                val over = all.all { it.done() }
                val ending = finishing
                if (ending != 0L && (over || System.nanoTime() - ending > FINISH_MS * 1_000_000L)) {
                    // played to its end: what it wrote is heard, then it goes
                    Thread.sleep(aheadMs() + 50)
                    break
                }
                if (!playing) {
                    t.pause()
                    synchronized(lock) { lock.wait(250) }
                    continue
                }
                if (over) {
                    // the song's over: the player moves on (and lets this go)
                    synchronized(lock) { lock.wait(250) }
                    continue
                }
                // the parts, each gliding to its volume
                java.util.Arrays.fill(partsMix, 0f)
                val measure = song != null && lag == null && !comparing && collected < ALIGN_FRAMES && System.nanoTime() > waitUntil
                if (measure && collected == 0) java.util.Arrays.fill(partsMono, 0f)
                for (p in parts) {
                    val got = p.take(part, CHUNK)
                    var sum = 0.0
                    var g = p.now
                    val goal = p.gain
                    for (f in 0 until got) {
                        g = if (goal > g) minOf(goal, g + step) else maxOf(goal, g - step)
                        val l = part[2 * f]
                        val r = part[2 * f + 1]
                        sum += l * l + r * r
                        partsMix[2 * f] += l * g
                        partsMix[2 * f + 1] += r * g
                    }
                    if (measure) for (f in 0 until minOf(got, ALIGN_FRAMES - collected)) partsMono[collected + f] += part[2 * f] + part[2 * f + 1]
                    if (got < CHUNK) g = if (goal > g) minOf(goal, g + step * (CHUNK - got)) else maxOf(goal, g - step * (CHUNK - got))
                    p.now = g
                    if (got > 0) {
                        p.heard += dt
                        val k = maxOf(dt / p.heard, 1 - exp(-dt / LOUDNESS_S))
                        p.power += (sum / (2 * got) - p.power) * k
                    }
                }
                // the song's own file
                val songGot = song?.take(own, CHUNK) ?: 0
                if (songGot < CHUNK) java.util.Arrays.fill(own, 2 * songGot, n, 0f)
                if (measure) {
                    val k = minOf(CHUNK, ALIGN_FRAMES - collected)
                    for (f in 0 until k) songMono[collected + f] = own[2 * f] + own[2 * f + 1]
                    collected += k
                    if (collected >= ALIGN_FRAMES) {
                        comparing = true
                        val s = songMono.copyOf()
                        val pm = partsMono.copyOf()
                        val g = alignGen
                        Thread({
                            val (l, score) = Align.find(s, pm, ALIGN_MOST) ?: (0 to -1.0)
                            if (alignGen == g) found.set(Lined(g, l, score))
                        }, "radiance-align").start()
                    }
                }
                found.get()?.let { got ->
                    if (got.gen != alignGen) { found.compareAndSet(got, null); return@let } // from before a seek
                    val l = got.lag
                    val score = got.score
                    val bothQuietOne = blendNow >= 1f && blendTo >= 1f || blendNow <= 0f && blendTo <= 0f
                    if (score < ALIGN_SURE) {
                        // too quiet or too unlike to tell: another stretch a little later
                        found.set(null)
                        comparing = false
                        collected = 0
                        if (++tries >= ALIGN_TRIES) lag = 0
                        waitUntil = System.nanoTime() + 3_000_000_000L
                        if (score >= 0) Log.i(TAG, "parts vs the song: not sure (${"%.2f".format(score)} at $l)")
                    } else if (bothQuietOne) {
                        // moved while it isn't heard: the parts when the song plays, the song when the parts do
                        if (blendNow >= 1f) parts.forEach { it.shift(l) } else song?.shift(-l)
                        lag = l
                        total += l
                        found.set(null)
                        comparing = false
                        Log.i(TAG, "parts vs the song: ${l} frames (${"%.1f".format(l * 1000.0 / rate)} ms), match ${"%.2f".format(score)}${if (l != 0) ": lined up" else ""}${if (total != l) " (${total} in all)" else ""}")
                    }
                }
                val target = Limiter.makeup(parts.map { it.power to it.gain })
                val m0 = makeup
                makeup += (target - makeup) * kMakeup
                var b = blendNow
                val bGoal = blendTo
                for (f in 0 until CHUNK) {
                    b = if (bGoal > b) minOf(bGoal, b + step) else maxOf(bGoal, b - step)
                    val m = (m0 + (makeup - m0) * f / CHUNK) * (1f - b)
                    mix[2 * f] = b * own[2 * f] + m * partsMix[2 * f]
                    mix[2 * f + 1] = b * own[2 * f + 1] + m * partsMix[2 * f + 1]
                }
                blendNow = b
                // just the song: its own sound, untouched (no headroom taken)
                val pure = b >= 1f && bGoal >= 1f
                limiter?.process(mix, CHUNK, 1f, if (pure) 1f else Limiter.CEILING)
                effects?.process(mix, CHUNK) // Low cut, High cut, Reverb (as the player's)
                val goal = fadeGoal
                if (fade != goal || goal < 1f) {
                    val fs = 1f / (fadeS * rate).toFloat()
                    for (f in 0 until CHUNK) {
                        fade = if (goal > fade) minOf(goal, fade + fs) else maxOf(goal, fade - fs)
                        mix[2 * f] *= fade
                        mix[2 * f + 1] *= fade
                    }
                }
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                t.write(mix, 0, n, AudioTrack.WRITE_BLOCKING)
                started = true
                // the phone too busy to keep up: a gap was heard (in the log, to find why)
                val under = t.underrunCount
                if (under > underruns) { Log.w(TAG, "the parts ran dry ($under times)"); underruns = under }
            }
        } catch (e: Exception) {
            Log.e(TAG, "the Mixer stopped", e)
        } finally {
            track?.run {
                runCatching { pause() }
                runCatching { flush() }
                release()
            }
            all.forEach { it.release() }
        }
    }

    companion object {
        fun file(f: File) = Source { it.setDataSource(f.absolutePath) }
        fun uri(context: Context, u: Uri) = Source { if (u.scheme == "file") it.setDataSource(u.path!!) else it.setDataSource(context, u, null) }

        private const val TAG = "radiance-mix"
        /** Frames mixed at a time (about 46 ms at 44.1 kHz). */
        private const val CHUNK = 2048
        /** A switch (whole song, karaoke, the singing's level) takes this long (as the desktop's 0.3 s). */
        const val GLIDE_S = 0.3
        /** In after a start or a seek: just enough not to click. */
        private const val START_FADE_S = 0.015
        private const val MAKEUP_GLIDE_S = 0.25
        private const val LOUDNESS_S = 8.0
        private const val PREROLL_US = 100_000L
        private const val NEAR_END_MS = 2500L
        private const val FINISH_MS = 4000L
        /** About 1.4 s of sound compared, the parts up to a quarter second either way. */
        private const val ALIGN_FRAMES = 65536
        private const val ALIGN_MOST = 12000
        private const val ALIGN_SURE = 0.7
        private const val ALIGN_TRIES = 8
    }
}

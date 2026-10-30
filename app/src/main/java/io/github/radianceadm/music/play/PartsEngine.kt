package io.github.radianceadm.music.play

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * A split song's parts (drums, bass, other, vocals) played in step and mixed
 * here, each at its own volume, kept as loud as the whole song (Limiter), out
 * through one audio stream. Moving a volume never stops or reloads anything:
 * it glides there within the next 50 ms.
 */
class PartsEngine(files: Map<String, File>, gains: Map<String, Float>) {
    private class Part(val name: String, file: File, gain: Float) {
        private val extractor = MediaExtractor()
        private val codec: MediaCodec
        var channels = 2
        var rate = 44100
        private var float = false
        private var buf = FloatArray(1 shl 16)
        private var start = 0
        private var end = 0
        private var inputDone = false
        var eos = false
        private var skipUntilUs = -1L
        private var tmp = FloatArray(1 shl 14)

        @Volatile var gain = gain
        var shown = gain
        /** Its loudness (mean power over the last seconds, before its volume) and how much was heard. */
        var power = 0.0
        var heard = 0.0

        init {
            extractor.setDataSource(file.absolutePath)
            val i = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(i)
            val format = extractor.getTrackFormat(i)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT) // float when the decoder can
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
        }

        fun available() = end - start

        private fun push(src: FloatArray, from: Int, n: Int) {
            if (start > 0 && end + n > buf.size) {
                System.arraycopy(buf, start, buf, 0, end - start)
                end -= start
                start = 0
            }
            if (end + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, end + n))
            System.arraycopy(src, from, buf, end, n)
            end += n
        }

        /** Up to [n] stereo samples into [dst] (mono doubled); how many. */
        fun take(dst: FloatArray, n: Int): Int {
            if (channels == 1) {
                val frames = minOf(n / 2, available())
                for (f in 0 until frames) {
                    dst[2 * f] = buf[start + f]
                    dst[2 * f + 1] = buf[start + f]
                }
                start += frames
                return frames * 2
            }
            val got = minOf(n, available())
            System.arraycopy(buf, start, dst, 0, got)
            start += got
            return got
        }

        fun seek(us: Long) {
            extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.flush()
            start = 0
            end = 0
            inputDone = false
            eos = false
            skipUntilUs = us
            heard = 0.0
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
                return
            }
            if (oi < 0) return
            val out = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
            out.position(info.offset)
            out.limit(info.offset + info.size)
            var count = if (float) info.size / 4 else info.size / 2
            if (tmp.size < count) tmp = FloatArray(count)
            if (float) out.asFloatBuffer().get(tmp, 0, count) else {
                val s = out.asShortBuffer()
                for (i in 0 until count) tmp[i] = s.get(i) / 32768f
            }
            var from = 0
            if (skipUntilUs >= 0 && count > 0) {
                // a seek lands on the packet before: its sound before the spot is dropped
                val frames = count / channels
                val endUs = info.presentationTimeUs + frames * 1_000_000L / rate
                if (endUs <= skipUntilUs) count = 0 else {
                    val drop = (((skipUntilUs - info.presentationTimeUs).coerceAtLeast(0)) * rate / 1_000_000L).toInt() * channels
                    from = minOf(drop, count)
                    count -= from
                    skipUntilUs = -1
                }
            }
            if (count > 0) push(tmp, from, count)
            codec.releaseOutputBuffer(oi, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
        }

        fun release() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
    }

    private val parts = files.map { (name, f) -> Part(name, f, gains[name] ?: 1f) }
    private val lock = Object()
    @Volatile private var running = true
    @Volatile private var playing = false
    @Volatile private var seekMs = -1L
    @Volatile private var startMs = 0L
    @Volatile private var rate = parts.firstOrNull()?.rate ?: 44100
    private var track: AudioTrack? = null
    private val thread = Thread({ loop() }, "radiance-mixer")

    /** Starts at [atMs] into the song, playing or paused. */
    fun start(atMs: Long, playing: Boolean) {
        this.playing = playing
        seekMs = atMs
        thread.start()
    }

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

    /** Where the song is, as heard. */
    fun positionMs(): Long = startMs + (track?.playbackHeadPosition?.toLong() ?: 0L) * 1000L / rate

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
        val info = MediaCodec.BufferInfo()
        val n = CHUNK * 2
        val mix = FloatArray(n)
        val part = FloatArray(n)
        var limiter: Limiter? = null
        var effects: EffectsChain? = null
        var fade = 0f // in after each start or seek, over FADE_S
        try {
            while (running) {
                val to = seekMs
                if (to >= 0) {
                    seekMs = -1
                    track?.pause()
                    track?.flush()
                    parts.forEach { it.seek(to * 1000) }
                    effects?.reset()
                    startMs = to
                    fade = 0f
                }
                if (!playing) {
                    track?.pause()
                    synchronized(lock) { lock.wait(250) }
                    continue
                }
                for (p in parts) {
                    var tries = 0
                    while (p.available() < (if (p.channels == 1) CHUNK else n) && !p.eos && tries++ < 400) p.decode(info)
                }
                val t = track ?: makeTrack(parts.first().rate).also {
                    rate = parts.first().rate
                    track = it
                    limiter = Limiter(2, rate)
                    effects = EffectsChain(rate, 2)
                }
                if (parts.all { it.eos && it.available() == 0 }) {
                    // the song's over: the player moves on (and lets this go)
                    synchronized(lock) { lock.wait(250) }
                    continue
                }
                java.util.Arrays.fill(mix, 0f)
                val dt = CHUNK.toDouble() / rate
                for (p in parts) {
                    val got = p.take(part, n)
                    var sum = 0.0
                    val from = p.shown
                    val toGain = p.gain
                    for (i in 0 until got) {
                        val x = part[i]
                        sum += x * x
                        val g = from + (toGain - from) * (i.toFloat() / n) // glides to its new volume
                        mix[i] += x * g
                    }
                    p.shown = toGain
                    if (got > 0) {
                        p.heard += dt
                        val k = maxOf(dt / p.heard, 1 - exp(-dt / LOUDNESS_S))
                        p.power += (sum / got - p.power) * k
                    }
                }
                limiter?.process(mix, CHUNK, Limiter.makeup(parts.map { it.power to it.gain }))
                effects?.process(mix, CHUNK) // Low cut, High cut, Reverb (as the player's)
                if (fade < 1f) {
                    val step = 1f / (FADE_S * rate).toFloat()
                    for (f in 0 until CHUNK) {
                        fade = minOf(1f, fade + step)
                        mix[2 * f] *= fade
                        mix[2 * f + 1] *= fade
                    }
                }
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                t.write(mix, 0, n, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            track?.run {
                runCatching { pause() }
                runCatching { flush() }
                release()
            }
            parts.forEach { it.release() }
        }
    }

    companion object {
        /** Frames mixed at a time (about 46 ms at 44.1 kHz). */
        private const val CHUNK = 2048
        private const val FADE_S = 0.25
        private const val LOUDNESS_S = 8.0
    }
}

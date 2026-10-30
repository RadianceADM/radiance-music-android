package io.github.radianceadm.music.split

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Sound as raw stereo float samples in a file (interleaved, little-endian): big songs never sit in memory. */
class RawAudio(val file: File, val rate: Int, val frames: Long) {
    fun map(): FloatBuffer = RandomAccessFile(file, "r").use { f ->
        f.channel.map(FileChannel.MapMode.READ_ONLY, 0, frames * 8).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    }
}

/** Decoding a song into raw stereo samples, resampling them, encoding a part. */
object Pcm {
    /** The song at [uri], decoded into [out] (mono made stereo). */
    fun decode(context: Context, uri: Uri, out: File, progress: (Float) -> Unit = {}): RawAudio {
        val ex = MediaExtractor()
        if (uri.scheme == "file") ex.setDataSource(uri.path!!) else ex.setDataSource(context, uri, null)
        try {
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var float = false
            var frames = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var bytes = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(0)
                val ch = raf.channel
                try {
                    while (true) {
                        if (!inputDone) {
                            val ii = codec.dequeueInputBuffer(5_000)
                            if (ii >= 0) {
                                val size = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                                    ex.advance()
                                }
                            }
                        }
                        val oi = codec.dequeueOutputBuffer(info, 5_000)
                        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            continue
                        }
                        if (oi < 0) continue
                        val buf = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val count = if (float) info.size / 4 else info.size / 2
                        val n = count / channels
                        if (bytes.capacity() < n * 8) bytes = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN)
                        bytes.clear()
                        for (f in 0 until n) {
                            val l = sample(buf, info.offset, f * channels, float)
                            val r = if (channels > 1) sample(buf, info.offset, f * channels + 1, float) else l
                            bytes.putFloat(l)
                            bytes.putFloat(r)
                        }
                        bytes.flip()
                        while (bytes.hasRemaining()) ch.write(bytes)
                        frames += n
                        codec.releaseOutputBuffer(oi, false)
                        if (durationUs > 0) progress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                } finally {
                    runCatching { codec.stop() }
                    codec.release()
                }
            }
            return RawAudio(out, rate, frames)
        } finally {
            ex.release()
        }
    }

    private fun sample(b: ByteBuffer, base: Int, i: Int, float: Boolean): Float =
        if (float) b.getFloat(base + 4 * i) else b.getShort(base + 2 * i) / 32768f

    /**
     * [src] at [rate] Hz (windowed sinc, Kaiser; 64 taps a side, so nothing
     * audible is lost or folded back). The same file when it's at that rate.
     */
    fun resample(src: RawAudio, rate: Int, out: File, progress: (Float) -> Unit = {}): RawAudio {
        if (src.rate == rate) return src
        val ratio = src.rate.toDouble() / rate
        val cutoff = minOf(1.0, rate.toDouble() / src.rate) * 0.96
        val half = ceil(TAPS / cutoff).toInt()
        val table = Array(PHASES + 1) { p ->
            val frac = p.toDouble() / PHASES
            FloatArray(2 * half) { j ->
                val d = (j - half + 1) - frac // tap distance from the output's spot, in input samples
                (cutoff * sinc(cutoff * d) * kaiser(d / half)).toFloat()
            }
        }
        val x = src.map()
        val inFrames = src.frames
        val outFrames = floor(inFrames / ratio).toLong()
        val bytes = ByteBuffer.allocate(BLOCK * 8).order(ByteOrder.LITTLE_ENDIAN)
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            val ch = raf.channel
            var n = 0L
            while (n < outFrames) {
                bytes.clear()
                val end = minOf(outFrames, n + BLOCK)
                while (n < end) {
                    val t = n * ratio
                    val i0 = floor(t).toLong()
                    val h = table[((t - i0) * PHASES).toInt()]
                    var l = 0f
                    var r = 0f
                    val first = i0 - half + 1
                    for (j in 0 until 2 * half) {
                        val k = first + j
                        if (k < 0 || k >= inFrames) continue
                        val w = h[j]
                        l += x.get((2 * k).toInt()) * w
                        r += x.get((2 * k + 1).toInt()) * w
                    }
                    bytes.putFloat(l)
                    bytes.putFloat(r)
                    n++
                }
                bytes.flip()
                while (bytes.hasRemaining()) ch.write(bytes)
                progress(n.toFloat() / outFrames)
            }
        }
        return RawAudio(out, rate, outFrames)
    }

    private fun sinc(x: Double) = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)

    private fun kaiser(x: Double): Double {
        if (abs(x) > 1) return 0.0
        return bessel0(BETA * sqrt(1 - x * x)) / bessel0(BETA)
    }

    private fun bessel0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        var k = 1
        while (term > 1e-12 * sum) {
            term *= (x / (2 * k)) * (x / (2 * k))
            sum += term
            k++
        }
        return sum
    }

    private const val TAPS = 64
    private const val PHASES = 1024
    private const val BETA = 9.0
    private const val BLOCK = 8192

    /** A part being written: AAC in .m4a, as the song's sound goes by. */
    class Encoder(file: File, private val rate: Int, bitrate: Int = 256_000) {
        private val codec: MediaCodec
        private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var track = -1
        private var fed = 0L
        private val info = MediaCodec.BufferInfo()

        init {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        /** [frames] stereo frames from [s] (interleaved), from [from]. */
        fun write(s: FloatArray, from: Int, frames: Int) {
            var at = 0
            while (at < frames) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii < 0) { drain(false); continue }
                val buf = codec.getInputBuffer(ii)!!.order(ByteOrder.nativeOrder())
                buf.clear()
                val n = minOf(frames - at, buf.capacity() / 4)
                for (f in 0 until n) {
                    val i = from + 2 * (at + f)
                    buf.putShort(pcm16(s[i]))
                    buf.putShort(pcm16(s[i + 1]))
                }
                codec.queueInputBuffer(ii, 0, n * 4, fed * 1_000_000L / rate, 0)
                fed += n
                at += n
                drain(false)
            }
        }

        private fun pcm16(v: Float) = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

        private fun drain(end: Boolean) {
            while (true) {
                val oi = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) { if (end) continue else return }
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    continue
                }
                if (oi < 0) continue
                val buf = codec.getOutputBuffer(oi)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    muxer.writeSampleData(track, buf, info)
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }

        fun finish() {
            var ii: Int
            do { ii = codec.dequeueInputBuffer(10_000); if (ii < 0) drain(false) } while (ii < 0)
            codec.queueInputBuffer(ii, 0, 0, fed * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            close()
        }

        fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    /**
     * How late an .m4a written by [Encoder] plays its sound, in frames, read
     * back by the same decoder the Mixer uses (AAC starts with a little
     * silence of its own). Parts are written that much early, so they play
     * exactly in step with the song.
     */
    fun encoderDelay(context: Context, dir: File): Int {
        val prefs = context.getSharedPreferences("splitter", Context.MODE_PRIVATE)
        prefs.getInt("aacDelay", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { return it }
        val rate = 44100
        val mark = 8192
        val test = FloatArray(rate * 2)
        test[2 * mark] = 0.9f
        test[2 * mark + 1] = 0.9f
        val f = File(dir, "delay-test.m4a")
        Encoder(f, rate).apply { write(test, 0, rate); finish() }
        val raw = File(dir, "delay-test.f32")
        val back = decode(context, Uri.fromFile(f), raw).map()
        var best = 0
        var peak = 0f
        for (i in 0 until back.limit() / 2) {
            val v = abs(back.get(2 * i))
            if (v > peak) { peak = v; best = i }
        }
        f.delete(); raw.delete()
        val delay = best - mark
        prefs.edit().putInt("aacDelay", delay).apply()
        return delay
    }
}

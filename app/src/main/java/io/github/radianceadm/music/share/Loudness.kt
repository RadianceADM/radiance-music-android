package io.github.radianceadm.music.share

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.sqrt

/**
 * How loud the song is along its length, as the bars of the strip under the story (#19):
 * its saved copy decoded once (the sound the video takes), each bar the loudness of its
 * stretch of the song, scaled to the loudest so the chorus stands out.
 */
object Loudness {
    /** The least a bar shows (a quiet stretch is still seen), as a part of the tallest. */
    private const val FLOOR = 0.12f

    /** [rms] scaled to the loudest: [FLOOR] for silence, 1 for the loudest. */
    fun bars(rms: FloatArray): FloatArray {
        val top = rms.maxOrNull() ?: return FloatArray(0)
        return FloatArray(rms.size) { i -> if (top <= 0f) FLOOR else FLOOR + (1 - FLOOR) * (rms[i] / top) }
    }

    /** The song's loudness collected in [count] equal stretches of its [durationUs]. */
    class Buckets(private val durationUs: Long, private val count: Int) {
        private val squares = DoubleArray(count)
        private val samples = LongArray(count)

        fun add(timeUs: Long, sumSquares: Float, samples: Int) {
            val i = if (durationUs <= 0) 0 else (timeUs * count / durationUs).toInt().coerceIn(0, count - 1)
            squares[i] += sumSquares.toDouble()
            this.samples[i] += samples.toLong()
        }

        fun rms() = FloatArray(count) { i -> if (samples[i] == 0L) 0f else sqrt(squares[i] / samples[i]).toFloat() }
    }

    /**
     * The bars of the song at [uri] ([count] of them; null when it can't be read). Every 4th
     * sample of the first channel is enough for loudness. Stops when its coroutine is cancelled.
     */
    suspend fun of(context: Context, uri: Uri, count: Int): FloatArray? {
        val ex = MediaExtractor()
        try {
            if (uri.scheme == "file") ex.setDataSource(uri.path!!) else ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else return null
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            val buckets = Buckets(durationUs, count)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                var float = false
                var inputDone = false
                val info = MediaCodec.BufferInfo()
                while (true) {
                    coroutineContext.ensureActive()
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
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        continue
                    }
                    if (oi < 0) continue
                    val buf = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                    val frames = (if (float) info.size / 4 else info.size / 2) / channels
                    var sum = 0f
                    var n = 0
                    var f = 0
                    while (f < frames) {
                        val i = f * channels
                        val s = if (float) buf.getFloat(info.offset + 4 * i) else buf.getShort(info.offset + 2 * i) / 32768f
                        sum += s * s
                        n++
                        f += 4
                    }
                    if (n > 0) buckets.add(info.presentationTimeUs, sum, n)
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return bars(buckets.rms())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            ex.release()
        }
    }
}

package io.github.radianceadm.music.share

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A story with the song's own sound (owner, 2026-10-31: "pede kaya to kasama yung
 * clip nung sounds"): the card as a video, its lyric lines lighting up as they're
 * sung, over the song itself, clipped to those lines (or 15 seconds from where
 * you are), fading in and out. An MP4 (H.264 and AAC), as Instagram, Facebook and
 * TikTok take for stories.
 */
@OptIn(UnstableApi::class)
object StoryVideo {
    /** Without timed lines: this long. */
    const val PLAIN_MS = 15_000L
    /** Stories take a minute; a clip this long is plenty. */
    const val MOST_MS = 30_000L
    const val LEAST_MS = 6_000L

    /** One picture of the video and how long it shows. */
    data class Frame(val picture: Bitmap, val ms: Long)

    /** The clip of the song: where it starts in it, how long it is, and each line's moment in it (ms from the start). */
    data class Clip(val startMs: Long, val lengthMs: Long, val lineAtMs: List<Long>)

    /**
     * The clip for [picked] lines sung at [starts] (seconds, with [nextStart] the
     * line after the last one), or [fromMs] for a [PLAIN_MS] clip when the lines
     * aren't timed. [songMs]: the song's length, if known.
     */
    fun clip(starts: List<Double>?, nextStart: Double?, fromMs: Long, songMs: Long): Clip {
        if (starts.isNullOrEmpty()) {
            val start = if (songMs > 0) fromMs.coerceIn(0, (songMs - PLAIN_MS).coerceAtLeast(0)) else fromMs.coerceAtLeast(0)
            val length = if (songMs > 0) PLAIN_MS.coerceAtMost(songMs - start) else PLAIN_MS
            return Clip(start, length, emptyList())
        }
        val start = ((starts.first() * 1000).toLong() - 500).coerceAtLeast(0)
        val last = (starts.last() * 1000).toLong()
        val end = ((nextStart?.let { (it * 1000).toLong() } ?: (last + 4_000)) + 700).coerceAtMost(last + 8_000)
        var length = (end - start).coerceIn(LEAST_MS, MOST_MS)
        if (songMs > 0) length = length.coerceAtMost(songMs - start)
        return Clip(start, length, starts.map { (it * 1000).toLong() - start })
    }

    /** The frames: before the first line, then each line lit in turn (or one picture all along). */
    fun frames(clip: Clip, picture: (lit: Int?) -> Bitmap): List<Frame> {
        if (clip.lineAtMs.isEmpty()) return listOf(Frame(picture(null), clip.lengthMs))
        // lines past the clip's end (it's at most MOST_MS long) don't get a frame of their own
        val out = mutableListOf<Frame>()
        val first = clip.lineAtMs.first().coerceAtLeast(0)
        if (first > 80) out += Frame(picture(-1), first)
        val shown = clip.lineAtMs.takeWhile { it < clip.lengthMs - 200 }.ifEmpty { clip.lineAtMs.take(1) }
        shown.forEachIndexed { i, at ->
            val until = (shown.getOrNull(i + 1) ?: clip.lengthMs).coerceAtMost(clip.lengthMs)
            val ms = (until - at.coerceAtLeast(0)).coerceAtLeast(120)
            out += Frame(picture(i), ms)
        }
        // what's left (rounding) goes to the last line, so the picture lasts as long as the sound
        val total = out.sumOf { it.ms }
        if (total != clip.lengthMs) out[out.lastIndex] = out.last().copy(ms = (out.last().ms + clip.lengthMs - total).coerceAtLeast(120))
        return out
    }

    /** Makes the video in the app's cache; [progress] gets 0-1. The file. */
    suspend fun make(context: Context, name: String, song: Uri, clip: Clip, frames: List<Frame>, progress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "share/video").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the latest video is kept
        val pictures = withContext(Dispatchers.IO) {
            frames.mapIndexed { i, f ->
                val file = File(dir, "frame-$i.jpg")
                file.outputStream().use { f.picture.compress(Bitmap.CompressFormat.JPEG, 94, it) }
                EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(file)).setImageDurationMs(f.ms).build())
                    .setDurationUs(f.ms * 1000)
                    .setFrameRate(30)
                    .build()
            }
        }
        val sound = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(song).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.startMs).setEndPositionMs(clip.startMs + clip.lengthMs).build(),
            ).build(),
        )
            .setRemoveVideo(true) // a cover inside the song file isn't a picture for this
            .setEffects(Effects(listOf<AudioProcessor>(Fade(clip.lengthMs * 1000)), emptyList()))
            .build()
        val composition = Composition.Builder(
            EditedMediaItemSequence.withVideoFrom(pictures),
            EditedMediaItemSequence.withAudioFrom(listOf(sound)),
        ).build()
        val out = File(dir, "$name.mp4")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { going ->
                val main = Handler(Looper.getMainLooper())
                lateinit var poll: Runnable
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setPortraitEncodingEnabled(true) // stored upright (some apps ignore a "turn it" mark)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            main.removeCallbacks(poll)
                            progress(1f)
                            if (going.isActive) going.resume(out)
                        }

                        override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                            main.removeCallbacks(poll)
                            if (going.isActive) going.resumeWithException(IllegalStateException("couldn't make the video (${e.errorCodeName})", e))
                        }
                    })
                    .build()
                val holder = ProgressHolder()
                poll = Runnable {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100f)
                    main.postDelayed(poll, 250)
                }
                transformer.start(composition, out.path)
                main.post(poll)
                going.invokeOnCancellation {
                    main.post {
                        main.removeCallbacks(poll)
                        transformer.cancel()
                        out.delete()
                    }
                }
            }
        }
    }

    /** The sound fades in over a moment and out over the last second (a clip, not a cut). */
    private class Fade(private val lengthUs: Long) : BaseAudioProcessor() {
        private var rate = 44_100
        private var channels = 2
        private var encoding = C.ENCODING_PCM_16BIT
        private var frame = 0L

        override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (input.encoding != C.ENCODING_PCM_16BIT && input.encoding != C.ENCODING_PCM_FLOAT) throw AudioProcessor.UnhandledAudioFormatException(input)
            rate = input.sampleRate
            channels = input.channelCount
            encoding = input.encoding
            return input
        }

        private fun gain(): Float {
            val us = frame * 1_000_000 / rate
            val fadeIn = (us / 400_000f).coerceAtMost(1f)
            val fadeOut = ((lengthUs - us) / 1_000_000f).coerceIn(0f, 1f)
            return minOf(fadeIn, fadeOut)
        }

        override fun queueInput(input: ByteBuffer) {
            val out = replaceOutputBuffer(input.remaining())
            if (encoding == C.ENCODING_PCM_FLOAT) {
                while (input.remaining() >= 4 * channels) {
                    val g = gain()
                    repeat(channels) { out.putFloat(input.float * g) }
                    frame++
                }
            } else {
                while (input.remaining() >= 2 * channels) {
                    val g = gain()
                    repeat(channels) { out.putShort((input.short * g).toInt().coerceIn(-32768, 32767).toShort()) }
                    frame++
                }
            }
            input.position(input.limit())
            out.flip()
        }

        override fun onFlush() {
            frame = 0
        }
    }
}

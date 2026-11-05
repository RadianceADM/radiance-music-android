package io.github.radianceadm.music.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import io.github.radianceadm.music.model.clock
import io.github.radianceadm.music.share.StoryVideo.Window

/** Where a finger on the strip lands, and the strip's pixels as the song's milliseconds. */
object StripTouch {
    enum class Grab { START, END, MIDDLE, OUTSIDE }

    fun msAt(x: Float, width: Float, songMs: Long): Long = if (width <= 0f) 0 else (x / width * songMs).toLong().coerceIn(0, songMs)

    fun xAt(ms: Long, width: Float, songMs: Long): Float = if (songMs <= 0) 0f else ms.toFloat() / songMs * width

    /**
     * An end of the bright part (a finger's width either side of it, or its outer quarter when
     * it's narrow, so a short part still has a middle), its middle, or the rest of the strip.
     */
    fun grab(x: Float, width: Float, w: Window, songMs: Long, edgePx: Float): Grab {
        val left = xAt(w.startMs, width, songMs)
        val right = xAt(w.endMs, width, songMs)
        val inner = minOf(edgePx, (right - left) / 4)
        return when {
            x >= left - edgePx && x <= left + inner -> Grab.START
            x >= right - inner && x <= right + edgePx -> Grab.END
            x > left && x < right -> Grab.MIDDLE
            else -> Grab.OUTSIDE
        }
    }
}

/**
 * The strip under the story (#19, owner's pick: "Strip under the story", "Any length, 6 to 30 s"):
 * the song's loudness as soft bars, the part the video takes bright. Dragging the part moves it,
 * dragging its ends makes it shorter or longer, a tap puts it there. [onTouch] says when a
 * finger goes down on it and when it's lifted (the part plays meanwhile, and from its start after). Not [enabled]: the picked lyric lines set
 * the part, and it only shows it.
 */
@Composable
fun ClipStrip(
    bars: FloatArray?,
    window: Window,
    songMs: Long,
    enabled: Boolean,
    playing: Boolean,
    onChange: (Window) -> Unit,
    onTouch: (down: Boolean, grab: StripTouch.Grab) -> Unit,
    onHear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(window)
    val edgePx = with(LocalDensity.current) { 22.dp.toPx() }
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        val touch = if (!enabled || songMs <= 0) Modifier else Modifier
            .pointerInput(songMs) {
                detectTapGestures { o ->
                    onChange(current.moved(StripTouch.msAt(o.x, size.width.toFloat(), songMs) - current.lengthMs / 2, songMs))
                    onTouch(false, StripTouch.Grab.OUTSIDE)
                }
            }
            .pointerInput(songMs) {
                var grab = StripTouch.Grab.OUTSIDE
                var from = current
                var fromMs = 0L
                detectDragGestures(
                    onDragStart = { o ->
                        val width = size.width.toFloat()
                        grab = StripTouch.grab(o.x, width, current, songMs, edgePx)
                        fromMs = StripTouch.msAt(o.x, width, songMs)
                        from = if (grab == StripTouch.Grab.OUTSIDE) current.moved(fromMs - current.lengthMs / 2, songMs).also(onChange) else current
                        onTouch(true, grab)
                    },
                    onDragEnd = { onTouch(false, grab) },
                    onDragCancel = { onTouch(false, grab) },
                ) { change, _ ->
                    change.consume()
                    val ms = StripTouch.msAt(change.position.x, size.width.toFloat(), songMs)
                    onChange(
                        when (grab) {
                            StripTouch.Grab.START -> from.startAt(ms, songMs)
                            StripTouch.Grab.END -> from.endAt(ms, songMs)
                            else -> from.moved(from.startMs + (ms - fromMs), songMs)
                        },
                    )
                }
            }
        Canvas(Modifier.fillMaxWidth().height(56.dp).then(touch)) {
            val width = size.width
            val left = StripTouch.xAt(window.startMs, width, songMs)
            val right = StripTouch.xAt(window.endMs, width, songMs)
            // the bright part's ground, round
            drawRoundRect(Color.White.copy(alpha = if (enabled) 0.16f else 0.1f), Offset(left, 0f), Size((right - left).coerceAtLeast(2f), size.height), CornerRadius(12.dp.toPx()))
            // the bars (soft: round ends); flat while the loudness is still being read
            val shown = bars ?: FloatArray(64) { 0.22f }
            val slot = width / shown.size
            val barW = (slot * 0.55f).coerceAtLeast(1f)
            for (i in shown.indices) {
                val x = i * slot + (slot - barW) / 2
                val h = (size.height - 14.dp.toPx()) * shown[i]
                val inside = x + barW / 2 in left..right
                drawRoundRect(
                    Color.White.copy(alpha = if (inside) (if (enabled) 1f else 0.7f) else 0.32f),
                    Offset(x, (size.height - h) / 2),
                    Size(barW, h),
                    CornerRadius(barW / 2),
                )
            }
            // its ends: round handles
            if (enabled) {
                val hw = 4.dp.toPx()
                val hh = size.height * 0.94f
                for (x in listOf(left, right)) drawRoundRect(Color.White, Offset(x - hw / 2, (size.height - hh) / 2), Size(hw, hh), CornerRadius(hw / 2))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (enabled) "${clock(window.startMs / 1000)} – ${clock(window.endMs / 1000)} · ${window.lengthMs / 1000} s"
                else "The lines you picked set the part",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
            )
            TextButton(onClick = onHear) { Labeled(if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (playing) "Stop" else "Hear it", Color.White) }
        }
        if (enabled) Text("Drag the bright part to move it, its ends to make it 6 to 30 s.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

/**
 * The picked part, heard from the song's saved copy (what the video's sound is): its own
 * small player, so the music you were playing keeps its place (it's paused meanwhile).
 */
class ClipPreview(context: Context, song: Uri) {
    private val player = ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.fromUri(song))
        prepare()
    }
    private var endMs = 0L

    val playing get() = player.isPlaying || player.playWhenReady

    /** Plays from [fromMs] to the end of [w]. */
    fun play(w: Window, fromMs: Long = w.startMs) {
        endMs = w.endMs
        player.seekTo(fromMs.coerceIn(w.startMs, w.endMs))
        player.play()
    }

    /** The part changed while it plays: its end follows. */
    fun follow(w: Window) { endMs = w.endMs }

    /** Called often: stops at the part's end. True while it plays. */
    fun tick(): Boolean {
        if (player.playWhenReady && player.currentPosition >= endMs) player.pause()
        return player.playWhenReady
    }

    fun stop() = player.pause()

    fun release() = player.release()
}

package io.github.radianceadm.music.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The start (a cold one): the icon's sun rises out of the sea and its four
 * bars (drums, bass, other, vocals) play like an equalizer; then they come to
 * rest, the sun sets again and the logo writes itself (the R, its leg rolling
 * on into the m, the wave fading out) with the name under it; then the dark
 * opens from the middle out into the app, the logo going last. In the app's
 * own dark. A tap goes straight in. The same as the computer app's
 * (src/ui/splash.rs).
 */
object Splash {
    /** The sun coming up, at least this long on screen, the bars coming to rest. */
    const val RISE_S = 1.0f
    const val SHOWN_S = 1.4f
    const val SETTLE_S = 0.35f
    /** After they settle: the sun goes down, the logo writes itself (from WRITE_S on, WRITTEN_S long), stays, and the dark opens. */
    const val SET_S = 0.5f
    const val WRITE_S = 0.3f
    const val WRITTEN_S = 1.75f
    const val HOLD_S = 0.35f
    const val OPEN_S = 0.9f
    /** The opening's soft edge (part of the screen's half-diagonal). */
    private const val SOFT = 0.3f

    /** When the dark starts opening, its bars having settled at [settleFrom]. */
    fun openFrom(settleFrom: Float) = settleFrom + SETTLE_S + WRITE_S + WRITTEN_S + HOLD_S
    /** When it's all open (the splash goes). */
    fun end(open: Float) = open + OPEN_S

    /** The icon's own drawing (128 across): the sun, the bars (x, resting top), the sea's fade. */
    private const val SUN_X = 64f
    private const val SUN_Y = 67.5f
    private const val SUN_R = 34.8f
    private val BARS = listOf(50.7f to 73.3f, 59.6f to 60.5f, 68.4f to 67.5f, 77.3f to 77.9f)
    private const val BAR_W = 5.8f
    private const val BAR_FOOT = 112.7f
    private const val SEA_TOP = 87.2f
    private const val SEA_BOTTOM = 105.8f
    /** How far below its place the sun starts (under the sea). */
    private const val RISE_FROM = 45f

    /** The logo (Logo.kt, the desktop's data/brand/radiance-music-logo.svg): 0..100 units, the R's middle at 50. */
    private val R = path("M38 71V29H51.5A10.5 10.5 0 0 1 51.5 50H38")
    private val LEG = path("M50.5 50C56.5 51.5 61 59 61 71C62.5467 60 63.4167 52.5 68.25 52.5C73.0833 52.5 73.9533 60 75.5 70.6C77.0467 60 77.9167 52.5 82.75 52.5C87.5833 52.5 88.0667 71 90.5 71")
    private val TAIL = path("M90.5 71C94.5 71 97.5 66 100.5 61")
    private const val PEN = 8.8f
    private fun path(d: String) = PathParser().parsePathString(d).toPath()

    /** The app's own dark (Brand.Night): the sky, the sea, the bars. */
    private val DARK = Brand.Night
    private val WHITE = Color(0xFFF5F7FA)
    private val SUN_TOP = Color(0xFFFFDB94)
    private val ORANGE = Color(0xFFF38A35)
    private val GLOW = Color(0xFFFFAE63)

    fun ease(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return v * v * (3 - 2 * v)
    }

    /** [p] drawn [part] (0..1) of the way along (null: not started). */
    private fun written(p: Path, part: Float): Path? {
        if (part <= 0.001f) return null
        if (part >= 0.999f) return p
        val m = PathMeasure().apply { setPath(p, false) }
        return Path().also { m.getSegment(0f, m.length * part, it, true) }
    }

    /** The splash as it is [t] seconds in, its bars settling from [settleFrom], the dark opening from [openFrom]. */
    fun DrawScope.splash(t: Float, settleFrom: Float, openFrom: Float) {
        val (w, h) = size.width to size.height
        val rise = ease(t / RISE_S)
        val settled = ease((t - settleFrom) / SETTLE_S)
        val play = ease((t - 0.8f) / 0.3f) * (1f - settled)
        val set = ease((t - settleFrom - 0.1f) / SET_S)
        val open = ease((t - openFrom) / OPEN_S)
        // the icon's 128 units, half the screen's shorter side (more of a phone's narrow one), in the middle
        val u = min(w, h) * (if (h > w * 1.3f) 0.62f else 0.5f) / 128f
        fun at(x: Float, y: Float) = Offset(w / 2 + (x - 64f) * u, h / 2 + (y - 64f) * u)
        val whole = Rect(Offset.Zero, size)
        // the dark, the sun and the sea in a layer of their own, so the opening can cut through them
        drawIntoCanvas { it.saveLayer(whole, androidx.compose.ui.graphics.Paint()) }
        drawRect(DARK)
        drawRect(Brush.radialGradient(listOf(GLOW.copy(alpha = 0.4f * rise), GLOW.copy(alpha = 0f)), center = at(64f, 69.8f), radius = 100f * u))
        if (set < 1f) {
            // the sun, rising (and setting again), its bars playing
            val lift = (1f - rise) * RISE_FROM + set * RISE_FROM
            val c = at(SUN_X, SUN_Y + lift)
            val r = SUN_R * u
            val fadeDown = 1f - set * 0.6f
            drawCircle(Brush.verticalGradient(listOf(SUN_TOP, ORANGE), startY = c.y - r, endY = c.y + r), radius = r, center = c, alpha = fadeDown)
            BARS.forEachIndexed { i, (x, top) ->
                val bounce = sin(t * 2 * PI.toFloat() * 1.6f + i * 1.7f) * 7f * play
                val a = at(x - BAR_W / 2, top + bounce + lift)
                val b = at(x + BAR_W / 2, BAR_FOOT + lift)
                drawRoundRect(DARK, topLeft = Offset(a.x, a.y - BAR_W / 2 * u), size = Size(b.x - a.x, b.y - a.y + BAR_W * u), cornerRadius = CornerRadius(BAR_W / 2 * u))
            }
            // the sea: the sun's foot and the bars' fade into the dark
            val y0 = at(0f, SEA_TOP).y
            val y1 = at(0f, SEA_BOTTOM).y
            drawRect(Brush.verticalGradient(listOf(DARK.copy(alpha = 0f), DARK), startY = y0, endY = y1), topLeft = Offset(0f, y0), size = Size(w, y1 - y0))
            drawRect(DARK, topLeft = Offset(0f, y1 - 0.5f), size = Size(w, h - y1 + 0.5f))
        }
        if (open > 0f) {
            // the opening: none of the dark inside it, all of it past its soft edge
            val inner = (open * (1f + SOFT) - SOFT).coerceIn(0f, 1f)
            val outer = (open * (1f + SOFT)).coerceIn(inner, 1f)
            val clear = Color.Black.copy(alpha = 0f)
            drawRect(Brush.radialGradient(0f to clear, inner to clear, outer to Color.Black, 1f to Color.Black, center = Offset(w / 2, h / 2), radius = hypot(w, h) / 2), blendMode = BlendMode.DstIn)
        }
        drawIntoCanvas { it.restore() }
        // then the logo writes itself: the R, its leg into the m, the wave fading out, the name under it
        val k = t - settleFrom - SETTLE_S - WRITE_S
        val parts = listOf(ease(k / 0.65f), ease((k - 0.55f) / 0.75f), ease((k - 1.25f) / 0.3f))
        val shows = 1f - ease((open - 0.35f) / 0.55f)
        if (parts[0] <= 0f || shows <= 0f) return
        val v = min(w, h) * 0.55f / 100f
        val round = Stroke(width = PEN, cap = StrokeCap.Round, join = StrokeJoin.Round)
        withTransform({
            translate(w / 2 - 50f * v, h / 2 - 50f * v)
            scale(v, v, pivot = Offset.Zero)
        }) {
            written(R, parts[0])?.let { drawPath(it, WHITE, style = round, alpha = shows) }
            written(LEG, parts[1])?.let { drawPath(it, Brush.linearGradient(0f to WHITE, 0.24f to SUN_TOP, 1f to ORANGE, start = Offset(50.5f, 0f), end = Offset(90.5f, 0f)), style = round, alpha = shows) }
            written(TAIL, parts[2])?.let { drawPath(it, Brush.linearGradient(listOf(ORANGE, ORANGE.copy(alpha = 0f)), start = Offset(90.5f, 71f), end = Offset(100.5f, 61f)), style = Stroke(width = PEN, cap = StrokeCap.Butt), alpha = shows) }
        }
        // and the name, coming up softly as the wave goes out
        val name = ease((k - 1.1f) / 0.55f) * shows
        if (name > 0f) drawIntoCanvas { c ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * v; typeface = Typeface.create(Typeface.DEFAULT, 700, false) }
            val first = "Radiance "
            val whole2 = paint.measureText(first + "Music")
            val x = w / 2 - whole2 / 2
            val y = h / 2 + (36f + 4f * (1f - name)) * v + paint.textSize * 0.8f
            paint.color = WHITE.copy(alpha = name).toArgb()
            c.nativeCanvas.drawText(first, x, y, paint)
            paint.color = ORANGE.copy(alpha = name).toArgb()
            c.nativeCanvas.drawText("Music", x + paint.measureText(first), y, paint)
        }
    }
}

/** The splash over everything until it's done ([onDone]: take it away). */
@Composable
fun StartSplash(onDone: () -> Unit) {
    var t by remember { mutableFloatStateOf(0f) }
    // a tap: the dark opens now
    var openFrom by remember { mutableFloatStateOf(Splash.openFrom(Splash.SHOWN_S)) }
    var tapped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (t < Splash.end(openFrom)) {
            withFrameNanos { now -> t = (now - start) / 1e9f }
        }
        onDone()
    }
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures {
                if (!tapped) {
                    tapped = true
                    openFrom = minOf(openFrom, t)
                }
            }
        },
    ) {
        with(Splash) { splash(t, Splash.SHOWN_S, openFrom) }
    }
}

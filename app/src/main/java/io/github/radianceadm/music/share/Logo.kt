package io.github.radianceadm.music.share

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * Radiance Music's logo: the R, its leg rolling on as a wave shaped like an
 * m (R + M), fading out at the end. The same drawing as the desktop repo's
 * data/brand/radiance-music-logo.svg, in its 0..100 units.
 */
object Logo {
    /** The logo's box (the SVG's viewBox). */
    private const val LEFT = 33f
    private const val TOP = 24f
    private const val WIDTH = 70f
    private const val HEIGHT = 52f

    private const val WHITE = 0xFFF5F7FA.toInt()
    private const val SUN = 0xFFFFDB94.toInt()
    private const val ORANGE = 0xFFF38A35.toInt()

    private val r = Path().apply {
        moveTo(38f, 71f); lineTo(38f, 29f); lineTo(51.5f, 29f)
        arcTo(RectF(41f, 29f, 62f, 50f), -90f, 180f, false)
        lineTo(38f, 50f)
    }
    private val leg = Path().apply {
        moveTo(50.5f, 50f)
        cubicTo(56.5f, 51.5f, 61f, 59f, 61f, 71f)
        cubicTo(62.5467f, 60f, 63.4167f, 52.5f, 68.25f, 52.5f)
        cubicTo(73.0833f, 52.5f, 73.9533f, 60f, 75.5f, 70.6f)
        cubicTo(77.0467f, 60f, 77.9167f, 52.5f, 82.75f, 52.5f)
        cubicTo(87.5833f, 52.5f, 88.0667f, 71f, 90.5f, 71f)
    }
    private val tail = Path().apply {
        moveTo(90.5f, 71f)
        cubicTo(94.5f, 71f, 97.5f, 66f, 100.5f, 61f)
    }

    /** Width of the logo drawn [height] px tall. */
    fun widthFor(height: Float) = WIDTH * height / HEIGHT

    /** Draws the logo [height] px tall with its top-left at [x], [y]. */
    fun draw(c: Canvas, x: Float, y: Float, height: Float) {
        val k = height / HEIGHT
        val save = c.save()
        c.translate(x, y); c.scale(k, k); c.translate(-LEFT, -TOP)
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 8.8f
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        pen.color = WHITE
        c.drawPath(r, pen)
        // grows out of the R white, turns sunset as it rolls into the m
        pen.shader = LinearGradient(50.5f, 0f, 90.5f, 0f, intArrayOf(WHITE, SUN, ORANGE), floatArrayOf(0f, 0.24f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(leg, pen)
        // the wave going on out, fading away (flush with the m: no dot at the join)
        pen.shader = LinearGradient(90.5f, 71f, 100.5f, 61f, ORANGE, ORANGE and 0x00FFFFFF, Shader.TileMode.CLAMP)
        pen.strokeCap = Paint.Cap.BUTT
        c.drawPath(tail, pen)
        c.restoreToCount(save)
    }
}

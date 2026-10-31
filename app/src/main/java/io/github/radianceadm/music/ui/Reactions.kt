package io.github.radianceadm.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/**
 * A party's reactions as small soft pictures, the same as the server's party
 * page draws them: round faces, a rounded flame, a heart, clapping hands, a
 * balloon (owner: nothing spiky; no emoji font needed). The server carries
 * the emoji itself; one this phone doesn't know shows as the emoji's text.
 */
object Reactions {
    class Pic(val emoji: String, val name: String, val draw: DrawScope.() -> Unit)

    private val FACE = Color(0xFFFFC94D)
    private val INK = Color(0xFF5A3A12)

    private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

    private fun DrawScope.fill(p: Path, c: Color, stroke: Float = 0f) {
        drawPath(p, c)
        if (stroke > 0f) drawPath(p, c, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private fun DrawScope.line(p: Path, c: Color, width: Float, alpha: Float = 1f) =
        drawPath(p, c, alpha = alpha, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))

    private fun DrawScope.oval(cx: Float, cy: Float, rx: Float, ry: Float, c: Color, alpha: Float = 1f) =
        drawOval(c, Offset(cx - rx, cy - ry), Size(2 * rx, 2 * ry), alpha = alpha)

    private val flame = path("M12 21.5c-3.9 0-6.6-2.7-6.6-6.3 0-2.5 1.3-4.4 2.8-5.9.3 1.6 1.1 2.6 2.1 3-.4-3.1.9-6.2 3-8.2.5 2.4 1.9 4.2 3.3 5.8 1.2 1.4 1.9 3.1 1.9 5 0 3.9-2.7 6.6-6.5 6.6z")
    private val flameCore = path("M12 20.2c-1.8 0-3-1.2-3-2.9 0-1.5 1-2.6 2.2-3.5.1.9.6 1.5 1.3 1.7.1-1.2.6-2.1 1.4-2.8.6 1 1 2 1 3.3 0 2.5-1.2 4.2-2.9 4.2z")
    private val heart = path("M19 14c1.5-1.5 3-3.2 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.8 0-3 .5-4.5 2-1.5-1.5-2.7-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4 3 5.5l7 7z")
    private val laughEyes = path("M7.3 9.8q1.6-1.8 3.2 0M13.5 9.8q1.6-1.8 3.2 0")
    private val laughMouth = path("M7 13.2h10a5 5 0 0 1-10 0z")
    private val clapLines = path("M4.2 5.2q.6-1.5 2-2M19.8 3.2q1.3.5 1.8 1.9M20.8 8.1q.8.3 1.1 1")
    private val knot = path("M10.9 17.1h2.2l-1.1 1.5z")
    private val string = path("M12 18.6c-1.4 1 1.4 1.8 0 3.2")
    private val brows = path("M6.8 7.3q1.6-1.2 3.2-.6M14 6.7q1.6-.6 3.2.6")

    /** The six on the party's screen, in its order (on a 24 × 24 grid). */
    val all = listOf(
        Pic("🔥", "Fire") {
            fill(flame, Color(0xFFF38A35), 1.8f)
            fill(flameCore, Color(0xFFFFD27A), 1f)
        },
        Pic("❤️", "Love") {
            fill(heart, Color(0xFFF2685B), 1.6f)
            rotate(-30f, Offset(7.3f, 7.6f)) { oval(7.3f, 7.6f, 1.6f, 1.1f, Color.White, 0.35f) }
        },
        Pic("😂", "Laugh") {
            drawCircle(FACE, 10f, Offset(12f, 12f))
            line(laughEyes, INK, 1.6f)
            fill(laughMouth, INK, 1.2f)
            oval(12f, 16.7f, 2.4f, 1.2f, Color(0xFFF2685B))
        },
        Pic("👏", "Clap") {
            rotate(-16f, Offset(9f, 13f)) { drawRoundRect(Color(0xFFFFB774), Offset(5.5f, 6.5f), Size(7f, 13f), CornerRadius(3.5f)) }
            rotate(16f, Offset(15f, 12f)) { drawRoundRect(Color(0xFFF38A35), Offset(11.5f, 5.5f), Size(7f, 13f), CornerRadius(3.5f)) }
            line(clapLines, Color(0xFFFFD27A), 1.6f)
        },
        Pic("🎉", "Party") {
            oval(12f, 9.6f, 6.4f, 7.4f, Color(0xFFE2558A))
            rotate(20f, Offset(9.6f, 7f)) { oval(9.6f, 7f, 1.5f, 2.4f, Color.White, 0.3f) }
            fill(knot, Color(0xFFE2558A), 1f)
            line(string, Color(0xFFF5F5F4), 1.3f, 0.75f)
            drawCircle(Color(0xFFFFD27A), 1.2f, Offset(4f, 5f))
            drawCircle(Color(0xFFF38A35), 1f, Offset(20.2f, 4.2f))
            drawCircle(Color(0xFF9BD39B), 1.1f, Offset(19.6f, 16.5f))
        },
        Pic("😮", "Wow") {
            drawCircle(FACE, 10f, Offset(12f, 12f))
            line(brows, INK, 1.4f)
            drawCircle(INK, 1.4f, Offset(8.7f, 10.2f))
            drawCircle(INK, 1.4f, Offset(15.3f, 10.2f))
            oval(12f, 15.6f, 2.4f, 2.9f, INK)
        },
    )

    /** [emoji]'s picture (with or without its colour selector, as "❤" and "❤️"), or null. */
    fun of(emoji: String): Pic? {
        val bare = emoji.replace("️", "")
        return all.firstOrNull { it.emoji.replace("️", "") == bare }
    }
}

/** A reaction drawn at [size]: its soft picture, or its emoji as text when it isn't one of the six. */
@Composable
fun ReactionIcon(emoji: String, size: Dp, modifier: Modifier = Modifier) {
    val pic = Reactions.of(emoji)
    if (pic == null) {
        Box(modifier.size(size), contentAlignment = Alignment.Center) { Text(emoji, fontSize = (size.value * 0.55f).sp) }
        return
    }
    Canvas(modifier.size(size)) {
        scale(this.size.minDimension / 24f, pivot = Offset.Zero) { pic.draw(this) }
    }
}

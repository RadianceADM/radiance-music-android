package io.github.radianceadm.music.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * How things move, as the Radiance animation blueprint (RadianceADM/blueprints ANIMATION_BLUEPRINT.md §2, §6.8;
 * android#29): one place for the curves and durations, no inline numbers. Motion only, never how things look.
 */
object Motion {
    val EaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val EaseInOut = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
    val EaseExit = CubicBezierEasing(0.4f, 0f, 1f, 1f) // = FastOutLinearInEasing

    const val PRESS = 100
    const val RELEASE = 160
    const val MICRO = 150
    const val MICRO_OUT = 120
    const val MENU_IN = 200
    const val MENU_OUT = 160
    const val DIALOG_IN = 250
    const val DIALOG_OUT = 200
    const val SHEET_IN = 350
    const val SHEET_OUT = 280
    const val PAGE = 220
    const val SHARED = 400
    const val PULSE = 300
    const val STAGGER = 30
    const val STAGGER_CAP = 10

    fun <T> enter(ms: Int = MENU_IN) = tween<T>(ms, easing = EaseOut)
    fun <T> exit(ms: Int = MENU_OUT) = tween<T>(ms, easing = EaseExit)
    fun <T> move(ms: Int = 250) = tween<T>(ms, easing = EaseInOut)
    /** Everyday spring: no overshoot. */
    fun <T> calm() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}

/** Rule 4: down to 0.97 while pressed (100 ms), back in 160 ms. */
fun Modifier.pressScale(source: InteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(if (pressed) Motion.PRESS else Motion.RELEASE, easing = Motion.EaseOut),
        label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

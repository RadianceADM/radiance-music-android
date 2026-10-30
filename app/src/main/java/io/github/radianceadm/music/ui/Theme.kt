package io.github.radianceadm.music.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Radiance's own colours: navy and a warm orange. */
object Brand {
    val Navy = Color(0xFF0C1D38)
    val Night = Color(0xFF060D1A)
    val Orange = Color(0xFFF38A35)
    val Glow = Color(0xFFFFAE63)
    val Card = Color(0xFF13233F)
    val Dim = Color(0xFFB7C0D0)
    val Heart = Color(0xFFE5364F)
    val Lossless = Color(0xFF8FF0A4)
}

private val colors = darkColorScheme(
    primary = Brand.Orange,
    onPrimary = Color(0xFF1A0E02),
    secondary = Brand.Glow,
    background = Brand.Night,
    onBackground = Color.White,
    surface = Brand.Night,
    onSurface = Color.White,
    surfaceVariant = Brand.Card,
    onSurfaceVariant = Brand.Dim,
    surfaceContainer = Brand.Navy,
    surfaceContainerHigh = Brand.Card,
    surfaceContainerLow = Brand.Night,
    outline = Color(0xFF2A3A57),
)

private val type = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
)

// soft corners everywhere: nothing sharp
private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun RadianceTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, typography = type, shapes = shapes, content = content)

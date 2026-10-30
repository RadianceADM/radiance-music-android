package io.github.radianceadm.music.ui

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's look, as the computer app has them (Settings → Look): True Black (the
 * default) or Stone, warm greys. No navy (owner, 2026-10-29: "wag navy blue … either
 * stone or true black lang"). Kept on the phone; every screen follows a change at once.
 */
object Looks {
    var stone by mutableStateOf(false)
        private set

    fun load(context: Context) {
        stone = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("stone", false)
    }

    fun set(context: Context, stone: Boolean) {
        this.stone = stone
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("stone", stone).apply()
    }

    private const val PREFS = "look"
}

/** Radiance's colours in the look picked, and its warm orange. */
object Brand {
    /** The screen itself. */
    val Night get() = if (Looks.stone) Color(0xFF1C1917) else Color(0xFF000000)
    /** Sheets over it. */
    val Sheet get() = if (Looks.stone) Color(0xFF0C0A09) else Color(0xFF121212)
    /** Cards, fields, pills. */
    val Card get() = if (Looks.stone) Color(0xFF292524) else Color(0xFF1C1C1E)
    /** Quiet words. */
    val Dim get() = if (Looks.stone) Color(0xFFA8A29E) else Color(0xFF9A9A9F)
    val Line get() = if (Looks.stone) Color(0xFF44403C) else Color(0xFF2C2C2E)
    val Orange = Color(0xFFF38A35)
    val Glow = Color(0xFFFFAE63)
    val Heart = Color(0xFFE5364F)
    val Lossless = Color(0xFF8FF0A4)
}

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
fun RadianceTheme(content: @Composable () -> Unit) {
    // read here, so a change of look redraws everything
    val colors = darkColorScheme(
        primary = Brand.Orange,
        onPrimary = Color(0xFF1A0E02),
        secondary = Brand.Glow,
        background = Brand.Night,
        onBackground = Color.White,
        surface = Brand.Night,
        onSurface = Color.White,
        surfaceVariant = Brand.Card,
        onSurfaceVariant = Brand.Dim,
        surfaceContainer = Brand.Sheet,
        surfaceContainerHigh = Brand.Card,
        surfaceContainerLow = Brand.Night,
        outline = Brand.Line,
    )
    MaterialTheme(colorScheme = colors, typography = type, shapes = shapes, content = content)
}

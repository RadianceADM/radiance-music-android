package io.github.radianceadm.music.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws the start's sunrise at a few moments (a phone's screen), to look at on the build server. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class SplashTest {
    @Test fun draws() {
        val dir = File("/srv/build/android/splittest")
        val (w, h) = 1080 to 2340
        val open = Splash.openFrom(Splash.SHOWN_S)
        for (ms in listOf(300, 1200, 2000, 2700, 3200, 3700, 4200, 4600, 4900, 5200)) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val t = ms / 1000f
            CanvasDrawScope().draw(Density(3f), LayoutDirection.Ltr, Canvas(android.graphics.Canvas(bmp)), Size(w.toFloat(), h.toFloat())) {
                with(Splash) { splash(t, Splash.SHOWN_S, open) }
            }
            // drawn all over (never see-through) until the dark opens; then the middle goes first
            if (t < open) assertEquals(255, android.graphics.Color.alpha(bmp.getPixel(10, 10)))
            if (t > open + 0.2f) {
                val (middle, corner) = android.graphics.Color.alpha(bmp.getPixel(w / 2, h / 4)) to android.graphics.Color.alpha(bmp.getPixel(10, 10))
                assertTrue(middle < corner || corner == 0) // (all open at the end: both gone)
            }
            if (dir.canWrite()) File(dir, "splash-$ms.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(Splash.end(open) > open)
    }
}

package io.github.radianceadm.music.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The share's QR as drawn (round dots, rounded eyes, navy on white) still reads back, at phone-camera sizes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class QrTest {
    private fun draw(text: String, px: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
        val n = m.width
        val pad = px * 18 / 300
        val out = Bitmap.createBitmap(px + 2 * pad, px + 2 * pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0C1D38.toInt() }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val cell = px.toFloat() / n
        fun eye(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
        for (y in 0 until n) for (x in 0 until n) if (m.get(x, y) && !eye(x, y)) c.drawCircle(pad + (x + 0.5f) * cell, pad + (y + 0.5f) * cell, cell * 0.46f, ink)
        for ((ex, ey) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            val ox = pad + ex * cell; val oy = pad + ey * cell
            c.drawRoundRect(RectF(ox, oy, ox + 7 * cell, oy + 7 * cell), cell * 2.2f, cell * 2.2f, ink)
            c.drawRoundRect(RectF(ox + cell, oy + cell, ox + 6 * cell, oy + 6 * cell), cell * 1.5f, cell * 1.5f, white)
            c.drawRoundRect(RectF(ox + 2 * cell, oy + 2 * cell, ox + 5 * cell, oy + 5 * cell), cell * 1.1f, cell * 1.1f, ink)
        }
        return out
    }

    private fun read(b: Bitmap): String {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
        return reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(b.width, b.height, px)))).text
    }

    @Test fun readsBack() {
        // the longest a ticket gets: both ways in, a long phone name
        val t = Wire.Ticket("get", "192.168.100.123", 45678, Wire.newKey(), "Galaxy S24 Ultra ni Bassi", "DIRECT-rm-Radiance-ab12", "k3fq9xw2mz7p", "192.168.49.1").toUri()
        for (px in listOf(180, 300, 600)) {
            val b = draw(t, px)
            if (px == 600) File("/srv/build/android/splittest").takeIf { it.isDirectory }?.let { d -> File(d, "qr.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            assertEquals("at $px px", t, read(b))
        }
    }
}

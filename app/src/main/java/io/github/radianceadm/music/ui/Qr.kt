package io.github.radianceadm.music.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors

/**
 * [text] as a QR code: soft round dots and rounded eyes (no hard squares),
 * dark on white so every camera reads it.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
    }
    val ink = Brand.Sheet
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).padding(18.dp)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val n = matrix.width
            val cell = size.width / n
            fun eye(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
            for (y in 0 until n) for (x in 0 until n) {
                if (!matrix.get(x, y) || eye(x, y)) continue
                drawCircle(ink, radius = cell * 0.46f, center = Offset((x + 0.5f) * cell, (y + 0.5f) * cell))
            }
            // the three eyes: a rounded ring with a rounded centre
            for ((ex, ey) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                val o = Offset(ex * cell, ey * cell)
                drawRoundRect(ink, o, Size(7 * cell, 7 * cell), CornerRadius(cell * 2.2f))
                drawRoundRect(Color.White, o + Offset(cell, cell), Size(5 * cell, 5 * cell), CornerRadius(cell * 1.5f))
                drawRoundRect(ink, o + Offset(2 * cell, 2 * cell), Size(3 * cell, 3 * cell), CornerRadius(cell * 1.1f))
            }
        }
    }
}

/** The camera, reading QR codes; [onFound] gets the first one's words. Asks for the camera once. */
@Composable
fun QrScanner(onFound: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it; asked = true }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        if (!allowed) {
            Text(if (asked) "The camera isn't allowed, so it can't read the QR. Allow it in the phone's Settings → Apps → Radiance Music." else "Allow the camera to read the QR.", color = Color.White, modifier = Modifier.padding(24.dp))
            return@Box
        }
        var found by remember { mutableStateOf(false) }
        val worker = remember { Executors.newSingleThreadExecutor() }
        DisposableEffect(Unit) { onDispose { worker.shutdown() } }
        AndroidView(factory = { c ->
            val view = PreviewView(c)
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(worker) { image ->
                    try {
                        if (found) return@setAnalyzer
                        val plane = image.planes[0] // brightness is all a QR needs
                        val buf = plane.buffer
                        val data = ByteArray(buf.remaining()).also { buf.get(it) }
                        val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                        val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
                        reader.reset()
                        if (text != null && !found) {
                            found = true
                            ContextCompat.getMainExecutor(c).execute { onFound(text) }
                        }
                    } finally {
                        image.close()
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(c))
            view
        }, onRelease = { runCatching { ProcessCameraProvider.getInstance(it.context).get().unbindAll() } }, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
    }
}

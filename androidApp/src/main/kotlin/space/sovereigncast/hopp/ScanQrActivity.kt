package space.sovereigncast.hopp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads a QR code with the camera and returns its text (device feedback
 * 2026-10-14: a wallet's NWC connection could only be pasted). CameraX takes
 * the picture, ZXing reads it — no Google services, so the foss flavour has
 * it too. The camera is asked for here, when the listener wants to scan. No
 * picture is stored: each frame is read in memory, its copy wiped, and the
 * frame handed back to the camera at once (device feedback 2026-10-14). The
 * window is kept out of screenshots — the code it shows can spend.
 */
class ScanQrActivity : ComponentActivity() {
    private val analysis = Executors.newSingleThreadExecutor()
    private val done = AtomicBoolean(false)

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) show() else { Toast.makeText(this, R.string.scan_no_camera, Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) show()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        analysis.shutdown()
        super.onDestroy()
    }

    private fun show() = setContent {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(::preview, Modifier.fillMaxSize())
            // Where to hold the code.
            Box(Modifier.align(Alignment.Center).size(260.dp).border(3.dp, Color(0xFFF7931A), RoundedCornerShape(24.dp)))
            Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    getString(R.string.scan_hint), Modifier.padding(bottom = 16.dp),
                    style = TextStyle(color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center),
                )
                BasicText(
                    getString(R.string.scan_close),
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0x66FFFFFF)).clickable { finish() }.padding(horizontal = 28.dp, vertical = 12.dp),
                    style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }

    private fun preview(context: android.content.Context): PreviewView {
        val view = PreviewView(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val reader = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            reader.setAnalyzer(analysis) { image -> read(image) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, reader)
        }, ContextCompat.getMainExecutor(context))
        return view
    }

    private val zxing = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    /** The luminance plane is all ZXing needs; a dark-on-light code and an inverted one. */
    private fun read(image: ImageProxy) {
        image.use {
            if (done.get()) return
            val plane = it.planes[0]
            val bytes = ByteArray(plane.buffer.remaining()).also { b -> plane.buffer.get(b) }
            val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
            val text = try {
                listOf(source, source.invert()).firstNotNullOfOrNull { s ->
                    try { zxing.decodeWithState(BinaryBitmap(HybridBinarizer(s))).text } catch (e: NotFoundException) { null } finally { zxing.reset() }
                }
            } finally { bytes.fill(0) } ?: return
            if (!done.compareAndSet(false, true)) return
            runOnUiThread { setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text)); finish() }
        }
    }

    /** Starts the scanner; the text of the code, or null when closed. */
    class Scan : ActivityResultContract<Unit, String?>() {
        override fun createIntent(context: android.content.Context, input: Unit) = Intent(context, ScanQrActivity::class.java)
        override fun parseResult(resultCode: Int, intent: Intent?) = intent?.getStringExtra(EXTRA_TEXT)?.takeIf { resultCode == RESULT_OK }
    }

    companion object {
        private const val EXTRA_TEXT = "text"
    }
}

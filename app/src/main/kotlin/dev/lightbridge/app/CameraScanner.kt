// SPDX-License-Identifier: MIT
package dev.lightbridge.app

import android.annotation.SuppressLint
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import java.util.concurrent.Executors

/** Camera and decoder stay local: no Play Services model download, no internet permission. */
@SuppressLint("MissingPermission") // Parent only composes after checking CAMERA permission.
@Composable
internal fun CameraScanner(
    modifier: Modifier, torch: Boolean, zoom: Float,
    onFrame: (ByteArray) -> Unit, onError: (String) -> Unit, onCamera: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    } }
    val frameCallback by rememberUpdatedState(onFrame)
    val errorCallback by rememberUpdatedState(onError)
    val cameraCallback by rememberUpdatedState(onCamera)
    var camera by remember { mutableStateOf<Camera?>(null) }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }
    LaunchedEffect(camera, zoom) { camera?.cameraControl?.setLinearZoom(zoom) }
    DisposableEffect(lifecycle, previewView) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(1920, 1080)).build()
        analysis.setAnalyzer(executor) { image ->
            try {
                val plane = image.planes[0]
                val bytes = ByteArray(image.width * image.height)
                val source = plane.buffer.duplicate()
                val base = source.position()
                for (y in 0 until image.height) for (x in 0 until image.width)
                    bytes[y * image.width + x] = source.get(base + y * plane.rowStride + x * plane.pixelStride)
                val luminance = PlanarYUVLuminanceSource(bytes, image.width, image.height, 0, 0, image.width, image.height, false)
                val results = QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(luminance)),
                    mapOf(DecodeHintType.TRY_HARDER to true))
                for (result in results) {
                    val segments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? Iterable<*> ?: continue
                    val payloads = segments.filterIsInstance<ByteArray>()
                    val length = payloads.sumOf { it.size }
                    if (length !in 21..2953) continue
                    val data = ByteArray(length)
                    var offset = 0
                    for (segment in payloads) { segment.copyInto(data, offset); offset += segment.size }
                    frameCallback(data)
                }
            } catch (_: com.google.zxing.ReaderException) {
                // A blurred / partially displayed code is an erasure. The fountain will recover it.
            } catch (_: IllegalArgumentException) {
                // Some camera providers deliver a truncated plane while shutting down.
            } catch (_: IndexOutOfBoundsException) {
                // Ignore that one malformed image, always release ImageProxy below.
            } finally { image.close() }
        }
        future.addListener({
            if (!disposed) {
                try {
                    provider = future.get()
                    val selector = if (provider!!.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                        CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    camera = provider!!.bindToLifecycle(lifecycle, selector, preview, analysis)
                    cameraCallback(camera!!.cameraInfo.hasFlashUnit())
                } catch (e: Exception) { errorCallback("Camera unavailable. Close other camera apps and retry. ${e.message.orEmpty()}") }
            }
        }, main)
        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            camera = null
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

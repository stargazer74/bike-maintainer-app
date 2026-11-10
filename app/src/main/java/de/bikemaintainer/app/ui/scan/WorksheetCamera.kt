package de.bikemaintainer.app.ui.scan

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.bikemaintainer.app.domain.scan.GrayImage
import java.util.concurrent.Executors

/**
 * Live preview of the back camera and a shutter button. The picture is taken into memory (no file, no gallery),
 * turned upright and handed on as grey image; the camera frame is closed right away.
 */
@Composable
internal fun WorksheetCamera(onPhoto: (GrayImage) -> Unit, onFailed: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentOnPhoto = rememberUpdatedState(onPhoto)
    val currentOnFailed = rememberUpdatedState(onFailed)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            // About 12 MP is plenty for 6 mm boxes and keeps memory in check.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(4032, 3024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build(),
            )
            .build()
    }
    val worker = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener(
            {
                try {
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider { request -> surfaceRequest = request } }
                    p.unbindAll()
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                } catch (_: Exception) {
                    currentOnFailed.value()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose { provider?.unbindAll() }
    }
    DisposableEffect(Unit) { onDispose { worker.shutdown() } }
    Box(Modifier.fillMaxSize()) {
        surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
        SheetFrame(Modifier.fillMaxSize())
        CameraHints(
            onCapture = {
                capture.takePicture(
                    worker,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val gray = try {
                                image.toGrayImage()
                            } catch (_: Exception) {
                                null
                            } catch (_: OutOfMemoryError) {
                                null
                            } finally {
                                image.close()
                            }
                            ContextCompat.getMainExecutor(context).execute {
                                if (gray == null) currentOnFailed.value() else currentOnPhoto.value(gray)
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            ContextCompat.getMainExecutor(context).execute { currentOnFailed.value() }
                        }
                    },
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Upright grey copy of the captured frame; the bitmap is converted row by row and recycled. */
private fun ImageProxy.toGrayImage(): GrayImage {
    val bitmap = toBitmap()
    try {
        val w = bitmap.width
        val h = bitmap.height
        val gray = GrayImage(w, h)
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                gray.pixels[y * w + x] = ((((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000).toByte()
            }
        }
        return gray.rotated(imageInfo.rotationDegrees)
    } finally {
        bitmap.recycle()
    }
}

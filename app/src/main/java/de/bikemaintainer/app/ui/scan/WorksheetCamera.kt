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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.bikemaintainer.app.domain.scan.GrayImage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

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
    // Callbacks that arrive after the screen is gone are dropped instead of crashing the app.
    val callbacks = remember { Executor { task -> if (!worker.isShutdown) runCatching { worker.execute(task) } } }
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
        // Unbinding aborts a running capture and reports it to the worker, so the worker stops only afterwards.
        onDispose {
            provider?.unbindAll()
            worker.shutdown()
        }
    }
    // A capture that never returns (no frames, e.g. a camera without picture) ends as a failure instead of hanging.
    var capturing by remember { mutableStateOf(false) }
    LaunchedEffect(capturing) {
        if (capturing) {
            delay(CAPTURE_TIMEOUT_MILLIS)
            capturing = false
            currentOnFailed.value()
        }
    }
    val done: (GrayImage?) -> Unit = { gray ->
        if (capturing) {
            capturing = false
            if (gray == null) currentOnFailed.value() else currentOnPhoto.value(gray)
        }
    }
    // Preview and frame above, hints and shutter below, so the text never covers the frame.
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
            SheetFrame(Modifier.fillMaxSize())
        }
        CameraHints(
            capturing = capturing,
            onCapture = {
                capturing = true
                capture.takePicture(
                    callbacks,
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
                            ContextCompat.getMainExecutor(context).execute { done(gray) }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            ContextCompat.getMainExecutor(context).execute { done(null) }
                        }
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val CAPTURE_TIMEOUT_MILLIS = 15_000L

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

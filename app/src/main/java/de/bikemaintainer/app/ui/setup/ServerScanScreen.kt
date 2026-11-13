package de.bikemaintainer.app.ui.setup

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.bikemaintainer.app.R
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.WithCameraPermission
import java.util.concurrent.Executors
import zxingcpp.BarcodeReader

/**
 * Reads the server address from a QR code (e.g. the one the web app of a household server shows) and hands it over
 * once via [onAddress]. Asks for the camera permission only here. A code without an http(s) address shows a hint and
 * scanning goes on.
 */
@Composable
fun ServerScanScreen(onBack: () -> Unit, onAddress: (String) -> Unit) {
    var noAddress by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var handedOver by remember { mutableStateOf(false) }
    val currentOnAddress by rememberUpdatedState(onAddress)
    ServerScanContent(onBack = onBack, noAddress = noAddress, failed = failed) {
        WithCameraPermission(stringResource(R.string.server_scan_permission_needed)) {
            QrCamera(
                onText = { text ->
                    val address = ServerQr.address(text)
                    if (address == null) {
                        noAddress = true
                    } else if (!handedOver) {
                        handedOver = true
                        currentOnAddress(address)
                    }
                },
                onFailed = { failed = true },
            )
        }
    }
}

/** Stateless screen; [camera] is the live preview (replaced in tests, which have no camera). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScanContent(onBack: () -> Unit, noAddress: Boolean, failed: Boolean, camera: @Composable () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_scan_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                if (failed) {
                    ErrorText(stringResource(R.string.server_scan_failed), Modifier.padding(16.dp))
                } else {
                    camera()
                }
            }
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.server_scan_hint), style = MaterialTheme.typography.bodyLarge)
                if (noAddress) {
                    ErrorText(
                        stringResource(R.string.server_scan_no_address),
                        Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

/**
 * Back camera preview plus continuous analysis of the latest frame with zxing-cpp (offline, no Google services). Frames
 * stay in memory and are dropped right after reading; [onText] gets the text of each QR code found, on the main thread.
 */
@Composable
private fun QrCamera(onText: (String) -> Unit, onFailed: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    val currentOnFailed by rememberUpdatedState(onFailed)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    DisposableEffect(owner) {
        val worker = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val reader = BarcodeReader().apply { options.formats = setOf(BarcodeReader.Format.QR_CODE) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analysis.setAnalyzer(worker) { image ->
            val text = try {
                reader.read(image).firstNotNullOfOrNull { it.text }
            } catch (_: Exception) {
                null
            } finally {
                image.close()
            }
            if (text != null) main.execute { currentOnText(text) }
        }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener(
            {
                try {
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider { request -> surfaceRequest = request } }
                    p.unbindAll()
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) {
                    currentOnFailed()
                }
            },
            main,
        )
        onDispose {
            provider?.unbindAll()
            analysis.clearAnalyzer()
            worker.shutdown()
        }
    }
    surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
}

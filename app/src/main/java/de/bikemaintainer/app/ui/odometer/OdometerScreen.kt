package de.bikemaintainer.app.ui.odometer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.odometer.ODO_PHOTO_RATIO
import de.bikemaintainer.app.domain.odometer.Point
import de.bikemaintainer.app.domain.scan.GrayImage
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.LoadingIndicator
import de.bikemaintainer.app.ui.common.WithCameraPermission
import de.bikemaintainer.app.ui.scan.PhotoCamera
import de.bikemaintainer.app.ui.scan.toImageBitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun OdometerScreen(
    viewModel: OdometerViewModel,
    onBack: () -> Unit,
    onAccepted: (Int) -> Unit,
    camera: @Composable () -> Unit = {
        WithCameraPermission(stringResource(R.string.odo_permission_needed)) {
            PhotoCamera(
                hint = stringResource(R.string.odo_hint),
                frame = { OdoFrame(it) },
                onPhoto = viewModel::onPhoto,
                onFailed = viewModel::onCaptureFailed,
                torch = true,
            )
        }
    },
) {
    val state by viewModel.state.collectAsState()
    val currentOnAccepted by rememberUpdatedState(onAccepted)
    LaunchedEffect(state) {
        (state as? OdometerUiState.Done)?.let { currentOnAccepted(it.value) }
    }
    OdometerContent(
        state = state,
        onBack = onBack,
        actions = OdometerActions(
            onText = viewModel::onText,
            onAccept = viewModel::accept,
            onAdjust = viewModel::adjust,
            onFrame = viewModel::onFrame,
            onCancelAdjust = viewModel::cancelAdjust,
            onRetake = viewModel::retake,
        ),
        camera = camera,
    )
}

data class OdometerActions(
    val onText: (String) -> Unit = {},
    val onAccept: () -> Unit = {},
    val onAdjust: () -> Unit = {},
    /** The frame drawn by the user, as corners on the shown photo. */
    val onFrame: (List<Point>) -> Unit = {},
    val onCancelAdjust: () -> Unit = {},
    val onRetake: () -> Unit = {},
)

/** Stateless screen; [camera] is the live preview (replaced in tests, which have no camera). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OdometerContent(state: OdometerUiState, onBack: () -> Unit, actions: OdometerActions, camera: @Composable () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.odo_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                OdometerUiState.Camera -> camera()
                OdometerUiState.Reading, is OdometerUiState.Done -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LoadingIndicator()
                    Text(stringResource(R.string.odo_reading), Modifier.padding(top = 16.dp))
                }
                is OdometerUiState.Review -> Review(state, actions)
                is OdometerUiState.Adjust -> Adjust(state.photo, actions)
                is OdometerUiState.Problem -> Problem(state, actions)
            }
        }
    }
}

@Composable
private fun Review(state: OdometerUiState.Review, actions: OdometerActions) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(if (state.unsure) R.string.odo_review_check else R.string.odo_review_compare),
            style = MaterialTheme.typography.bodyMedium,
        )
        PhotoWithLine(state.photo, state.corners)
        state.strip?.let { strip ->
            val bitmap = remember(strip) { strip.toImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.odo_strip_description),
                // Scaled up from its 32 px, so the digits can be compared with the odometer.
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            )
        }
        OutlinedTextField(
            value = state.text,
            onValueChange = actions.onText,
            label = { Text(stringResource(R.string.mileage_label)) },
            singleLine = true,
            isError = state.unsure,
            supportingText = if (state.unsure) {
                { Text(stringResource(R.string.scan_uncertain)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = actions.onAccept, enabled = state.value != null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.scan_accept))
        }
        Text(stringResource(R.string.odo_review_frame), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = actions.onAdjust, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.odo_adjust))
        }
        OutlinedButton(onClick = actions.onRetake, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.scan_retake))
        }
    }
}

/** The photo with the read line framed in the accent colour. */
@Composable
private fun PhotoWithLine(photo: GrayImage, corners: List<Point>) {
    val bitmap = remember(photo) { photo.toImageBitmap() }
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().aspectRatio(photo.width.toFloat() / photo.height)) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.odo_photo_description),
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val s = size.width / photo.width
            val path = Path().apply {
                corners.forEachIndexed { i, p ->
                    if (i == 0) moveTo(p.x.toFloat() * s, p.y.toFloat() * s) else lineTo(p.x.toFloat() * s, p.y.toFloat() * s)
                }
                close()
            }
            drawPath(path, accent, style = Stroke(width = 3.dp.toPx()))
        }
    }
}

/** The user drags a frame around the line on the photo, from one corner to the opposite one. */
@Composable
private fun Adjust(photo: GrayImage, actions: OdometerActions) {
    val bitmap = remember(photo) { photo.toImageBitmap() }
    val accent = MaterialTheme.colorScheme.primary
    // Corners of the drawn frame in photo pixels; null until the user draws.
    var start by remember(photo) { mutableStateOf<Offset?>(null) }
    var end by remember(photo) { mutableStateOf<Offset?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.odo_adjust_hint), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth().aspectRatio(photo.width.toFloat() / photo.height)) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.odo_adjust_photo_description),
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(
                Modifier.fillMaxSize().pointerInput(photo) {
                    val scale = photo.width / size.width.toFloat()
                    fun inPhoto(o: Offset) = Offset(
                        (o.x * scale).coerceIn(0f, photo.width.toFloat()),
                        (o.y * scale).coerceIn(0f, photo.height.toFloat()),
                    )
                    // From where the finger touches down (no touch slop, which would move the corner), and every move
                    // is consumed so the page does not scroll while drawing.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        start = inPhoto(down.position)
                        end = start
                        drag(down.id) { change ->
                            end = inPhoto(change.position)
                            change.consume()
                        }
                    }
                },
            ) {
                val a = start
                val b = end
                if (a != null && b != null) {
                    val s = size.width / photo.width
                    drawRect(
                        accent,
                        Offset(min(a.x, b.x) * s, min(a.y, b.y) * s),
                        Size(abs(a.x - b.x) * s, abs(a.y - b.y) * s),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
        }
        val frame = frameOf(start, end)
        Button(onClick = { frame?.let(actions.onFrame) }, enabled = frame != null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.odo_adjust_read))
        }
        OutlinedButton(onClick = actions.onCancelAdjust, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cancel))
        }
    }
}

/** The drawn rectangle as corners clockwise from the top left, or null while it is too small to hold digits. */
internal fun frameOf(a: Offset?, b: Offset?): List<Point>? {
    if (a == null || b == null) return null
    val left = min(a.x, b.x).toDouble()
    val right = max(a.x, b.x).toDouble()
    val top = min(a.y, b.y).toDouble()
    val bottom = max(a.y, b.y).toDouble()
    if (right - left < MIN_FRAME || bottom - top < MIN_FRAME / 2) return null
    return listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))
}

/** Smallest frame width in shown photo pixels (about 4 % of its width). */
private const val MIN_FRAME = 40.0

@Composable
private fun Problem(state: OdometerUiState.Problem, actions: OdometerActions) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val message = when (state.problem) {
            OdometerProblem.NO_LINE -> R.string.odo_error_no_line
            OdometerProblem.FAILED -> R.string.scan_error_failed
        }
        ErrorText(stringResource(message))
        if (state.photo != null) {
            OutlinedButton(onClick = actions.onAdjust, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.odo_adjust_self))
            }
        }
        Button(onClick = actions.onRetake, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_retake)) }
    }
}

/** The 4:3 frame the models read (see [de.bikemaintainer.app.domain.odometer.odoPhotoArea]). */
@Composable
internal fun OdoFrame(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = min(size.width, size.height * ODO_PHOTO_RATIO.toFloat()) * 0.94f
        val h = w / ODO_PHOTO_RATIO.toFloat()
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        drawRect(Color.White, Offset(left, top), Size(w, h), style = Stroke(width = 2.dp.toPx()))
    }
}

/** The camera button next to a mileage field (app#40): photograph the odometer instead of typing. */
@Composable
fun OdometerCameraButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = stringResource(R.string.odo_open))
    }
}

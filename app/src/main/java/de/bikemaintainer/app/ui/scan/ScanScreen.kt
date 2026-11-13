package de.bikemaintainer.app.ui.scan

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.ui.common.WithCameraPermission
import de.bikemaintainer.app.domain.ValveKind
import de.bikemaintainer.app.domain.scan.GrayImage
import de.bikemaintainer.app.domain.scan.ScanLayout
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.LoadingIndicator
import de.bikemaintainer.app.ui.common.nameRes
import de.bikemaintainer.app.ui.common.valveLabel
import de.bikemaintainer.app.ui.logmaintenance.ScanTransfer

@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    onBack: () -> Unit,
    onAccepted: (ScanTransfer) -> Unit,
    camera: @Composable () -> Unit = {
        CameraWithPermission(onPhoto = viewModel::onPhoto, onFailed = viewModel::onCaptureFailed)
    },
) {
    val state by viewModel.state.collectAsState()
    val progress by viewModel.progress.collectAsState()
    // Once a page of a multi-page sheet is taken over, back (arrow or gesture) hands the collected pages over like
    // "Done" instead of dropping them.
    val back: () -> Unit = { if (viewModel.progress.value != null) viewModel.finish() else onBack() }
    BackHandler(enabled = progress != null) { viewModel.finish() }
    val currentOnAccepted by rememberUpdatedState(onAccepted)
    LaunchedEffect(state) {
        (state as? ScanUiState.Done)?.let { currentOnAccepted(it.transfer) }
    }
    ScanContent(
        state = state,
        progress = progress,
        onBack = back,
        actions = ScanActions(
            onValue = viewModel::onValue,
            onChecked = viewModel::onChecked,
            onAccept = viewModel::accept,
            onRetake = viewModel::retake,
            onFinish = viewModel::finish,
        ),
        camera = camera,
    )
}

data class ScanActions(
    val onValue: (Int, String) -> Unit,
    val onChecked: (Int, Boolean) -> Unit,
    val onAccept: () -> Unit,
    val onRetake: () -> Unit,
    /** Takes the pages of a multi-page sheet collected so far over. */
    val onFinish: () -> Unit = {},
)

/** Stateless screen; [camera] is the live preview (replaced in tests, which have no camera). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanContent(
    state: ScanUiState,
    onBack: () -> Unit,
    actions: ScanActions,
    progress: SheetProgress? = null,
    camera: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (progress != null && state != ScanUiState.Reading && state !is ScanUiState.Done) {
                ProgressBanner(progress, actions.onFinish)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { Phase(state, progress, actions, camera) }
        }
    }
}

/** Which pages are taken over, what to photograph next, and "Done" to take the collected values over. */
@Composable
private fun ProgressBanner(progress: SheetProgress, onFinish: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.scan_progress, progress.done.sorted().joinToString(", "), progress.pages),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            progress.nextPage?.let {
                Text(
                    stringResource(R.string.scan_next_page, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        TextButton(onClick = onFinish) { Text(stringResource(R.string.scan_finish)) }
    }
}

@Composable
private fun Phase(state: ScanUiState, progress: SheetProgress?, actions: ScanActions, camera: @Composable () -> Unit) {
    when (state) {
        ScanUiState.Camera -> camera()
        ScanUiState.Reading, is ScanUiState.Done -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LoadingIndicator()
            Text(stringResource(R.string.scan_reading), Modifier.padding(top = 16.dp))
        }
        is ScanUiState.Problem -> Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ErrorText(stringResource(state.problem.messageRes()), Modifier.padding(bottom = 16.dp))
            Button(onClick = actions.onRetake) { Text(stringResource(R.string.scan_retake)) }
        }
        is ScanUiState.Review -> Review(state, progress, actions)
    }
}

@Composable
private fun Review(state: ScanUiState.Review, progress: SheetProgress?, actions: ScanActions) {
    // Every page starts at the top, not where the previous page was scrolled to.
    val listState = key(state.code.page) { rememberLazyListState() }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val code = state.code
            Text(
                stringResource(R.string.scan_review_title, stringResource(code.template.nameRes()), code.page, code.pages),
                style = MaterialTheme.typography.titleMedium,
            )
            val hint = when {
                state.fields.isEmpty() -> stringResource(R.string.scan_review_empty)
                state.uncertainCount > 0 -> stringResource(R.string.scan_review_check, state.uncertainCount)
                else -> stringResource(R.string.scan_review_compare)
            }
            Text(hint, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
        }
        itemsIndexed(state.fields, key = { _, f -> f.field.id }) { index, field ->
            FieldRow(field, onValue = { actions.onValue(index, it) }, onChecked = { actions.onChecked(index, it) })
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // With pages still missing after this one, taking over leads on to the next page.
                val code = state.code
                val next = (1..code.pages).firstOrNull { it != code.page && it !in progress?.done.orEmpty() }
                Button(onClick = actions.onAccept, modifier = Modifier.fillMaxWidth()) {
                    Text(if (next == null) stringResource(R.string.scan_accept) else stringResource(R.string.scan_accept_next, next))
                }
                OutlinedButton(onClick = actions.onRetake, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.scan_retake))
                }
            }
        }
    }
}

/** The photographed field next to the recognised value; unsure values get the error colour and a note. */
@Composable
private fun FieldRow(field: ReviewField, onValue: (String) -> Unit, onChecked: (Boolean) -> Unit) {
    val label = fieldLabel(field.field.id)
    val error = MaterialTheme.colorScheme.error
    OutlinedCard(
        border = if (field.uncertain) BorderStroke(2.dp, error) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val bitmap = remember(field.crop) { field.crop.toImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.scan_crop_description, label),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 72.dp),
            )
            if (field.field.kind == ScanLayout.FieldKind.CHECKBOX) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = field.checked, role = Role.Checkbox, onValueChange = onChecked),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = field.checked, onCheckedChange = null)
                    Text(label, Modifier.weight(1f))
                }
            } else {
                OutlinedTextField(
                    value = field.text,
                    onValueChange = onValue,
                    label = { Text(label) },
                    singleLine = true,
                    isError = field.uncertain,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (field.uncertain) Text(stringResource(R.string.scan_uncertain), color = error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The label of the matching field in the log form, e.g. "Cyl. 2 exhaust 1 · New shim". */
@Composable
internal fun fieldLabel(id: String): String {
    val parts = id.split('.')
    if (parts.size == 5 && parts[0] == "valves") {
        val kind = ValveKind.entries.firstOrNull { it.name == parts[2] } ?: return id
        val valve = valveLabel(parts[1].toIntOrNull() ?: 0, kind, parts[3].toIntOrNull() ?: 0, showIndex = true)
        val what = when (parts[4]) {
            "measured" -> R.string.measure_valve_measured
            "shimOld" -> R.string.measure_valve_shim_old
            else -> R.string.measure_valve_shim_new
        }
        return "$valve · ${stringResource(what)}"
    }
    val res = when (id) {
        "brakes.padFront" -> R.string.measure_brakes_pad_front
        "brakes.padRear" -> R.string.measure_brakes_pad_rear
        "brakes.discFront" -> R.string.measure_brakes_disc_front
        "brakes.discRear" -> R.string.measure_brakes_disc_rear
        "chain.slack" -> R.string.measure_chain_slack
        "chain.wearLength" -> R.string.measure_chain_wear_length
        "chain.cleaned" -> R.string.measure_chain_cleaned
        "chain.lubricated" -> R.string.measure_chain_lubricated
        "tires.pressureFront" -> R.string.measure_tires_pressure_front
        "tires.pressureRear" -> R.string.measure_tires_pressure_rear
        "tires.treadFront" -> R.string.measure_tires_tread_front
        "tires.treadRear" -> R.string.measure_tires_tread_rear
        "tires.dotFront" -> R.string.measure_tires_dot_front
        "tires.dotRear" -> R.string.measure_tires_dot_rear
        else -> return id
    }
    return stringResource(res)
}

private fun ScanProblem.messageRes(): Int = when (this) {
    ScanProblem.NO_SHEET -> R.string.scan_error_no_sheet
    ScanProblem.NO_CODE -> R.string.scan_error_no_code
    ScanProblem.NEWER_LAYOUT -> R.string.scan_error_newer_layout
    ScanProblem.DEMO_SHEET -> R.string.scan_error_demo_sheet
    ScanProblem.OTHER_VEHICLE -> R.string.scan_error_other_vehicle
    ScanProblem.WRONG_TEMPLATE -> R.string.scan_error_template
    ScanProblem.OTHER_SHEET -> R.string.scan_error_other_sheet
    ScanProblem.FAILED -> R.string.scan_error_failed
}

internal fun GrayImage.toImageBitmap(): ImageBitmap {
    val argb = IntArray(width * height) { i ->
        val v = pixels[i].toInt() and 0xff
        (0xff shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Asks for the camera permission only now – when the user wants to photograph a sheet for the first time. */
@Composable
private fun CameraWithPermission(onPhoto: (GrayImage) -> Unit, onFailed: () -> Unit) {
    WithCameraPermission(stringResource(R.string.scan_permission_needed)) { WorksheetCamera(onPhoto, onFailed) }
}

/** Frame in A4 proportions with the four corner squares, as a guide where to hold the sheet. */
@Composable
internal fun SheetFrame(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val ratio = ScanLayout.PAGE_WIDTH / ScanLayout.PAGE_HEIGHT
        val h = minOf(size.height * 0.9f, size.width * 0.9f / ratio)
        val w = h * ratio
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        drawRect(Color.White, Offset(left, top), Size(w, h), style = Stroke(width = 2.dp.toPx()))
        val m = w * ScanLayout.MARKER_SIZE / ScanLayout.PAGE_WIDTH
        val inset = w * ScanLayout.MARKER_INSET / ScanLayout.PAGE_WIDTH
        listOf(left + inset to top + inset, left + w - inset - m to top + inset, left + inset to top + h - inset - m, left + w - inset - m to top + h - inset - m)
            .forEach { (x, y) -> drawRect(Color.White, Offset(x, y), Size(m, m), style = Stroke(width = 2.dp.toPx())) }
    }
}

@Composable
internal fun CameraHints(capturing: Boolean, onCapture: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.scan_hint), color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.scan_privacy), color = Color.White, style = MaterialTheme.typography.bodySmall)
        // Disabled while the picture is taken, so a second tap does not start another capture.
        Button(onClick = onCapture, enabled = !capturing, modifier = Modifier.width(220.dp)) {
            if (capturing) LoadingIndicator(small = true) else Text(stringResource(R.string.scan_capture))
        }
    }
}

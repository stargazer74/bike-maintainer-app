package de.bikemaintainer.app.domain.scan

import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.ValveKind

/**
 * Where the fields sit on a printed worksheet – the twin of the backend's `WorksheetScanLayout` (layout version 1,
 * see `docs/worksheet-scan-layout.md` in bike-maintainer-backend). Both are tested against the same
 * `worksheet-scan-layout-v1.json`. PDF points on A4, origin top left. Float arithmetic in the same order as the
 * backend, so the numbers match exactly.
 */
object ScanLayout {
    const val VERSION = 1

    const val PAGE_WIDTH = 595.2756f
    const val PAGE_HEIGHT = 841.8898f
    const val MARKER_SIZE = 18f
    const val MARKER_INSET = 20f
    const val QR_SIZE = 64f
    const val QR_LEFT = PAGE_WIDTH - 50f - QR_SIZE
    const val QR_TOP = 80f
    const val DIGIT_WIDTH = 18f
    const val DIGIT_HEIGHT = 24f
    const val SEPARATOR_WIDTH = 8f
    const val CHECKBOX_SIZE = 16f

    private const val CONTENT_LEFT = 50f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * CONTENT_LEFT
    private const val HEADING_HEIGHT = 20f
    private const val HEADER_ROW_HEIGHT = 20f
    private const val ROW_HEIGHT = 30f
    private const val BLOCK_GAP = 12f
    private const val LINE_HEIGHT = 24f
    private const val CELL_INSET = 5f
    private const val CONTENT_TOP = 180f
    private const val CONTENT_BOTTOM = 780f
    private const val VALVE_TABLES_TOP = CONTENT_TOP + 2 * LINE_HEIGHT

    private val VALVE_COLUMNS = floatArrayOf(0.18f, 0.16f, 0.20f, 0.20f, 0.20f, 0.06f)
    private val CHECK_COLUMNS = floatArrayOf(0.36f, 0.20f, 0.32f, 0.12f)

    /** Centres of the four markers: top left, top right, bottom left, bottom right. */
    val markerCentres: List<Point> = run {
        val far = MARKER_INSET + MARKER_SIZE
        listOf(
            MARKER_INSET to MARKER_INSET,
            PAGE_WIDTH - far to MARKER_INSET,
            MARKER_INSET to PAGE_HEIGHT - far,
            PAGE_WIDTH - far to PAGE_HEIGHT - far,
        ).map { (left, top) -> Point((left + MARKER_SIZE / 2).toDouble(), (top + MARKER_SIZE / 2).toDouble()) }
    }

    enum class FieldKind { DIGITS, CHECKBOX }

    /** One field; see the backend's `WorksheetScanLayout.Field`. */
    data class Field(
        val id: String,
        val kind: FieldKind,
        val left: Float,
        val top: Float,
        val integerDigits: Int = 0,
        val decimalDigits: Int = 0,
    ) {
        val width: Float
            get() = if (kind == FieldKind.CHECKBOX) {
                CHECKBOX_SIZE
            } else {
                (integerDigits + decimalDigits) * DIGIT_WIDTH + if (decimalDigits > 0) SEPARATOR_WIDTH else 0f
            }

        val height: Float get() = if (kind == FieldKind.CHECKBOX) CHECKBOX_SIZE else DIGIT_HEIGHT

        /** Left edge of each digit box. */
        fun boxLefts(): List<Float> = (0 until integerDigits + decimalDigits).map { digit ->
            left + digit * DIGIT_WIDTH + if (decimalDigits > 0 && digit >= integerDigits) SEPARATOR_WIDTH else 0f
        }
    }

    /** The fields of every page (index 0 = page 1). */
    fun pages(template: MeasurementTemplate, cylinders: Int = 0, valvesPerCylinder: Int = 0): List<List<Field>> =
        when (template) {
            MeasurementTemplate.VALVE_CLEARANCE -> valvePages(cylinders, valvesPerCylinder)
            MeasurementTemplate.BRAKES -> listOf(
                stack(
                    table(Row("brakes.padFront", 2, 2), Row("brakes.discFront", 2, 2), Row()),
                    table(Row("brakes.padRear", 2, 2), Row("brakes.discRear", 2, 2), Row()),
                    table(Row(), Row(), Row()),
                ),
            )
            MeasurementTemplate.CHAIN -> listOf(
                stack(
                    table(Row("chain.slack", 2, 1), Row("chain.wearLength", 3, 1)),
                    table(Row(), Row()),
                    checkboxLine("chain.cleaned"),
                    checkboxLine("chain.lubricated"),
                ),
            )
            MeasurementTemplate.TIRES -> listOf(stack(tireTable("Front"), tireTable("Rear")))
        }

    /** Pages of a valve clearance sheet, including a trailing page that only holds the fill-in lines. */
    fun pageCount(template: MeasurementTemplate, cylinders: Int = 0, valvesPerCylinder: Int = 0): Int {
        if (template != MeasurementTemplate.VALVE_CLEARANCE) return 1
        val perPage = cylindersPerPage(valvesPerCylinder)
        val tablePages = (cylinders + perPage - 1) / perPage
        val onLast = cylinders - (tablePages - 1) * perPage
        val top = VALVE_TABLES_TOP + onLast * tableHeight(valveSlots(valvesPerCylinder).size)
        return if (top + 7 * LINE_HEIGHT <= 785f) tablePages else tablePages + 1
    }

    private data class Row(val id: String? = null, val integerDigits: Int = 0, val decimalDigits: Int = 0)

    private class Pending(val height: Float, val place: (Float) -> List<Field>)

    private fun table(vararg rows: Row) = Pending(tableHeight(rows.size)) { top ->
        val left = columnLeft(CHECK_COLUMNS, 2) + CELL_INSET
        var rowTop = top + HEADING_HEIGHT + HEADER_ROW_HEIGHT
        buildList {
            for (row in rows) {
                if (row.id != null) add(Field(row.id, FieldKind.DIGITS, left, boxTop(rowTop), row.integerDigits, row.decimalDigits))
                rowTop += ROW_HEIGHT
            }
        }
    }

    private fun tireTable(side: String) = table(
        Row("tires.pressure$side", 1, 2),
        Row("tires.tread$side", 2, 1),
        Row("tires.dot$side", 4, 0),
        Row(),
    )

    private fun checkboxLine(id: String) = Pending(LINE_HEIGHT) { top ->
        listOf(Field(id, FieldKind.CHECKBOX, CONTENT_LEFT, top + (LINE_HEIGHT - CHECKBOX_SIZE) / 2))
    }

    private fun stack(vararg blocks: Pending): List<Field> {
        var top = CONTENT_TOP
        return buildList {
            for (block in blocks) {
                addAll(block.place(top))
                top += block.height
            }
        }
    }

    private fun valvePages(cylinders: Int, valvesPerCylinder: Int): List<List<Field>> {
        require(cylinders in 1..8 && valvesPerCylinder in 2..4) { "Invalid engine layout" }
        val slots = valveSlots(valvesPerCylinder)
        val blockHeight = tableHeight(slots.size)
        val perPage = cylindersPerPage(valvesPerCylinder)
        val pages = (1..cylinders).chunked(perPage).map { onPage ->
            var top = VALVE_TABLES_TOP
            onPage.flatMap { cylinder ->
                cylinderFields(cylinder, top, slots).also { top += blockHeight }
            }
        }
        return pages + List(pageCount(MeasurementTemplate.VALVE_CLEARANCE, cylinders, valvesPerCylinder) - pages.size) { emptyList() }
    }

    private fun cylindersPerPage(valvesPerCylinder: Int): Int =
        maxOf(1, ((CONTENT_BOTTOM - VALVE_TABLES_TOP) / tableHeight(valveSlots(valvesPerCylinder).size)).toInt())

    private fun valveSlots(valvesPerCylinder: Int): List<Pair<ValveKind, Int>> {
        val intakes = if (valvesPerCylinder >= 3) 2 else 1
        val exhausts = if (valvesPerCylinder >= 4) 2 else 1
        return (1..intakes).map { ValveKind.INTAKE to it } + (1..exhausts).map { ValveKind.EXHAUST to it }
    }

    private fun cylinderFields(cylinder: Int, top: Float, slots: List<Pair<ValveKind, Int>>): List<Field> {
        var rowTop = top + HEADING_HEIGHT + HEADER_ROW_HEIGHT
        return slots.flatMap { (kind, index) ->
            val prefix = "valves.$cylinder.${kind.name}.$index."
            val boxTop = boxTop(rowTop)
            listOf(
                Field(prefix + "measured", FieldKind.DIGITS, columnLeft(VALVE_COLUMNS, 2) + CELL_INSET, boxTop, 1, 3),
                Field(prefix + "shimOld", FieldKind.DIGITS, columnLeft(VALVE_COLUMNS, 3) + CELL_INSET, boxTop, 1, 3),
                Field(prefix + "shimNew", FieldKind.DIGITS, columnLeft(VALVE_COLUMNS, 4) + CELL_INSET, boxTop, 1, 3),
            ).also { rowTop += ROW_HEIGHT }
        }
    }

    private fun tableHeight(rows: Int): Float = HEADING_HEIGHT + HEADER_ROW_HEIGHT + rows * ROW_HEIGHT + BLOCK_GAP

    private fun boxTop(rowTop: Float): Float = rowTop + (ROW_HEIGHT - DIGIT_HEIGHT) / 2

    private fun columnLeft(columns: FloatArray, column: Int): Float {
        var left = CONTENT_LEFT
        for (index in 0 until column) left += CONTENT_WIDTH * columns[index]
        return left
    }
}

/**
 * What the QR code of a worksheet page says, e.g. `BMWS;v=1;t=VALVE_CLEARANCE;id=42;c=4;vpc=4;p=1/2`. Only the
 * vehicle id identifies anything.
 */
data class SheetCode(
    val version: Int,
    val template: MeasurementTemplate,
    val vehicleId: Long,
    val cylinders: Int,
    val valvesPerCylinder: Int,
    val page: Int,
    val pages: Int,
) {
    companion object {
        /** The code of a sheet printed during the trial, e.g. `BMWD;v=1;t=TIRES;p=1/1` (twin of `demoQrContent`). */
        fun isDemo(content: String): Boolean = content.trim().substringBefore(';') == "BMWD"

        /** `null` for anything that is not a worksheet code (another QR code, damaged content, unknown template). */
        fun parse(content: String): SheetCode? {
            val parts = content.trim().split(';')
            if (parts.firstOrNull() != "BMWS") return null
            val values = parts.drop(1).mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
            }.toMap()
            val version = values["v"]?.toIntOrNull() ?: return null
            val template = MeasurementTemplate.entries.firstOrNull { it.name == values["t"] } ?: return null
            val vehicleId = values["id"]?.toLongOrNull() ?: return null
            val (page, pages) = values["p"]?.split('/')?.takeIf { it.size == 2 }
                ?.let { (p, n) -> (p.toIntOrNull() ?: return null) to (n.toIntOrNull() ?: return null) } ?: return null
            val cylinders = values["c"]?.toIntOrNull() ?: 0
            val valves = values["vpc"]?.toIntOrNull() ?: 0
            if (template == MeasurementTemplate.VALVE_CLEARANCE && (cylinders !in 1..8 || valves !in 2..4)) return null
            if (page !in 1..pages) return null
            return SheetCode(version, template, vehicleId, cylinders, valves, page, pages)
        }
    }
}

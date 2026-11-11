package de.bikemaintainer.app.domain.scan

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Exports labelled digits from real photos for training the digit model (`tools/digit-model/train.py`), not part of
 * the normal test run. `BM_SCAN_SAMPLES=<list>` names one photo per line as `<jpg>|<expected json>|<group>`, the JSON
 * in the format of the sample photos (`qr`, `values`). Every filled-in digit box is read exactly as [SheetReader] does
 * and written as one line `<group> <label> <784 values>` (the 28×28 model input) to `BM_SCAN_OUT`. Boxes that come
 * out empty are counted, not written.
 */
class ScanSampleExportTest {
    @Test
    fun export() {
        val list = System.getenv("BM_SCAN_SAMPLES")
        assumeTrue("set BM_SCAN_SAMPLES to run", !list.isNullOrBlank())
        val out = File(System.getenv("BM_SCAN_OUT") ?: "build/scan-samples.txt")
        val lines = StringBuilder()
        var missed = 0
        for (entry in File(list!!).readLines().filter { it.isNotBlank() }) {
            val (photoPath, jsonPath, group) = entry.split("|")
            val expected = Json.parseToJsonElement(File(jsonPath).readText()).jsonObject
            val code = SheetCode.parse(expected.getValue("qr").jsonPrimitive.content) ?: error("bad QR in $jsonPath")
            val values = expected.getValue("values").jsonObject
            val photo = loadGray(File(photoPath))
            val markers = MarkerFinder.find(photo) ?: error("no markers in $photoPath")
            val page = SheetReader.uprightPage(photo, markers) ?: error("no homography for $photoPath")
            val fields = ScanLayout.pages(code.template, code.cylinders, code.valvesPerCylinder)[code.page - 1]
            for (field in fields.filter { it.kind == ScanLayout.FieldKind.DIGITS }) {
                val value = values[field.id]?.jsonPrimitive?.content ?: continue
                val labels = boxLabels(value, field.integerDigits, field.decimalDigits)
                for ((index, input) in SheetReader.digitInputs(photo, page, field).withIndex()) {
                    val label = labels[index] ?: continue
                    if (input == null) {
                        missed++
                        continue
                    }
                    lines.append(group).append(' ').append(label)
                    for (v in input) lines.append(' ').append("%.3f".format(java.util.Locale.ROOT, v))
                    lines.append('\n')
                }
            }
        }
        out.writeText(lines.toString())
        println("written to $out, $missed filled boxes read as empty")
    }

    /** Digit per box: integer part right-aligned, decimals left-aligned, `null` for a box left empty. */
    private fun boxLabels(value: String, integerDigits: Int, decimalDigits: Int): List<Int?> {
        val (integer, decimals) = value.split(".").let { it[0] to it.getOrElse(1) { "" } }
        val intPart = integer.padStart(integerDigits, ' ')
        val decPart = decimals.padEnd(decimalDigits, ' ')
        return (intPart + decPart).map { if (it == ' ') null else it.digitToInt() }
    }
}

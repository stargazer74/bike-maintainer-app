package de.bikemaintainer.app.domain.scan

import de.bikemaintainer.app.domain.MeasurementTemplate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The app reads exactly the positions the backend prints: both are checked against the same contract file. */
class ScanLayoutTest {
    private val contract = Json.parseToJsonElement(testResource("worksheet-scan-layout-v1.json").readText()).jsonObject

    @Test
    fun constantsMatchTheContract() {
        assertEquals(ScanLayout.VERSION, contract["version"]!!.jsonPrimitive.int)
        assertEquals(ScanLayout.QR_LEFT, contract["qr"]!!.jsonObject["left"]!!.jsonPrimitive.float, 0.01f)
        assertEquals(ScanLayout.QR_TOP, contract["qr"]!!.jsonObject["top"]!!.jsonPrimitive.float, 0.01f)
        assertEquals(ScanLayout.QR_SIZE, contract["qr"]!!.jsonObject["size"]!!.jsonPrimitive.float, 0.01f)
        assertEquals(ScanLayout.DIGIT_WIDTH, contract["digit"]!!.jsonObject["width"]!!.jsonPrimitive.float, 0.01f)
        assertEquals(ScanLayout.DIGIT_HEIGHT, contract["digit"]!!.jsonObject["height"]!!.jsonPrimitive.float, 0.01f)
        assertEquals(ScanLayout.SEPARATOR_WIDTH, contract["digit"]!!.jsonObject["separator"]!!.jsonPrimitive.float, 0.01f)
        assertEquals(ScanLayout.CHECKBOX_SIZE, contract["checkboxSize"]!!.jsonPrimitive.float, 0.01f)
        val markers = contract["markers"]!!.jsonArray
        val size = contract["markerSize"]!!.jsonPrimitive.float
        ScanLayout.markerCentres.forEachIndexed { i, centre ->
            val (left, top) = markers[i].jsonArray.map { it.jsonPrimitive.float }
            assertEquals(left + size / 2, centre.x.toFloat(), 0.01f)
            assertEquals(top + size / 2, centre.y.toFloat(), 0.01f)
        }
    }

    @Test
    fun everySheetHasTheFieldsOfTheContract() {
        val sheets = contract["sheets"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3 + 8 * 3, sheets.size)
        for (sheet in sheets) {
            val template = MeasurementTemplate.valueOf(sheet["template"]!!.jsonPrimitive.content)
            val cylinders = sheet["cylinders"]?.jsonPrimitive?.intOrNull ?: 0
            val valves = sheet["valvesPerCylinder"]?.jsonPrimitive?.intOrNull ?: 0
            val pages = ScanLayout.pages(template, cylinders, valves)
            val expectedPages = sheet["pages"]!!.jsonArray
            val what = "$template $cylinders×$valves"
            assertEquals(what, expectedPages.size, pages.size)
            assertEquals(what, expectedPages.size, ScanLayout.pageCount(template, cylinders, valves))
            expectedPages.forEachIndexed { index, page ->
                val expected = page.jsonObject["fields"]!!.jsonArray.map { field(it.jsonObject) }
                val actual = pages[index].map { it.copy(left = round(it.left), top = round(it.top)) }
                assertEquals("$what page ${index + 1}", expected, actual)
            }
        }
    }

    @Test
    fun boxLefts_skipTheSeparatorAfterTheIntegerDigits() {
        val field = ScanLayout.Field("x", ScanLayout.FieldKind.DIGITS, 100f, 0f, 2, 2)
        assertEquals(listOf(100f, 118f, 144f, 162f), field.boxLefts())
        assertEquals(80f, field.width, 0f)
        assertEquals(listOf(10f, 28f), ScanLayout.Field("dot", ScanLayout.FieldKind.DIGITS, 10f, 0f, 2, 0).boxLefts())
        assertEquals(ScanLayout.CHECKBOX_SIZE, ScanLayout.Field("c", ScanLayout.FieldKind.CHECKBOX, 0f, 0f).height, 0f)
    }

    private fun field(json: JsonObject) = ScanLayout.Field(
        id = json["id"]!!.jsonPrimitive.content,
        kind = ScanLayout.FieldKind.valueOf(json["kind"]!!.jsonPrimitive.content),
        left = json["left"]!!.jsonPrimitive.float,
        top = json["top"]!!.jsonPrimitive.float,
        integerDigits = json["integerDigits"]!!.jsonPrimitive.int,
        decimalDigits = json["decimalDigits"]!!.jsonPrimitive.int,
    )

    /** Like the contract: rounded to 1/100 point. */
    private fun round(value: Float) = String.format(java.util.Locale.ROOT, "%.2f", value).toFloat()
}

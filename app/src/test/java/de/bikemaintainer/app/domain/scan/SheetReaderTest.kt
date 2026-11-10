package de.bikemaintainer.app.domain.scan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Reads the sample photos: real worksheet pages from the backend, filled with handwritten digits of the MNIST test set
 * (not used for training), photographed straight, at an angle and in poor light (see docs/scan-samples.md).
 */
class SheetReaderTest {
    private val classifier = classifier()

    @Test
    fun valvesStraight() = check("valves-straight")

    @Test
    fun tiresOblique() = check("tires-oblique")

    @Test
    fun chainDim() = check("chain-dim")

    @Test
    fun brakesObliqueDim() = check("brakes-oblique-dim")

    @Test
    fun noSheetInThePicture() {
        val blank = GrayImage(800, 600).apply { pixels.fill(200.toByte()) }
        assertEquals(ScanOutcome.NoSheet, SheetReader(classifier) { "BMWS" }.read(blank))
    }

    @Test
    fun unreadableOrForeignQrCode() {
        val photo = loadGray(testResource("scan/tires-oblique.jpg"))
        assertEquals(ScanOutcome.NoCode, SheetReader(classifier) { null }.read(photo))
        assertEquals(ScanOutcome.NoCode, SheetReader(classifier) { "https://example.org" }.read(photo))
        assertEquals(
            ScanOutcome.UnsupportedVersion(2),
            SheetReader(classifier) { "BMWS;v=2;t=TIRES;id=42;p=1/1" }.read(photo),
        )
    }

    private fun check(name: String) {
        val expected = Json.parseToJsonElement(testResource("scan/$name.json").readText()).jsonObject
        val qr = expected["qr"]!!.jsonPrimitive.content
        val values = expected["values"]!!.jsonObject
        val reader = SheetReader(classifier, FinderPatternCheck(qr))
        val outcome = reader.read(loadGray(testResource("scan/$name.jpg")))
        if (outcome !is ScanOutcome.Read) fail("$name: $outcome")
        val sheet = (outcome as ScanOutcome.Read).sheet
        assertEquals(SheetCode.parse(qr), sheet.code)
        assertEquals(values.keys, sheet.readings.map { it.field.id }.toSet())
        var wrong = 0
        val report = StringBuilder()
        for (reading in sheet.readings) {
            val want = values[reading.field.id]!!.jsonPrimitive
            val ok = if (want.booleanOrNull != null) reading.checked == want.boolean else reading.value == want.content
            report.append("${reading.field.id}: want ${want.content}, got ${reading.value}${reading.checked}${if (reading.uncertain) " (uncertain)" else ""}\n")
            if (!ok) {
                wrong++
                // A wrong value must never look certain: the user checks highlighted fields before taking them over.
                assertTrue("$name ${reading.field.id} wrong but not flagged:\n$report", reading.uncertain)
            }
            assertTrue(reading.crop.width > 0)
        }
        // Most fields are read right; the rest is flagged (above).
        assertTrue("$name: $wrong of ${sheet.readings.size} wrong\n$report", wrong * 5 <= sheet.readings.size)
        println("$name: $wrong of ${sheet.readings.size} wrong\n$report")
    }

    /**
     * Stands in for zxing-cpp (native, not available on the JVM): returns [content] only if the straightened QR area
     * really shows the three finder patterns where they belong – i.e. the perspective was found correctly.
     */
    private class FinderPatternCheck(private val content: String) : QrReader {
        override fun read(image: GrayImage): String? {
            val scale = image.width / (ScanLayout.QR_SIZE + 20f)
            fun dark(xPt: Float, yPt: Float) = image[(xPt * scale).toInt(), (yPt * scale).toInt()] < 110
            val qr0 = 10f
            val qr1 = 10f + ScanLayout.QR_SIZE
            val corners = listOf(qr0 + 1f to qr0 + 1f, qr1 - 1f to qr0 + 1f, qr0 + 1f to qr1 - 1f)
            val quiet = listOf(qr0 - 5f to qr0 - 5f, qr1 + 5f to qr0 + 30f, qr0 + 30f to qr1 + 5f)
            return content.takeIf { corners.all { (x, y) -> dark(x, y) } && quiet.none { (x, y) -> dark(x, y) } }
        }
    }
}

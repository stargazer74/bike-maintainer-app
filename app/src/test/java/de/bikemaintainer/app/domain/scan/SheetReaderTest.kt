package de.bikemaintainer.app.domain.scan

import de.bikemaintainer.app.domain.MeasurementTemplate
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

    /** Page 2 of 3: cylinders 4–6, read from the positions of page 2. */
    @Test
    fun valvesPage2Oblique() = check("valves-page2-oblique")

    @Test
    fun chainDim() = check("chain-dim")

    @Test
    fun brakesObliqueDim() = check("brakes-oblique-dim")

    /**
     * Real photos (phone camera, handwriting with a 9 bent to the left and a 1 with a long flag), metadata stripped.
     * A dark knot in the table next to the page bottom right once made a bigger "frame" than the real marker.
     */
    @Test
    fun realPhotoWithKnotInTheTable() = check("real-valves-table-knot")

    /** The marker of another sheet in the picture once replaced the page's own top left marker. */
    @Test
    fun realPhotoWithAnotherSheetInThePicture() = check("real-valves-other-sheet")

    /** Pale, slightly blurred strokes once vanished, whole fields came out empty. */
    @Test
    fun realPhotoWithPaleWriting() = check("real-valves-pale")

    /**
     * A phone lens bends straight lines (barrel distortion) and paper is rarely flat; the four corner markers cannot
     * correct that, so fields sit a few points off their computed position. Box borders must still not be read as
     * digits (a stray border line looked like a confident "1"), and empty boxes must stay empty.
     */
    @Test
    fun valvesStraightLensDistortion() = check("valves-straight") { barrel(it, 0.02) }

    @Test
    fun tiresObliqueLensDistortion() = check("tires-oblique") { barrel(it, -0.04) }

    /**
     * Phone held sideways or the sheet upside down: the page lies turned in the photo. The QR code tells which corner
     * is which; its finder patterns, now next to another corner, must not be taken for a corner marker.
     */
    @Test
    fun sheetTurnedClockwise() = check("valves-straight") { it.rotated(90) }

    @Test
    fun sheetUpsideDown() = check("tires-oblique") { it.rotated(180) }

    @Test
    fun sheetTurnedCounterClockwise() = check("chain-dim") { it.rotated(270) }

    /** The page as a whole is 2 pt right and 1.5 pt down of where the markers put it: every field finds its frame. */
    @Test
    fun fieldsAreAlignedToTheirPrintedFrame() {
        val photo = loadGray(testResource("scan/valves-straight.jpg"))
        val markers = MarkerFinder.find(photo)!!
        val shifted = Homography.fromPoints(ScanLayout.markerCentres.map { Point(it.x + 2.0, it.y + 1.5) }, markers)!!
        val fields = ScanLayout.pages(MeasurementTemplate.VALVE_CLEARANCE, 8, 3)[0]
        for (field in fields) {
            val (dx, dy) = SheetReader.frameOffset(photo, shifted, field)
            assertEquals("${field.id} dx", 2f, dx, 0.5f)
            assertEquals("${field.id} dy", 1.5f, dy, 0.5f)
        }
    }

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

    /** A sheet of the trial (backend#138): its demo code has no vehicle, so nothing is read. */
    @Test
    fun demoSheetOfTheTrial() {
        val photo = loadGray(testResource("scan/tires-oblique.jpg"))
        assertEquals(ScanOutcome.DemoSheet, SheetReader(classifier) { "BMWD;v=1;t=TIRES;p=1/1" }.read(photo))
        assertEquals(true, SheetCode.isDemo(" BMWD;v=1;t=VALVE_CLEARANCE;c=4;vpc=4;p=2/2"))
        assertEquals(false, SheetCode.isDemo("BMWS;v=1;t=TIRES;id=42;p=1/1"))
        assertEquals(null, SheetCode.parse("BMWD;v=1;t=TIRES;p=1/1"))
    }

    private fun check(name: String, distort: (GrayImage) -> GrayImage = { it }) {
        val expected = Json.parseToJsonElement(testResource("scan/$name.json").readText()).jsonObject
        val qr = expected["qr"]!!.jsonPrimitive.content
        val values = expected["values"]!!.jsonObject
        val reader = SheetReader(classifier, FinderPatternCheck(qr))
        val outcome = reader.read(distort(loadGray(testResource("scan/$name.jpg"))))
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
            // Nothing is invented: an empty field stays empty.
            if (want.booleanOrNull == null && want.content.isEmpty()) {
                assertEquals("$name ${reading.field.id} is empty on the sheet:\n$report", "", reading.value)
            }
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

    /** Radial lens distortion around the image centre; [k] > 0 barrel, < 0 pincushion (relative to the half diagonal). */
    private fun barrel(image: GrayImage, k: Double): GrayImage {
        val cx = image.width / 2.0
        val cy = image.height / 2.0
        val r2max = cx * cx + cy * cy
        val out = GrayImage(image.width, image.height)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val dx = x - cx
            val dy = y - cy
            val f = 1 + k * (dx * dx + dy * dy) / r2max
            out[x, y] = image.sample(cx + dx * f, cy + dy * f).toInt()
        }
        return out
    }

    /**
     * Stands in for zxing-cpp (native, not available on the JVM): returns [content] only if the straightened QR area
     * really shows the three finder patterns where they belong – i.e. the perspective was found correctly.
     */
    private class FinderPatternCheck(private val content: String) : QrReader {
        override fun read(image: GrayImage): String? {
            val scale = image.width / (ScanLayout.QR_SIZE + 20f)
            // Relative to the paper: on real photos the printed modules are grey, not black.
            val limit = SheetReader.paperLevel(image) * 0.6
            fun dark(xPt: Float, yPt: Float) = image[(xPt * scale).toInt(), (yPt * scale).toInt()] < limit
            val qr0 = 10f
            val qr1 = 10f + ScanLayout.QR_SIZE
            // Centres of the three finder patterns (their solid 3×3 core), so a few points of lens distortion still pass.
            val inset = 8f
            val corners = listOf(qr0 + inset to qr0 + inset, qr1 - inset to qr0 + inset, qr0 + inset to qr1 - inset)
            val quiet = listOf(qr0 - 5f to qr0 - 5f, qr1 + 5f to qr0 + 30f, qr0 + 30f to qr1 + 5f)
            // Paper that is not flat moves the patterns by a few points, as it moves the fields; zxing finds them anyway.
            fun darkNear(xPt: Float, yPt: Float) = (-3..3).any { dx -> (-3..3).any { dy -> dark(xPt + dx, yPt + dy) } }
            return content.takeIf { corners.all { (x, y) -> darkNear(x, y) } && quiet.none { (x, y) -> dark(x, y) } }
        }
    }
}

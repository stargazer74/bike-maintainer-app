package de.bikemaintainer.app.domain.odometer

import de.bikemaintainer.app.domain.scan.GrayImage
import de.bikemaintainer.app.domain.scan.loadGray
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OdoReaderTest {
    private val reader = File("src/main/assets/odo-reader.bin").inputStream().use(OdoReader::load)

    /** The Kotlin network computes what the trained PyTorch model computes (`train_reader.py export`). */
    @Test
    fun matchesTheReferenceOutputsOfTheTrainedModel() {
        val input = FloatArray(OdoReader.H * OdoReader.MAX_W) { (sin(it * 0.37) * 2.0).toFloat() }
        val (logits, roll) = reader.forward(input)
        val values = File("src/test/resources/odometer/odo-reader-reference.txt").readLines()
            .associate { line -> line.split(" ").let { it[0] to it[1].toDouble() } }
        assertEquals(values.getValue("sum"), logits.sumOf { it.toDouble() }, 0.05)
        assertEquals(values.getValue("abs"), logits.sumOf { abs(it.toDouble()) }, 0.05)
        assertEquals(values.getValue("roll"), roll.sumOf { it.toDouble() }, 0.01)
        values.filterKeys { key -> key.all(Char::isDigit) }
            .forEach { (i, v) -> assertEquals("logit $i", v, logits[i.toInt()].toDouble(), 1e-3) }
    }

    /** Synthetic test photos: the crop is the one Python trained on, and the value comes out right and sure. */
    @Test
    fun readsTheSamplePhotosLikeTheTrainingCode() {
        val lines = File("src/test/resources/odometer/odo-reader-samples.txt").readLines().filterNot { it.startsWith("#") }
        assertEquals(3, lines.size)
        for (line in lines) {
            val (name, quad, last, value) = line.split(" | ")
            val (width, sum) = line.split(" | ").drop(4)
            val photo = loadGray(File("src/test/resources/odometer/$name"))
            val n = quad.split(" ").map { it.toDouble() }
            val corners = List(4) { Point(n[2 * it], n[2 * it + 1]) }
            val crop = OdoReader.crop(photo, corners)!!
            assertEquals(name, width.toInt(), crop.width)
            var total = 0L
            for (y in 0 until OdoReader.H) for (x in 0 until crop.width) total += crop.pixels[y * OdoReader.MAX_W + x].toLong()
            // Rounding of a few pixels may differ by one between numpy and the JVM.
            assertEquals(name, sum.toDouble(), total.toDouble(), sum.toDouble() * 0.002)
            assertEquals(name, OdoReading(value.toInt(), false), reader.read(photo, corners, last.toInt()))
        }
    }

    @Test
    fun aDegenerateFrameReadsNothing() {
        val p = Point(10.0, 10.0)
        assertNull(OdoReader.crop(GrayImage(100, 100), listOf(p, p, p, p)))
        assertEquals(OdoReading(null, true), reader.read(GrayImage(100, 100), listOf(p, p, p, p), 1000))
    }

    @Test
    fun rejectsFilesThatAreNoModel() {
        assertThrows(IOException::class.java) { OdoReader.load(ByteArrayInputStream("NOPE".toByteArray())) }
        val detector = File("src/main/assets/odo-detector.bin").readBytes()
        assertThrows(IOException::class.java) { OdoReader.load(ByteArrayInputStream(detector)) }
    }

    // ------------------------------------------------------------------ decoding (per column: blank, 0–9, tenths 0–9)

    /** Columns that show [digits] (an integer digit, or 10 + d for a tenths digit), each over two columns. */
    private fun columns(vararg digits: Int, p: Double = 0.995): Array<DoubleArray> = Array(OdoReader.FRAMES) { f ->
        val k = f / 3
        val c = if (k < digits.size && f % 3 != 2) 1 + digits[k] else 0
        DoubleArray(OdoReader.CLASSES) { if (it == c) p else (1 - p) / (OdoReader.CLASSES - 1) }
    }

    private val still = DoubleArray(OdoReader.FRAMES)

    @Test
    fun aClearTenthsRollGivesBothReadingsWithTheTenthsOneFavoured() {
        val c = OdoReader.candidates(columns(1, 2, 3, 4, 10 + 5), still)
        assertEquals(2, c.size)
        // As an integer digit the 5 is not readable (the model sees only a tenths digit there): unsure.
        assertTrue(c[0].unsure)
        assertEquals(1234, c[1].value)
        assertTrue(c[1].weight > 0.9)
        assertFalse(c[1].unsure)
    }

    @Test
    fun theLastMileageTellsTheTenthsDigitApart() {
        val c = OdoReader.candidates(columns(1, 2, 3, 4, 5), still)
        // 12345 or 1234.5: after 11,800 only 12345 fits, after 1,100 (at most 5,000 more) only 1234.
        assertEquals(OdoReading(12345, false), OdoPlausibility.choose(c, 11_800))
        assertEquals(OdoReading(1234, false), OdoPlausibility.choose(c, 1_100, maxJump = 5_000))
    }

    @Test
    fun withoutALastMileageOrWhenBothFitTheValueIsUnsure() {
        val c = OdoReader.candidates(columns(1, 2, 3, 4, 10 + 5), still)
        assertEquals(OdoReading(1234, true), OdoPlausibility.choose(c, null))
        assertEquals(OdoReading(1234, true), OdoPlausibility.choose(c, 1_000)) // 1234 and 12345 both fit
    }

    @Test
    fun aValueBelowTheLastMileageOrFarAboveIsUnsure() {
        val c = listOf(OdoCandidate(5000, false, 1.0))
        assertEquals(OdoReading(5000, true), OdoPlausibility.choose(c, 6000))
        assertEquals(OdoReading(5000, true), OdoPlausibility.choose(c, 100, maxJump = 4000))
        assertEquals(OdoReading(5000, false), OdoPlausibility.choose(c, 4000))
        assertTrue(OdoPlausibility.plausible(Int.MAX_VALUE, Int.MAX_VALUE - 1)) // no overflow
    }

    @Test
    fun aTurningRollMakesTheReadingUnsureAndTheLowerDigitCounts() {
        // The units roll between 4 and 5: the model reads the lower digit, the turning flag is up under it.
        val roll = DoubleArray(OdoReader.FRAMES) { f -> if (f / 3 == 4) 0.8 else 0.0 }
        val c = OdoReader.candidates(columns(1, 2, 3, 4, 4), roll)
        assertEquals(OdoReading(12344, true), OdoPlausibility.choose(c, 12_000))
    }

    @Test
    fun aTenthsRollAtNineOrZeroMakesTheNextDigitUnsure() {
        for (tenths in listOf(9, 0)) {
            val c = OdoReader.candidates(columns(1, 2, 3, 4, 10 + tenths), still)
            assertEquals(OdoReading(1234, true), OdoPlausibility.choose(c, 1_100, maxJump = 5_000))
        }
        val c = OdoReader.candidates(columns(1, 2, 3, 4, 10 + 5), still)
        assertEquals(OdoReading(1234, false), OdoPlausibility.choose(c, 1_100, maxJump = 5_000))
    }

    @Test
    fun aDigitTheModelIsNotSureOfMakesTheReadingUnsure() {
        val c = OdoReader.candidates(columns(1, 2, 3, 4, 10 + 5, p = 0.9), still)
        assertEquals(OdoReading(1234, true), OdoPlausibility.choose(c, 1_100, maxJump = 5_000))
    }

    @Test
    fun aDigitHalfHiddenInABlankColumnMakesTheReadingUnsure() {
        val p = columns(1, 2, 3, 4, 10 + 5)
        // Between 2 and 3 a blank column also shows a 7 that no neighbour reads.
        p[5] = DoubleArray(OdoReader.CLASSES) { if (it == 0) 0.55 else if (it == 8) 0.4 else 0.05 / 19 }
        val c = OdoReader.candidates(p, still)
        assertEquals(OdoReading(1234, true), OdoPlausibility.choose(c, 1_100, maxJump = 5_000))
    }

    @Test
    fun nothingReadMeansNoValue() {
        assertEquals(OdoReading(null, true), OdoPlausibility.choose(OdoReader.candidates(columns(), still), 1000))
    }

    @Test
    fun runsOnAPhotoInReasonableTime() {
        val photo = GrayImage(1280, 960)
        val corners = listOf(Point(300.0, 400.0), Point(900.0, 400.0), Point(900.0, 520.0), Point(300.0, 520.0))
        reader.read(photo, corners, 1000)
        val start = System.nanoTime()
        repeat(5) { reader.read(photo, corners, 1000) }
        val millis = (System.nanoTime() - start) / 5_000_000
        println("OdoReader: $millis ms per line on this JVM")
        assertTrue("took $millis ms", millis < 1_000)
    }
}

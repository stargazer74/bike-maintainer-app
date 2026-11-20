package de.bikemaintainer.app.domain.odometer

import de.bikemaintainer.app.domain.scan.GrayImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OdoDetectorTest {
    private val detector = File("src/main/assets/odo-detector.bin").inputStream().use(OdoDetector::load)

    /** The Kotlin network computes what the trained PyTorch model computes (`train_detector.py export`). */
    @Test
    fun matchesTheReferenceOutputsOfTheTrainedModel() {
        val input = FloatArray(OdoDetector.IN_W * OdoDetector.IN_H) { (sin(it * 0.37) * 2.0).toFloat() }
        val out = detector.logits(input)
        val values = File("src/test/resources/odometer/odo-reference.txt").readLines()
            .associate { line -> line.split(" ").let { it[0] to it[1].toDouble() } }
        assertEquals(values.getValue("sum"), out.sumOf { it.toDouble() }, 0.5)
        assertEquals(values.getValue("abs"), out.sumOf { abs(it.toDouble()) }, 0.5)
        values.filterKeys { key -> key.all(Char::isDigit) }
            .forEach { (i, v) -> assertEquals("logit $i", v, out[i.toInt()].toDouble(), 1e-3) }
    }

    @Test
    fun aMarkedBandBecomesARectangleInPhotoPixels() {
        // Cells 20..59 × 30..35 of a 96×72 mask over a 960×720 photo → x 200..600, y 300..360.
        val prob = FloatArray(96 * 72) { p -> if (p % 96 in 20 until 60 && p / 96 in 30 until 36) 0.9f else 0.05f }
        val line = OdoDetector.lineOf(prob, 96, 72, 960.0, 720.0)!!
        val (tl, tr, br, bl) = line.corners
        assertEquals(200.0, tl.x, 6.0)
        assertEquals(300.0, tl.y, 6.0)
        assertEquals(600.0, tr.x, 6.0)
        assertEquals(360.0, br.y, 6.0)
        assertEquals(tl.x, bl.x, 1e-6)
        assertEquals(0.9, line.confidence, 1e-6)
        assertFalse(line.unsure)
    }

    @Test
    fun theStrongestOfSeveralRegionsWins() {
        val prob = FloatArray(96 * 72)
        for (x in 10 until 20) prob[10 * 96 + x] = 0.6f // small and weak, e.g. the trip meter
        for (x in 40 until 80) for (y in 50 until 54) prob[y * 96 + x] = 0.95f
        val line = OdoDetector.lineOf(prob, 96, 72, 96.0, 72.0)!!
        assertTrue(line.corners.all { it.y > 45 })
    }

    @Test
    fun aWeakRegionIsUnsure() {
        val prob = FloatArray(96 * 72) { p -> if (p % 96 in 20 until 60 && p / 96 in 30 until 36) 0.6f else 0f }
        assertTrue(OdoDetector.lineOf(prob, 96, 72, 96.0, 72.0)!!.unsure)
    }

    @Test
    fun nothingMarkedMeansNoLine() {
        assertNull(OdoDetector.lineOf(FloatArray(96 * 72) { 0.2f }, 96, 72, 96.0, 72.0))
        assertNull(detector.detect(GrayImage(320, 240)))
    }

    @Test
    fun scalingAveragesTheAreaEachPixelCovers() {
        val photo = GrayImage(384, 288) // twice the model input
        for (y in 0 until 288) for (x in 0 until 384) photo[x, y] = if (x % 2 == 0) 0 else 200
        assertTrue(OdoDetector.scaled(photo).all { abs(it - 100f) < 1e-3 })
        val odd = GrayImage(500, 333, ByteArray(500 * 333) { 77 })
        assertTrue(OdoDetector.scaled(odd).all { abs(it - 77f) < 1e-3 })
    }

    @Test
    fun normalisingGivesMeanZeroAndUnitSpread() {
        val n = OdoDetector.normalised(FloatArray(1000) { (it % 256).toFloat() })
        assertEquals(0.0, n.average(), 1e-4)
        assertEquals(1.0, kotlin.math.sqrt(n.sumOf { it.toDouble() * it } / n.size), 1e-2)
    }

    @Test
    fun runsOnAPhotoInReasonableTime() {
        val photo = GrayImage(1280, 960)
        detector.detect(photo)
        val start = System.nanoTime()
        repeat(3) { detector.detect(photo) }
        val millis = (System.nanoTime() - start) / 3_000_000
        println("OdoDetector: $millis ms per photo on this JVM")
        assertTrue("took $millis ms", millis < 2_000)
    }

    @Test
    fun rejectsFilesThatAreNoModel() {
        assertThrows(IOException::class.java) { OdoDetector.load(ByteArrayInputStream("NOPE".toByteArray())) }
        val wrongShape = ByteBuffer.allocate(8 + 24 * 12).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("BMO1".toByteArray())
            putInt(24)
            repeat(24) { putInt(1); putInt(1); putFloat(0f) }
        }.array()
        assertThrows(IOException::class.java) { OdoDetector.load(ByteArrayInputStream(wrongShape)) }
        val badCount = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put("BMO1".toByteArray()).putInt(999).array()
        assertThrows(IOException::class.java) { OdoDetector.load(ByteArrayInputStream(badCount)) }
        val badRank = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put("BMO1".toByteArray()).putInt(1).putInt(9).array()
        assertThrows(IOException::class.java) { OdoDetector.load(ByteArrayInputStream(badRank)) }
        val badDim = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put("BMO1".toByteArray()).putInt(1).putInt(1).putInt(0).array()
        assertThrows(IOException::class.java) { OdoDetector.load(ByteArrayInputStream(badDim)) }
        val huge = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).put("BMO1".toByteArray()).putInt(1).putInt(2).putInt(5000).putInt(5000).array()
        assertThrows(IOException::class.java) { OdoDetector.load(ByteArrayInputStream(huge)) }
    }
}

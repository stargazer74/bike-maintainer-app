package de.bikemaintainer.app.domain.scan

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitClassifierTest {
    private val classifier = classifier()

    /** The Kotlin network computes what the trained PyTorch model computes. */
    @Test
    fun matchesTheReferenceOutputsOfTheTrainedModel() {
        val lines = testResource("scan/digit-reference.txt").readLines().filterNot { it.startsWith("#") }
        assertEquals(10, lines.size)
        for (line in lines) {
            val (input, output) = line.split(" | ")
            val numbers = input.split(" ").map { it.toInt() }
            val pixels = FloatArray(784) { numbers[it + 1] / 255f }
            val expected = output.split(" ").map { it.toFloat() }
            val logits = classifier.logits(pixels)
            expected.forEachIndexed { i, v -> assertEquals(v, logits[i], 1e-3f) }
            val guess = classifier.classify(pixels)
            assertEquals(numbers[0], guess.digit)
            assertTrue(guess.confidence > 0.5)
        }
    }

    @Test
    fun rejectsFilesThatAreNoModel() {
        assertThrows(IOException::class.java) { DigitClassifier.load(ByteArrayInputStream("NOPE".toByteArray())) }
        val wrongShape = java.nio.ByteBuffer.allocate(4 + 8 * 12).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("BMD1".toByteArray())
            repeat(8) { putInt(1); putInt(1); putFloat(0f) }
        }.array()
        assertThrows(IOException::class.java) { DigitClassifier.load(ByteArrayInputStream(wrongShape)) }
        val badRank = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).put("BMD1".toByteArray()).putInt(9).array()
        assertThrows(IOException::class.java) { DigitClassifier.load(ByteArrayInputStream(badRank)) }
    }

    private fun header(vararg dimensions: Int) =
        java.nio.ByteBuffer.allocate(8 + 4 * dimensions.size).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("BMD1".toByteArray())
            putInt(dimensions.size)
            dimensions.forEach { putInt(it) }
        }.array()

    @Test
    fun rejectsBrokenTensorSizes() {
        // Zero or negative dimensions, too large in total, and a product that would overflow an Int.
        listOf(intArrayOf(0), intArrayOf(4, -2), intArrayOf(1_000_001), intArrayOf(65_536, 65_536)).forEach { dims ->
            assertThrows(IOException::class.java) { DigitClassifier.load(ByteArrayInputStream(header(*dims))) }
        }
    }
}

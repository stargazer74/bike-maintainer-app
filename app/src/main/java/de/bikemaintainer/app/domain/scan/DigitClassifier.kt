package de.bikemaintainer.app.domain.scan

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/** A recognised digit and how sure the model is (softmax probability, 0..1). */
data class DigitGuess(val digit: Int, val confidence: Double)

/**
 * Recognises a handwritten digit with a small convolutional network that runs entirely on the device – no library,
 * no network. Architecture: conv 3×3 (16) → ReLU → max pool → conv 3×3 (32) → ReLU → max pool → dense 64 → ReLU →
 * dense 10. Weights from `assets/digits.bin` (trained on MNIST with augmentation, see `docs/digit-model.md`).
 *
 * Input: 28×28 values 0..1, ink = 1, centred like MNIST.
 */
class DigitClassifier(private val layers: List<FloatArray>) {
    init {
        require(layers.size == 8) { "Expected 8 weight tensors" }
        require(layers[0].size == 16 * 9 && layers[2].size == 32 * 16 * 9 && layers[4].size == 64 * 32 * 7 * 7 && layers[6].size == 10 * 64) {
            "Unexpected weight shapes"
        }
    }

    fun classify(input: FloatArray): DigitGuess = softmaxBest(logits(input))

    internal fun logits(input: FloatArray): FloatArray {
        require(input.size == 28 * 28)
        val c1 = pool(relu(conv(input, 1, 28, layers[0], layers[1], 16)), 16, 28)
        val c2 = pool(relu(conv(c1, 16, 14, layers[2], layers[3], 32)), 32, 14)
        return dense(relu(dense(c2, layers[4], layers[5], 64)), layers[6], layers[7], 10)
    }

    private fun conv(input: FloatArray, inC: Int, size: Int, w: FloatArray, b: FloatArray, outC: Int): FloatArray {
        val out = FloatArray(outC * size * size)
        for (o in 0 until outC) {
            val bias = b[o]
            for (y in 0 until size) for (x in 0 until size) {
                var sum = bias
                for (i in 0 until inC) {
                    val inBase = i * size * size
                    val wBase = (o * inC + i) * 9
                    for (ky in 0 until 3) {
                        val iy = y + ky - 1
                        if (iy < 0 || iy >= size) continue
                        for (kx in 0 until 3) {
                            val ix = x + kx - 1
                            if (ix < 0 || ix >= size) continue
                            sum += input[inBase + iy * size + ix] * w[wBase + ky * 3 + kx]
                        }
                    }
                }
                out[o * size * size + y * size + x] = sum
            }
        }
        return out
    }

    private fun relu(a: FloatArray): FloatArray {
        for (i in a.indices) if (a[i] < 0f) a[i] = 0f
        return a
    }

    private fun pool(input: FloatArray, channels: Int, size: Int): FloatArray {
        val half = size / 2
        val out = FloatArray(channels * half * half)
        for (c in 0 until channels) for (y in 0 until half) for (x in 0 until half) {
            val base = c * size * size
            val a = input[base + 2 * y * size + 2 * x]
            val b = input[base + 2 * y * size + 2 * x + 1]
            val d = input[base + (2 * y + 1) * size + 2 * x]
            val e = input[base + (2 * y + 1) * size + 2 * x + 1]
            out[c * half * half + y * half + x] = maxOf(maxOf(a, b), maxOf(d, e))
        }
        return out
    }

    private fun dense(input: FloatArray, w: FloatArray, b: FloatArray, out: Int): FloatArray {
        val n = input.size
        return FloatArray(out) { o ->
            var sum = b[o]
            val base = o * n
            for (i in 0 until n) sum += input[i] * w[base + i]
            sum
        }
    }

    private fun softmaxBest(logits: FloatArray): DigitGuess {
        val max = logits.max()
        val e = logits.map { exp((it - max).toDouble()) }
        val total = e.sum()
        val best = e.indices.maxBy { e[it] }
        return DigitGuess(best, e[best] / total)
    }

    companion object {
        /** Reads the weights: magic `BMD1`, then per tensor rank, dimensions and little-endian float32 values. */
        fun load(input: InputStream): DigitClassifier {
            val data = DataInputStream(input.buffered())
            val magic = ByteArray(4)
            data.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "BMD1") throw IOException("Not a digit model")
            val tensors = List(8) {
                val rank = readInt(data)
                if (rank !in 1..4) throw IOException("Invalid tensor rank")
                val count = readTensorSize(data, rank)
                val bytes = ByteArray(count * 4)
                data.readFully(bytes)
                FloatArray(count).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            }
            return try {
                DigitClassifier(tensors)
            } catch (e: IllegalArgumentException) {
                throw IOException(e.message, e)
            }
        }

        /**
         * Product of the [rank] dimensions; each must be positive and the total at most [MAX_TENSOR_SIZE], checked
         * per step so a broken file can neither overflow the count nor make us allocate a huge array.
         */
        private fun readTensorSize(data: DataInputStream, rank: Int): Int {
            var count = 1L
            for (i in 0 until rank) {
                val dimension = readInt(data)
                if (dimension <= 0) throw IOException("Invalid tensor dimension")
                count *= dimension
                if (count > MAX_TENSOR_SIZE) throw IOException("Invalid tensor size")
            }
            return count.toInt()
        }

        private const val MAX_TENSOR_SIZE = 1_000_000L

        private fun readInt(data: DataInputStream): Int {
            val bytes = ByteArray(4)
            data.readFully(bytes)
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
        }
    }
}

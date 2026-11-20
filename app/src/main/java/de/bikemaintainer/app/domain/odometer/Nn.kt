package de.bikemaintainer.app.domain.odometer

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * The few network layers the odometer models need, in plain Kotlin (no library). Tensors are channel-first float
 * arrays: channel c, row y, column x at `c * h * w + y * w + x`. Batch norms are folded into the weights on export.
 */
internal object Nn {
    /** 3×3 convolution, padding 1, then ReLU. Loops ordered so the innermost one runs along a row. */
    fun conv3(x: FloatArray, inC: Int, w: Int, h: Int, weights: FloatArray, bias: FloatArray, outC: Int): FloatArray {
        val n = w * h
        val out = FloatArray(outC * n)
        for (o in 0 until outC) {
            val ob = o * n
            java.util.Arrays.fill(out, ob, ob + n, bias[o])
            for (i in 0 until inC) {
                val ib = i * n
                val wb = (o * inC + i) * 9
                for (ky in 0 until 3) {
                    val dy = ky - 1
                    val y0 = max(0, -dy)
                    val y1 = min(h, h - dy)
                    for (kx in 0 until 3) {
                        val wv = weights[wb + ky * 3 + kx]
                        if (wv == 0f) continue
                        val dx = kx - 1
                        val x0 = max(0, -dx)
                        val x1 = min(w, w - dx)
                        for (y in y0 until y1) {
                            val orow = ob + y * w
                            val irow = ib + (y + dy) * w + dx
                            for (xx in x0 until x1) out[orow + xx] += x[irow + xx] * wv
                        }
                    }
                }
            }
            relu(out, ob, ob + n)
        }
        return out
    }

    /** Convolution along a sequence of [n] steps, kernel 3, padding 1, then ReLU. */
    fun conv1(x: FloatArray, inC: Int, n: Int, weights: FloatArray, bias: FloatArray, outC: Int): FloatArray {
        val out = FloatArray(outC * n)
        for (o in 0 until outC) {
            val ob = o * n
            java.util.Arrays.fill(out, ob, ob + n, bias[o])
            for (i in 0 until inC) {
                val ib = i * n
                val wb = (o * inC + i) * 3
                for (k in 0 until 3) {
                    val wv = weights[wb + k]
                    val d = k - 1
                    for (t in max(0, -d) until min(n, n - d)) out[ob + t] += x[ib + t + d] * wv
                }
            }
            relu(out, ob, ob + n)
        }
        return out
    }

    /** 1×1 convolution along a sequence (a dense layer per step), no activation. */
    fun pointwise(x: FloatArray, inC: Int, n: Int, weights: FloatArray, bias: FloatArray, outC: Int): FloatArray =
        FloatArray(outC * n) { p ->
            val o = p / n
            val t = p % n
            var s = bias[o]
            for (i in 0 until inC) s += x[i * n + t] * weights[o * inC + i]
            s
        }

    /** Max pooling by [kx] × [ky] (no padding; a remainder is dropped). */
    fun pool(x: FloatArray, c: Int, w: Int, h: Int, kx: Int = 2, ky: Int = 2): FloatArray {
        val ow = w / kx
        val oh = h / ky
        val out = FloatArray(c * ow * oh)
        for (ch in 0 until c) {
            val b = ch * w * h
            val ob = ch * ow * oh
            for (y in 0 until oh) for (xx in 0 until ow) {
                var m = Float.NEGATIVE_INFINITY
                for (dy in 0 until ky) for (dx in 0 until kx) m = maxOf(m, x[b + (y * ky + dy) * w + xx * kx + dx])
                out[ob + y * ow + xx] = m
            }
        }
        return out
    }

    private fun relu(a: FloatArray, from: Int, to: Int) {
        for (p in from until to) if (a[p] < 0f) a[p] = 0f
    }

    private const val MAX_TENSOR_SIZE = 1_000_000L

    /** Reads weights: [magic], tensor count, then per tensor rank, dimensions and little-endian float32. */
    fun readTensors(input: InputStream, magic: String): List<FloatArray> {
        val data = DataInputStream(input.buffered())
        val head = ByteArray(4)
        data.readFully(head)
        if (String(head, Charsets.US_ASCII) != magic) throw IOException("Not an odometer model")
        val count = readInt(data)
        if (count !in 1..64) throw IOException("Invalid tensor count")
        return List(count) {
            val rank = readInt(data)
            if (rank !in 1..4) throw IOException("Invalid tensor rank")
            var size = 1L
            repeat(rank) {
                val d = readInt(data)
                if (d <= 0) throw IOException("Invalid tensor dimension")
                size *= d
                if (size > MAX_TENSOR_SIZE) throw IOException("Invalid tensor size")
            }
            val bytes = ByteArray(size.toInt() * 4)
            data.readFully(bytes)
            FloatArray(size.toInt()).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        }
    }

    private fun readInt(data: DataInputStream): Int {
        val bytes = ByteArray(4)
        data.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }
}

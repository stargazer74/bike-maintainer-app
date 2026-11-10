package de.bikemaintainer.app.domain.scan

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * An 8-bit grey image, row by row (0 = black, 255 = white). Lives only in memory while a photo is read; never
 * written anywhere.
 */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray = ByteArray(width * height)) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Invalid image size" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xff

    operator fun set(x: Int, y: Int, value: Int) {
        pixels[y * width + x] = value.coerceIn(0, 255).toByte()
    }

    /** Bilinear value at a sub-pixel position; outside the image counts as white (paper). */
    fun sample(x: Double, y: Double): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        fun at(px: Int, py: Int): Double = if (px in 0 until width && py in 0 until height) get(px, py).toDouble() else 255.0
        val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
        val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
        return top * (1 - fy) + bottom * fy
    }

    /** Shrunk by an integer factor (mean of each block), so the long side is at most [maxSide]. */
    fun downscaled(maxSide: Int): Pair<GrayImage, Int> {
        val factor = max(1, (max(width, height) + maxSide - 1) / maxSide)
        if (factor == 1) return this to 1
        val w = width / factor
        val h = height / factor
        val out = GrayImage(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (dy in 0 until factor) {
                    val row = (y * factor + dy) * width
                    for (dx in 0 until factor) sum += pixels[row + x * factor + dx].toInt() and 0xff
                }
                out[x, y] = sum / (factor * factor)
            }
        }
        return out to factor
    }

    /** The image turned clockwise by 0, 90, 180 or 270 degrees, as a camera reports the sensor rotation. */
    fun rotated(degrees: Int): GrayImage = when (((degrees % 360) + 360) % 360) {
        0 -> this
        90 -> GrayImage(height, width).also { out ->
            for (y in 0 until height) for (x in 0 until width) out[height - 1 - y, x] = get(x, y)
        }
        180 -> GrayImage(width, height).also { out ->
            for (y in 0 until height) for (x in 0 until width) out[width - 1 - x, height - 1 - y] = get(x, y)
        }
        270 -> GrayImage(height, width).also { out ->
            for (y in 0 until height) for (x in 0 until width) out[y, width - 1 - x] = get(x, y)
        }
        else -> throw IllegalArgumentException("Rotation must be a multiple of 90 degrees")
    }

    companion object {
        /** Luminance of packed ARGB pixels (as from `Bitmap.getPixels`). */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage {
            val out = GrayImage(width, height)
            for (i in argb.indices) {
                val c = argb[i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                out.pixels[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
            return out
        }
    }
}

/** A point in image pixels or page points. */
data class Point(val x: Double, val y: Double)

/** Projective mapping from page points to image pixels, from four point pairs. */
class Homography private constructor(private val h: DoubleArray) {

    fun map(x: Double, y: Double): Point {
        val w = h[6] * x + h[7] * y + 1.0
        return Point((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }

    companion object {
        /** `null` if the points are degenerate (e.g. three on one line). */
        fun fromPoints(from: List<Point>, to: List<Point>): Homography? {
            require(from.size == 4 && to.size == 4)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = from[i]
                val (u, v) = to[i]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            return solve(a)?.let { Homography(it) }
        }

        /** Gaussian elimination with partial pivoting on an 8×9 augmented matrix. */
        private fun solve(a: Array<DoubleArray>): DoubleArray? {
            val n = 8
            for (col in 0 until n) {
                var pivot = col
                for (row in col + 1 until n) if (kotlin.math.abs(a[row][col]) > kotlin.math.abs(a[pivot][col])) pivot = row
                if (kotlin.math.abs(a[pivot][col]) < 1e-12) return null
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                for (row in 0 until n) {
                    if (row == col) continue
                    val f = a[row][col] / a[col][col]
                    for (k in col until n + 1) a[row][k] -= f * a[col][k]
                }
            }
            return DoubleArray(n) { a[it][n] / a[it][it] }
        }
    }
}

internal fun clamp01(v: Double) = min(1.0, max(0.0, v))

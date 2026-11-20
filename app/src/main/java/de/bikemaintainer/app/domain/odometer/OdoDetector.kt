package de.bikemaintainer.app.domain.odometer

import de.bikemaintainer.app.domain.scan.GrayImage
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A point in photo pixels. */
data class Point(val x: Double, val y: Double)

/**
 * The total-distance line found in a photo: four corners clockwise from the top left of the upright display, in
 * photo pixels, and how sure the model is (mean mask probability of the region, 0..1). [unsure] lines are offered
 * for adjusting rather than read right away (app#38).
 */
data class OdoLine(val corners: List<Point>, val confidence: Double) {
    val unsure: Boolean get() = confidence < SURE
    companion object {
        /** Below this mean probability the user should check the frame. */
        const val SURE = 0.75
    }
}

/**
 * Finds the ODO line (total distance) in a photo of an odometer – entirely on the device, no library, no network
 * (app#38). A small U-Net marks the line in a 96×72 mask of the photo scaled to 192×144; the strongest marked region
 * becomes an oriented rectangle. Weights from `assets/odo-detector.bin` (trained on synthetic photos, see
 * `docs/odometer-model.md`), batch norms folded into the convolutions.
 *
 * Layers (3×3 convolutions with ReLU, max pool 2, nearest upsampling 2, skips concatenated):
 * e1 1→12 · e2 12→24 · e3 24→32→32 · e4 32→48→48 · b 48→64→64 · d4 (64+48)→48 · d3 (48+32)→32 · d2 (32+24)→12 · 1×1 →1
 */
class OdoDetector(private val t: List<FloatArray>) {
    private val shapes = listOf(
        1 to E1, E1 to E2, E2 to E3, E3 to E3, E3 to E4, E4 to E4, E4 to B, B to B, B + E4 to D4, D4 + E3 to D3,
        D3 + E2 to D2,
    )

    init {
        require(t.size == shapes.size * 2 + 2) { "Expected ${shapes.size * 2 + 2} weight tensors" }
        shapes.forEachIndexed { i, (inC, outC) ->
            require(t[2 * i].size == outC * inC * 9 && t[2 * i + 1].size == outC) { "Unexpected weight shapes" }
        }
        require(t[shapes.size * 2].size == D2 && t[shapes.size * 2 + 1].size == 1) { "Unexpected head shape" }
    }

    /** The line in [photo], or null if the model marks nothing. */
    fun detect(photo: GrayImage): OdoLine? {
        val input = normalised(scaled(photo))
        val logits = logits(input)
        val prob = FloatArray(logits.size) { (1.0 / (1.0 + exp(-logits[it].toDouble()))).toFloat() }
        return lineOf(prob, OUT_W, OUT_H, photo.width.toDouble(), photo.height.toDouble())
    }

    /** Mask logits (96×72) for a normalised 192×144 input. */
    internal fun logits(input: FloatArray): FloatArray {
        require(input.size == IN_W * IN_H)
        var k = 0
        fun layer(x: FloatArray, inC: Int, w: Int, h: Int): FloatArray {
            val (_, outC) = shapes[k]
            val out = conv3(x, inC, w, h, t[2 * k], t[2 * k + 1], outC)
            k++
            return out
        }
        val e1 = layer(input, 1, IN_W, IN_H)
        val e2 = layer(pool(e1, E1, IN_W, IN_H), E1, IN_W / 2, IN_H / 2)
        var e3 = layer(pool(e2, E2, IN_W / 2, IN_H / 2), E2, IN_W / 4, IN_H / 4)
        e3 = layer(e3, E3, IN_W / 4, IN_H / 4)
        var e4 = layer(pool(e3, E3, IN_W / 4, IN_H / 4), E3, IN_W / 8, IN_H / 8)
        e4 = layer(e4, E4, IN_W / 8, IN_H / 8)
        var b = layer(pool(e4, E4, IN_W / 8, IN_H / 8), E4, IN_W / 16, IN_H / 16)
        b = layer(b, B, IN_W / 16, IN_H / 16)
        val d4 = layer(concat(up(b, B, IN_W / 16, IN_H / 16), e4), B + E4, IN_W / 8, IN_H / 8)
        val d3 = layer(concat(up(d4, D4, IN_W / 8, IN_H / 8), e3), D4 + E3, IN_W / 4, IN_H / 4)
        val d2 = layer(concat(up(d3, D3, IN_W / 4, IN_H / 4), e2), D3 + E2, IN_W / 2, IN_H / 2)
        val hw = t[shapes.size * 2]
        val hb = t[shapes.size * 2 + 1][0]
        val n = OUT_W * OUT_H
        return FloatArray(n) { p ->
            var s = hb
            for (c in 0 until D2) s += d2[c * n + p] * hw[c]
            s
        }
    }

    /** 3×3 convolution, padding 1, then ReLU. Loops ordered so the innermost one runs along a row. */
    private fun conv3(x: FloatArray, inC: Int, w: Int, h: Int, weights: FloatArray, bias: FloatArray, outC: Int): FloatArray {
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
            for (p in ob until ob + n) if (out[p] < 0f) out[p] = 0f
        }
        return out
    }

    private fun pool(x: FloatArray, c: Int, w: Int, h: Int): FloatArray {
        val hw = w / 2
        val hh = h / 2
        val out = FloatArray(c * hw * hh)
        for (ch in 0 until c) {
            val b = ch * w * h
            val ob = ch * hw * hh
            for (y in 0 until hh) for (xx in 0 until hw) {
                val p = b + 2 * y * w + 2 * xx
                out[ob + y * hw + xx] = maxOf(maxOf(x[p], x[p + 1]), maxOf(x[p + w], x[p + w + 1]))
            }
        }
        return out
    }

    private fun up(x: FloatArray, c: Int, w: Int, h: Int): FloatArray {
        val w2 = w * 2
        val out = FloatArray(c * w2 * h * 2)
        for (ch in 0 until c) {
            val b = ch * w * h
            val ob = ch * w2 * h * 2
            for (y in 0 until h * 2) for (xx in 0 until w2) out[ob + y * w2 + xx] = x[b + (y / 2) * w + xx / 2]
        }
        return out
    }

    private fun concat(a: FloatArray, b: FloatArray): FloatArray = FloatArray(a.size + b.size).also {
        a.copyInto(it)
        b.copyInto(it, a.size)
    }

    companion object {
        const val IN_W = 192
        const val IN_H = 144
        const val OUT_W = 96
        const val OUT_H = 72
        private const val E1 = 12
        private const val E2 = 24
        private const val E3 = 32
        private const val E4 = 48
        private const val B = 64
        private const val D4 = 48
        private const val D3 = 32
        private const val D2 = 12

        /** Mask cells above this probability belong to the line. */
        private const val THRESHOLD = 0.5f

        /**
         * The photo at 192×144 as an area average (each output pixel the mean of the photo area it covers, like
         * Pillow's BOX filter the model was trained with). Other aspect ratios are stretched, as in training.
         */
        fun scaled(photo: GrayImage): FloatArray {
            val sx = photo.width.toDouble() / IN_W
            val sy = photo.height.toDouble() / IN_H
            val out = FloatArray(IN_W * IN_H)
            for (oy in 0 until IN_H) {
                val fy0 = oy * sy
                val fy1 = (oy + 1) * sy
                for (ox in 0 until IN_W) {
                    val fx0 = ox * sx
                    val fx1 = (ox + 1) * sx
                    var sum = 0.0
                    var area = 0.0
                    var y = fy0.toInt()
                    while (y < fy1 && y < photo.height) {
                        val wy = min(fy1, y + 1.0) - max(fy0, y.toDouble())
                        var xx = fx0.toInt()
                        while (xx < fx1 && xx < photo.width) {
                            val wx = min(fx1, xx + 1.0) - max(fx0, xx.toDouble())
                            sum += photo[xx, y] * wx * wy
                            area += wx * wy
                            xx++
                        }
                        y++
                    }
                    out[oy * IN_W + ox] = (sum / area).toFloat()
                }
            }
            return out
        }

        /** Per photo: values 0..1, then mean 0 and standard deviation 1 (exposure and contrast vary a lot). */
        fun normalised(pixels: FloatArray): FloatArray {
            var mean = 0.0
            for (v in pixels) mean += v / 255.0
            mean /= pixels.size
            var variance = 0.0
            for (v in pixels) {
                val d = v / 255.0 - mean
                variance += d * d
            }
            val std = sqrt(variance / pixels.size) + 1e-3
            return FloatArray(pixels.size) { ((pixels[it] / 255.0 - mean) / std).toFloat() }
        }

        /**
         * The strongest connected region of the mask (by probability mass) as an oriented rectangle from its second
         * moments, in photo pixels ([width] × [height]). Same steps as `quad_of` in `train_detector.py`.
         */
        fun lineOf(prob: FloatArray, mw: Int, mh: Int, width: Double, height: Double): OdoLine? {
            val labels = IntArray(prob.size)
            var best = 0
            var bestMass = 0.0
            var current = 0
            val stack = IntArray(prob.size)
            for (start in prob.indices) {
                if (prob[start] <= THRESHOLD || labels[start] != 0) continue
                current++
                var top = 0
                stack[top++] = start
                labels[start] = current
                var mass = 0.0
                while (top > 0) {
                    val p = stack[--top]
                    mass += prob[p]
                    val x = p % mw
                    val y = p / mw
                    fun visit(q: Int) {
                        if (prob[q] > THRESHOLD && labels[q] == 0) {
                            labels[q] = current
                            stack[top++] = q
                        }
                    }
                    if (y > 0) visit(p - mw)
                    if (y < mh - 1) visit(p + mw)
                    if (x > 0) visit(p - 1)
                    if (x < mw - 1) visit(p + 1)
                }
                if (mass > bestMass) {
                    best = current
                    bestMass = mass
                }
            }
            if (best == 0) return null
            val cellW = width / mw
            val cellH = height / mh
            var sw = 0.0
            var cx = 0.0
            var cy = 0.0
            var count = 0
            for (p in prob.indices) if (labels[p] == best) {
                val w = prob[p].toDouble()
                sw += w
                cx += w * (p % mw + 0.5) * cellW
                cy += w * (p / mw + 0.5) * cellH
                count++
            }
            cx /= sw
            cy /= sw
            var cxx = 0.0
            var cyy = 0.0
            var cxy = 0.0
            for (p in prob.indices) if (labels[p] == best) {
                val w = prob[p].toDouble()
                val dx = (p % mw + 0.5) * cellW - cx
                val dy = (p / mw + 0.5) * cellH - cy
                cxx += w * dx * dx
                cyy += w * dy * dy
                cxy += w * dx * dy
            }
            cxx /= sw
            cyy /= sw
            cxy /= sw
            val angle = 0.5 * atan2(2 * cxy, cxx - cyy)
            var ux = cos(angle)
            var uy = sin(angle)
            if (ux < 0) {
                ux = -ux
                uy = -uy
            }
            val vx = -uy
            val vy = ux
            var along = 0.0
            var across = 0.0
            for (p in prob.indices) if (labels[p] == best) {
                val w = prob[p].toDouble()
                val dx = (p % mw + 0.5) * cellW - cx
                val dy = (p / mw + 0.5) * cellH - cy
                val a = dx * ux + dy * uy
                val c = dx * vx + dy * vy
                along += w * a * a
                across += w * c * c
            }
            // A uniform rectangle of length L has variance L²/12 along that axis.
            val halfL = sqrt(3 * along / sw)
            val halfH = max(sqrt(3 * across / sw), cellW / 2)
            val corners = listOf(
                Point(cx - ux * halfL - vx * halfH, cy - uy * halfL - vy * halfH),
                Point(cx + ux * halfL - vx * halfH, cy + uy * halfL - vy * halfH),
                Point(cx + ux * halfL + vx * halfH, cy + uy * halfL + vy * halfH),
                Point(cx - ux * halfL + vx * halfH, cy - uy * halfL + vy * halfH),
            )
            return OdoLine(corners, sw / count)
        }

        /** Reads the weights: magic `BMO1`, tensor count, then per tensor rank, dimensions and little-endian float32. */
        fun load(input: InputStream): OdoDetector {
            val data = DataInputStream(input.buffered())
            val magic = ByteArray(4)
            data.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "BMO1") throw IOException("Not an odometer model")
            val count = readInt(data)
            if (count !in 1..64) throw IOException("Invalid tensor count")
            val tensors = List(count) {
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
            return try {
                OdoDetector(tensors)
            } catch (e: IllegalArgumentException) {
                throw IOException(e.message, e)
            }
        }

        private const val MAX_TENSOR_SIZE = 1_000_000L

        private fun readInt(data: DataInputStream): Int {
            val bytes = ByteArray(4)
            data.readFully(bytes)
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
        }
    }
}

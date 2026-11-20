package de.bikemaintainer.app.domain.odometer

import de.bikemaintainer.app.domain.scan.GrayImage
import de.bikemaintainer.app.domain.scan.Homography
import java.io.IOException
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

import kotlin.math.sqrt
import de.bikemaintainer.app.domain.scan.Point as ScanPoint

/**
 * One way to read the line: the value in whole km/mi, whether a digit is unsure, and how likely the model thinks the
 * role of the last digit is (integer digit or tenths) – see [OdoReader.candidates].
 */
data class OdoCandidate(val value: Int, val unsure: Boolean, val weight: Double)

/** What the reader suggests: [value] (null if nothing was read) and whether the user should check it. */
data class OdoReading(val value: Int?, val unsure: Boolean)

/**
 * Reads the digits of the ODO line (app#39), entirely on the device. The line is cut out of the photo along its four
 * corners and straightened ([crop]); a small CNN turns it into 40 columns with, per column, the probability of blank,
 * digits 0–9 and tenths digits 0–9, and whether a roll is turning there; CTC decoding gives the digits ([candidates]).
 * [OdoPlausibility] picks the value against the last known mileage. Weights from `assets/odo-reader.bin`, trained on
 * synthetic photos (`tools/odometer-model/train_reader.py`, `docs/odometer-model.md`). Every step mirrors the Python
 * code of the same name.
 *
 * Layers (3×3 convolutions with ReLU): 1→32, pool 2 · 32→48, pool 2 · 48→64 · 64→64, pool 2×1 · 64→96, max over the
 * height · along the line: conv 3 96→96 twice · per column: 21 classes and a turning flag.
 */
class OdoReader(private val t: List<FloatArray>) {
    private val convs = listOf(1 to C1, C1 to C2, C2 to C3, C3 to C4, C4 to C5)

    init {
        require(t.size == 18) { "Expected 18 weight tensors" }
        convs.forEachIndexed { i, (inC, outC) ->
            require(t[2 * i].size == outC * inC * 9 && t[2 * i + 1].size == outC) { "Unexpected weight shapes" }
        }
        require(t[10].size == T * C5 * 3 && t[11].size == T && t[12].size == T * T * 3 && t[13].size == T) {
            "Unexpected weight shapes"
        }
        require(t[14].size == CLASSES * T && t[15].size == CLASSES && t[16].size == T && t[17].size == 1) {
            "Unexpected head shapes"
        }
    }

    /** Reads the line with the given corners in [photo], against the last known mileage [last] (null if none). */
    fun read(photo: GrayImage, corners: List<Point>, last: Int?, maxJump: Int = OdoPlausibility.MAX_JUMP): OdoReading {
        val crop = crop(photo, corners) ?: return OdoReading(null, true)
        val (logits, roll) = forward(normalised(crop))
        return OdoPlausibility.choose(candidates(softmax(logits), sigmoid(roll)), last, maxJump)
    }

    /** Class logits (21 × 40, class-major) and turning logits (40) for a normalised 32 × 160 crop. */
    internal fun forward(input: FloatArray): Pair<FloatArray, FloatArray> {
        require(input.size == H * MAX_W)
        var x = Nn.pool(Nn.conv3(input, 1, MAX_W, H, t[0], t[1], C1), C1, MAX_W, H)
        x = Nn.pool(Nn.conv3(x, C1, MAX_W / 2, H / 2, t[2], t[3], C2), C2, MAX_W / 2, H / 2)
        val w = MAX_W / 4
        x = Nn.conv3(x, C2, w, H / 4, t[4], t[5], C3)
        x = Nn.pool(Nn.conv3(x, C3, w, H / 4, t[6], t[7], C4), C4, w, H / 4, kx = 1, ky = 2)
        x = Nn.conv3(x, C4, w, H / 8, t[8], t[9], C5)
        // Max over the remaining height: one feature vector per column.
        val rows = H / 8
        val seq = FloatArray(C5 * FRAMES) { p ->
            val c = p / FRAMES
            val f = p % FRAMES
            var m = Float.NEGATIVE_INFINITY
            for (y in 0 until rows) m = maxOf(m, x[c * rows * w + y * w + f])
            m
        }
        val h1 = Nn.conv1(seq, C5, FRAMES, t[10], t[11], T)
        val h2 = Nn.conv1(h1, T, FRAMES, t[12], t[13], T)
        return Nn.pointwise(h2, T, FRAMES, t[14], t[15], CLASSES) to Nn.pointwise(h2, T, FRAMES, t[16], t[17], 1)
    }

    /** A straightened line: [pixels] are H × [MAX_W] grey values, of which the first [width] columns are the line. */
    class Crop(val pixels: FloatArray, val width: Int)

    companion object {
        const val H = 32
        const val MAX_W = 160
        const val FRAMES = 40
        const val CLASSES = 21
        private const val MARGIN_Y = 0.25
        private const val MARGIN_X = 0.3
        private const val C1 = 32
        private const val C2 = 48
        private const val C3 = 64
        private const val C4 = 64
        private const val C5 = 96
        private const val T = 96

        /** A digit below this probability is unsure. */
        const val SURE = 0.98
        /** A blank column that shows a digit this strongly, which no neighbour reads, may hide a missing digit. */
        const val GHOST = 0.35
        /** A roll turning under a digit with this probability makes it unsure. */
        const val ROLLING = 0.3

        /**
         * The line straightened: the four [corners] (clockwise from the top left) with a margin, 32 px high and at
         * most 160 px wide, sampled bilinearly (edge pixels repeated outside the photo) with s×s supersampling where
         * the line is larger than the crop, rounded to whole grey values. Null for a degenerate quad.
         */
        fun crop(photo: GrayImage, corners: List<Point>): Crop? {
            require(corners.size == 4)
            if (photo.width < 2 || photo.height < 2) return null
            val (q0, q1, q2, q3) = corners
            val length = (dist(q0, q1) + dist(q3, q2)) / 2
            val height = max(1.0, (dist(q0, q3) + dist(q1, q2)) / 2)
            val mx = MARGIN_X * height / max(length, 1.0)
            val w = Math.rint(H * length * (1 + 2 * mx) / (height * (1 + 2 * MARGIN_Y))).toInt().coerceIn(16, MAX_W)
            val unit = listOf(ScanPoint(0.0, 0.0), ScanPoint(1.0, 0.0), ScanPoint(1.0, 1.0), ScanPoint(0.0, 1.0))
            val m = Homography.fromPoints(unit, corners.map { ScanPoint(it.x, it.y) }) ?: return null
            val s = min(4.0, max(1.0, ceil(height * (1 + 2 * MARGIN_Y) / H))).toInt()
            val out = FloatArray(H * MAX_W)
            for (y in 0 until H) for (x in 0 until w) {
                var sum = 0.0
                for (sy in 0 until s) for (sx in 0 until s) {
                    val u = -mx + (x + (sx + 0.5) / s) / w * (1 + 2 * mx)
                    val v = -MARGIN_Y + (y + (sy + 0.5) / s) / H * (1 + 2 * MARGIN_Y)
                    val p = m.map(u, v)
                    sum += bilinear(photo, p.x, p.y)
                }
                out[y * MAX_W + x] = Math.rint(sum / (s * s)).coerceIn(0.0, 255.0).toFloat()
            }
            return Crop(out, w)
        }

        /** Pixel centres at integer + 0.5; outside the photo the edge repeats. */
        private fun bilinear(img: GrayImage, px: Double, py: Double): Double {
            val x = (px - 0.5).coerceIn(0.0, img.width - 1.0)
            val y = (py - 0.5).coerceIn(0.0, img.height - 1.0)
            val x0 = min(floor(x).toInt(), img.width - 2)
            val y0 = min(floor(y).toInt(), img.height - 2)
            val fx = x - x0
            val fy = y - y0
            val top = img[x0, y0] * (1 - fx) + img[x0 + 1, y0] * fx
            val bottom = img[x0, y0 + 1] * (1 - fx) + img[x0 + 1, y0 + 1] * fx
            return top * (1 - fy) + bottom * fy
        }

        private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

        /** Over the line's columns: values 0..1, then mean 0 and standard deviation 1; the padding becomes 0. */
        fun normalised(crop: Crop): FloatArray {
            val n = H * crop.width
            var mean = 0.0
            for (y in 0 until H) for (x in 0 until crop.width) mean += crop.pixels[y * MAX_W + x] / 255.0
            mean /= n
            var variance = 0.0
            for (y in 0 until H) for (x in 0 until crop.width) {
                val d = crop.pixels[y * MAX_W + x] / 255.0 - mean
                variance += d * d
            }
            val std = sqrt(variance / n) + 1e-3
            val out = FloatArray(H * MAX_W)
            for (y in 0 until H) for (x in 0 until crop.width) {
                out[y * MAX_W + x] = ((crop.pixels[y * MAX_W + x] / 255.0 - mean) / std).toFloat()
            }
            return out
        }

        /** Per column, probabilities over the 21 classes (class-major like the logits). */
        fun softmax(logits: FloatArray): Array<DoubleArray> = Array(FRAMES) { f ->
            var m = Double.NEGATIVE_INFINITY
            for (c in 0 until CLASSES) m = max(m, logits[c * FRAMES + f].toDouble())
            val e = DoubleArray(CLASSES) { c -> exp(logits[c * FRAMES + f] - m) }
            val sum = e.sum()
            DoubleArray(CLASSES) { e[it] / sum }
        }

        fun sigmoid(logits: FloatArray): DoubleArray = DoubleArray(logits.size) { 1.0 / (1.0 + exp(-logits[it].toDouble())) }

        private class Char(val digit: Int, var conf: Double, val frames: MutableList<Int>)

        /**
         * The values the columns [p] (per column: blank, digits 0–9, tenths 0–9) can mean. Digits are read with both
         * roles merged; whether the last one is the tenths digit is often not visible (a 100 m roll in the same
         * colour, no decimal point), so up to two readings come out: all digits as integer digits, and the last one
         * dropped as tenths. A reading is unsure if a digit's probability is below [SURE], a roll turns under one of
         * its digits ([roll] above [ROLLING]), a blank column shows a digit none of its neighbours reads ([GHOST]),
         * or – for the second reading – the dropped tenths digit is 9 or 0: then the next roll is turning along (a
         * half-turned roll counts as its lower digit, like reading a meter).
         */
        fun candidates(p: Array<DoubleArray>, roll: DoubleArray): List<OdoCandidate> {
            val frames = p.size
            val merged = Array(frames) { f -> DoubleArray(11) { k -> if (k == 0) p[f][0] else p[f][k] + p[f][k + 10] } }
            val best = IntArray(frames) { f -> argmax(merged[f], 0, 11) }
            val chars = mutableListOf<Char>()
            var prev = 0
            for (f in 0 until frames) {
                val c = best[f]
                if (c != 0 && c != prev) {
                    chars += Char(c - 1, merged[f][c], mutableListOf(f))
                } else if (c != 0) {
                    chars.last().conf = max(chars.last().conf, merged[f][c])
                    chars.last().frames += f
                }
                prev = c
            }
            if (chars.isEmpty()) return emptyList()
            var shaky = false
            for (f in 0 until frames) {
                if (best[f] != 0) continue
                val k = argmax(merged[f], 1, 11)
                if (merged[f][k] > GHOST) shaky = shaky || (f - 1..f + 1 step 2).none { it in 0 until frames && best[it] == k }
            }

            fun unsure(cs: List<Char>) =
                shaky || cs.any { it.conf < SURE } || cs.any { c -> c.frames.any { roll[it] > ROLLING } }

            val last = chars.last()
            val d = last.digit
            val tShare = last.frames.maxOf { f -> p[f][11 + d] / (p[f][1 + d] + p[f][11 + d] + 1e-9) }
            // As an integer digit the last one is read from the integer classes alone (a tenths roll shows its lower
            // digit half turned; as an integer digit that may be the next one).
            val fInt = last.frames.maxBy { f -> (1..10).maxOf { p[f][it] } }
            val dInt = argmax(p[fInt], 1, 11) - 1
            val pInt = p[fInt][1 + dInt] / ((1..10).sumOf { p[fInt][it] } + 1e-9)
            val whole = chars.dropLast(1) + Char(dInt, if (tShare > 0.5) min(last.conf, pInt) else last.conf, last.frames)
            val out = mutableListOf<OdoCandidate>()
            valueOf(whole)?.let { out += OdoCandidate(it, unsure(whole), 1 - tShare) }
            if (chars.size > 1) {
                val rest = chars.dropLast(1)
                valueOf(rest)?.let { out += OdoCandidate(it, unsure(rest) || d == 9 || d == 0, tShare) }
            }
            return out
        }

        /** First index of the largest value in [from] until [to]. */
        private fun argmax(a: DoubleArray, from: Int, to: Int): Int {
            var b = from
            for (i in from + 1 until to) if (a[i] > a[b]) b = i
            return b
        }

        /** Digits as a number; null for more than 9 digits (no odometer has that many). */
        private fun valueOf(cs: List<Char>): Int? =
            if (cs.isEmpty() || cs.size > 9) null else cs.fold(0) { acc, c -> acc * 10 + c.digit }

        /** Reads the weights (`assets/odo-reader.bin`, magic `BMR1`, see [Nn.readTensors]). */
        fun load(input: InputStream): OdoReader = try {
            OdoReader(Nn.readTensors(input, "BMR1"))
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
    }
}

/**
 * Picks the value from the readings against the last known mileage (app#39): a reading below it or more than
 * [MAX_JUMP] above is implausible. Same rules as `choose` in `train_reader.py`.
 */
object OdoPlausibility {
    /** More than this above the last mileage is not believed without a check. */
    const val MAX_JUMP = 20_000

    fun plausible(value: Int, last: Int?, maxJump: Int = MAX_JUMP): Boolean =
        last == null || value >= last && value.toLong() <= last.toLong() + maxJump

    /**
     * The only plausible reading, unsure only if its digits are. Otherwise – no last mileage, both readings fit or
     * none does – the one the model prefers, marked unsure: whether the last digit is the tenths one is not reliable
     * enough from the picture alone.
     */
    fun choose(candidates: List<OdoCandidate>, last: Int?, maxJump: Int = MAX_JUMP): OdoReading {
        if (candidates.isEmpty()) return OdoReading(null, true)
        val ok = candidates.filter { plausible(it.value, last, maxJump) }
        if (ok.size == 1 && last != null) return OdoReading(ok[0].value, ok[0].unsure)
        val pick = (ok.ifEmpty { candidates }).maxBy { it.weight }
        return OdoReading(pick.value, pick.unsure || candidates.size > 1 || ok.isEmpty())
    }
}

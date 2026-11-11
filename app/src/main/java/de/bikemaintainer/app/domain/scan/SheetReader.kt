package de.bikemaintainer.app.domain.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Decodes a QR code in an upright, straightened image; `null` if there is none or it cannot be read. */
fun interface QrReader {
    fun read(image: GrayImage): String?
}

/**
 * What was read from one field. [value] for digits: e.g. `0.15` (always `.` as separator) or `""` if nothing was
 * written; [checked] for checkboxes. [uncertain] fields are highlighted for the user to check. [crop] shows the field
 * as photographed (straightened) next to the value – only in memory.
 */
data class FieldReading(
    val field: ScanLayout.Field,
    val value: String = "",
    val checked: Boolean = false,
    val uncertain: Boolean = false,
    val crop: GrayImage,
)

data class ScannedSheet(val code: SheetCode, val readings: List<FieldReading>)

sealed interface ScanOutcome {
    data class Read(val sheet: ScannedSheet) : ScanOutcome

    /** The four corner markers are not all in the picture. */
    data object NoSheet : ScanOutcome

    /** The QR code is missing, unreadable or not one of a worksheet. */
    data object NoCode : ScanOutcome

    /** Printed by a newer server with another layout; the app needs an update. */
    data class UnsupportedVersion(val version: Int) : ScanOutcome
}

/**
 * Reads a photo of a filled-in worksheet (Epic #120): corner markers → perspective → QR code → fields at the fixed
 * positions of [ScanLayout]. Runs entirely on the device, the photo is neither stored nor sent anywhere.
 */
class SheetReader(private val classifier: DigitClassifier, private val qrReader: QrReader) {

    fun read(photo: GrayImage): ScanOutcome {
        val markers = MarkerFinder.find(photo) ?: return ScanOutcome.NoSheet
        val page = Homography.fromPoints(ScanLayout.markerCentres, markers) ?: return ScanOutcome.NoSheet
        val margin = 10f
        val qr = rectify(photo, page, ScanLayout.QR_LEFT - margin, ScanLayout.QR_TOP - margin, ScanLayout.QR_SIZE + 2 * margin, ScanLayout.QR_SIZE + 2 * margin, QR_SCALE)
        val code = qrReader.read(qr)?.let(SheetCode::parse) ?: return ScanOutcome.NoCode
        if (code.version != ScanLayout.VERSION) return ScanOutcome.UnsupportedVersion(code.version)
        val fields = ScanLayout.pages(code.template, code.cylinders, code.valvesPerCylinder).getOrNull(code.page - 1)
            ?: return ScanOutcome.NoCode
        return ScanOutcome.Read(ScannedSheet(code, fields.map { readField(photo, page, it) }))
    }

    private fun readField(photo: GrayImage, page: Homography, field: ScanLayout.Field): FieldReading {
        val (dx, dy) = frameOffset(photo, page, field)
        val left = field.left + dx
        val top = field.top + dy
        val crop = rectify(photo, page, left - CROP_MARGIN, top - CROP_MARGIN, field.width + 2 * CROP_MARGIN, field.height + 2 * CROP_MARGIN, CROP_SCALE)
        val paper = paperLevel(crop)
        return when (field.kind) {
            ScanLayout.FieldKind.CHECKBOX -> {
                val inner = rectify(photo, page, left + BOX_INSET, top + BOX_INSET, ScanLayout.CHECKBOX_SIZE - 2 * BOX_INSET, ScanLayout.CHECKBOX_SIZE - 2 * BOX_INSET, READ_SCALE)
                val ink = inkMap(inner, paper)
                val filled = ink.count { it > 0.5f }.toDouble() / ink.size
                FieldReading(field, checked = filled > TICK_THRESHOLD, uncertain = filled in TICK_UNSURE, crop = crop)
            }
            ScanLayout.FieldKind.DIGITS -> {
                val digits = field.boxLefts().map { boxLeft ->
                    val inner = rectify(photo, page, boxLeft + dx + BOX_INSET, top + BOX_INSET, ScanLayout.DIGIT_WIDTH - 2 * BOX_INSET, ScanLayout.DIGIT_HEIGHT - 2 * BOX_INSET, READ_SCALE)
                    DigitBox.normalise(digitInkMap(inner, paper), inner.width, inner.height)?.let(classifier::classify)
                }
                val (value, uncertain) = assemble(digits, field.integerDigits)
                FieldReading(field, value = value, uncertain = uncertain, crop = crop)
            }
        }
    }

    companion object {
        /** How far (points) a printed frame may sit from its computed position and still be found. */
        private const val ALIGN_RANGE = 5f
        private const val ALIGN_SCALE = 4f
        /** Average darkness along the frame lines below which no frame is taken as found (the field stays put). */
        private const val MIN_FRAME_DARKNESS = 12

        /** Pixels per point of the straightened images. */
        private const val QR_SCALE = 4f
        private const val READ_SCALE = 4f
        private const val CROP_SCALE = 3f
        private const val CROP_MARGIN = 4f
        /** The box border is left out; the border line may be off by a point or two after straightening. */
        private const val BOX_INSET = 1.5f
        private const val TICK_THRESHOLD = 0.08
        private val TICK_UNSURE = 0.03..0.15
        /** Below this, a digit is marked for checking. */
        const val MIN_CONFIDENCE = 0.8
        /** Darkest stroke at least this much darker than the paper, or the digit box is empty. */
        private const val MIN_DIGIT_CONTRAST = 0.18f

        /** The part of the page from ([left], [top]) of size [width] × [height] points, upright, [scale] px per point. */
        fun rectify(photo: GrayImage, page: Homography, left: Float, top: Float, width: Float, height: Float, scale: Float): GrayImage {
            val w = max(1, (width * scale).roundToInt())
            val h = max(1, (height * scale).roundToInt())
            val out = GrayImage(w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val p = page.map((left + (x + 0.5f) / scale).toDouble(), (top + (y + 0.5f) / scale).toDouble())
                out[x, y] = photo.sample(p.x, p.y).roundToInt()
            }
            return out
        }

        /**
         * Where the printed frame of [field] really is, as an offset in points from its computed position. The four
         * corner markers fix the page as a whole, but lens distortion and paper that is not flat move single fields by a
         * few points – enough for a box border to end up inside the area that is read, where it looks like a "1". So
         * every field is aligned to its own frame: the offset within ±[ALIGN_RANGE] where the frame lines are darkest.
         */
        internal fun frameOffset(photo: GrayImage, page: Homography, field: ScanLayout.Field): Pair<Float, Float> {
            val range = (ALIGN_RANGE * ALIGN_SCALE).roundToInt()
            val area = rectify(photo, page, field.left - ALIGN_RANGE, field.top - ALIGN_RANGE, field.width + 2 * ALIGN_RANGE, field.height + 2 * ALIGN_RANGE, ALIGN_SCALE)
            val paper = paperLevel(area)
            val w = area.width
            val h = area.height
            val dark = IntArray(w * h) { max(0, paper - (area.pixels[it].toInt() and 0xff)) }
            fun px(points: Float) = (points * ALIGN_SCALE).roundToInt()
            val boxWidth = if (field.kind == ScanLayout.FieldKind.CHECKBOX) ScanLayout.CHECKBOX_SIZE else ScanLayout.DIGIT_WIDTH
            val boxes = if (field.kind == ScanLayout.FieldKind.CHECKBOX) listOf(0f) else field.boxLefts().map { it - field.left }
            // Frame lines relative to the field: top and bottom of every box (not across the separator), and every side.
            val horizontal = boxes.flatMap { b -> listOf(0f, field.height).map { y -> Triple(px(y), px(b), px(b + boxWidth)) } }
            val vertical = boxes.flatMap { listOf(px(it), px(it + boxWidth)) }.distinct()
            val lineHeight = px(field.height)
            val pixelsOnLines = horizontal.sumOf { it.third - it.second } + vertical.size * lineHeight
            var best = -1L
            var bestX = 0
            var bestY = 0
            for (oy in -range..range) for (ox in -range..range) {
                var score = 0L
                for ((y, x0, x1) in horizontal) {
                    val row = (range + y + oy).coerceIn(0, h - 1) * w
                    for (x in x0 until x1) score += dark[row + (range + x + ox).coerceIn(0, w - 1)]
                }
                for (x in vertical) {
                    val col = (range + x + ox).coerceIn(0, w - 1)
                    for (y in 0 until lineHeight) score += dark[(range + y + oy).coerceIn(0, h - 1) * w + col]
                }
                // Equal scores: the smaller correction wins.
                if (score > best || (score == best && abs(ox) + abs(oy) < abs(bestX) + abs(bestY))) {
                    best = score
                    bestX = ox
                    bestY = oy
                }
            }
            if (best < MIN_FRAME_DARKNESS.toLong() * pixelsOnLines) return 0f to 0f
            return bestX / ALIGN_SCALE to bestY / ALIGN_SCALE
        }

        /** Brightness of the paper around a field: a high percentile, so ink and box lines do not count. */
        internal fun paperLevel(image: GrayImage): Int {
            val histogram = IntArray(256)
            for (b in image.pixels) histogram[b.toInt() and 0xff]++
            var seen = 0
            val target = image.pixels.size * 0.9
            for (v in 0..255) {
                seen += histogram[v]
                if (seen >= target) return max(v, 40)
            }
            return 255
        }

        /**
         * Ink 0..1 per pixel, relative to the paper: nothing up to 25 % darker than the paper (grain, noise of a dim
         * photo – smoothed over 3×3 pixels first), full ink from 60 % darker on. Box lines are grey and stay faint.
         */
        internal fun inkMap(image: GrayImage, paper: Int): FloatArray {
            val w = image.width
            val h = image.height
            return FloatArray(w * h) { i ->
                val x = i % w
                val y = i / w
                var sum = 0
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) {
                        sum += image[nx, ny]
                        n++
                    }
                }
                val darker = (paper - sum.toFloat() / n) / paper
                ((darker - 0.25f) / 0.35f).coerceIn(0f, 1f)
            }
        }

        /**
         * Ink of a digit box, relative to its own darkest stroke: pale or blurred writing (a soft pencil, a photo slightly
         * out of focus) came out at only about 30 % darker than the paper and vanished under the fixed limits of
         * [inkMap]. Below [MIN_DIGIT_CONTRAST] the box counts as empty; strong ink gives about the same map as [inkMap].
         */
        internal fun digitInkMap(image: GrayImage, paper: Int): FloatArray {
            val smooth = smoothed(image)
            val darkest = smooth.sorted()[(smooth.size * 0.01).toInt()]
            val contrast = (paper - darkest) / paper
            if (contrast < MIN_DIGIT_CONTRAST) return FloatArray(smooth.size)
            val low = contrast * 0.35f
            val span = max(contrast * 0.5f, 0.05f)
            return FloatArray(smooth.size) { i -> (((paper - smooth[i]) / paper - low) / span).coerceIn(0f, 1f) }
        }

        private fun smoothed(image: GrayImage): FloatArray {
            val w = image.width
            val h = image.height
            return FloatArray(w * h) { i ->
                val x = i % w
                val y = i / w
                var sum = 0
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) {
                        sum += image[nx, ny]
                        n++
                    }
                }
                sum.toFloat() / n
            }
        }

        /**
         * The digits of a field as text: integer digits right-aligned, decimals left-aligned; e.g. boxes `_ , 1 5 _`
         * give `0.15`. Uncertain if a digit is unsure, digits have a gap, or nothing but decimals is missing a whole.
         */
        internal fun assemble(digits: List<DigitGuess?>, integerDigits: Int): Pair<String, Boolean> {
            if (digits.all { it == null }) return "" to false
            val integer = digits.take(integerDigits)
            val decimals = digits.drop(integerDigits)
            var uncertain = digits.any { it != null && it.confidence < MIN_CONFIDENCE }
            // A gap inside the integer part (e.g. "1 _ 2") or the decimals (e.g. ", 1 _ 5") is suspicious.
            if (hasGap(integer) || hasGap(decimals)) uncertain = true
            val intText = integer.filterNotNull().joinToString("") { it.digit.toString() }.ifEmpty { "0" }
            val decText = decimals.filterNotNull().joinToString("") { it.digit.toString() }
            return (if (decText.isEmpty()) intText else "$intText.$decText") to uncertain
        }

        private fun hasGap(part: List<DigitGuess?>): Boolean {
            val first = part.indexOfFirst { it != null }
            val last = part.indexOfLast { it != null }
            return first >= 0 && (first..last).any { part[it] == null }
        }
    }
}

/** Turns the ink of one digit box into the 28×28 input of the classifier, like MNIST: 20×20 box, centred by mass. */
internal object DigitBox {
    /** Less ink than this (in pixels at full strength) is an empty box or a speck of dirt. */
    private const val MIN_INK = 40f
    private const val EDGE = 5
    private const val BORDER_INK = 0.1f

    fun normalise(ink: FloatArray, width: Int, height: Int): FloatArray? {
        val cleaned = ink.copyOf()
        removeBorderLines(cleaned, width, height)
        removeSpecks(cleaned, width, height)
        if (cleaned.sum() < MIN_INK) return null
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (cleaned[y * width + x] > 0f) {
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val scale = 20f / max(bw, bh)
        val tw = max(1, (bw * scale).roundToInt())
        val th = max(1, (bh * scale).roundToInt())
        // Area-averaging resize of the bounding box to tw × th.
        val patch = FloatArray(tw * th)
        for (ty in 0 until th) for (tx in 0 until tw) {
            val sx0 = minX + tx / scale
            val sx1 = minX + (tx + 1) / scale
            val sy0 = minY + ty / scale
            val sy1 = minY + (ty + 1) / scale
            var sum = 0f; var area = 0f
            var sy = sy0.toInt()
            while (sy < sy1 && sy <= maxY) {
                val hy = min(sy1, sy + 1f) - max(sy0, sy.toFloat())
                var sx = sx0.toInt()
                while (sx < sx1 && sx <= maxX) {
                    val wx = min(sx1, sx + 1f) - max(sx0, sx.toFloat())
                    sum += cleaned[sy * width + sx] * wx * hy
                    area += wx * hy
                    sx++
                }
                sy++
            }
            patch[ty * tw + tx] = if (area > 0f) sum / area else 0f
        }
        thicken(patch, tw, th)
        var mass = 0f; var mx = 0f; var my = 0f
        for (y in 0 until th) for (x in 0 until tw) {
            val v = patch[y * tw + x]
            mass += v; mx += v * x; my += v * y
        }
        val ox = (14f - (if (mass > 0) mx / mass else tw / 2f) - 0.5f).roundToInt()
        val oy = (14f - (if (mass > 0) my / mass else th / 2f) - 0.5f).roundToInt()
        val out = FloatArray(28 * 28)
        for (y in 0 until th) for (x in 0 until tw) {
            val dx = x + ox
            val dy = y + oy
            if (dx in 0 until 28 && dy in 0 until 28) out[dy * 28 + dx] = patch[y * tw + x].coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * Remnants of the box border: rows/columns near the edge that carry ink over most of their length. The printed
     * border is grey and comes out faint, so any ink counts here – a faint line left in was read as a confident "1".
     */
    private fun removeBorderLines(ink: FloatArray, width: Int, height: Int) {
        for (y in (0 until min(EDGE, height)) + (max(0, height - EDGE) until height)) {
            val dark = (0 until width).count { ink[y * width + it] > BORDER_INK }
            if (dark > width * 0.5) for (x in 0 until width) ink[y * width + x] = 0f
        }
        for (x in (0 until min(EDGE, width)) + (max(0, width - EDGE) until width)) {
            val dark = (0 until height).count { ink[it * width + x] > BORDER_INK }
            if (dark > height * 0.5) for (y in 0 until height) ink[y * width + x] = 0f
        }
    }

    /** Connected ink smaller than a short pen stroke (dust, paper texture, the end of a border line). */
    private fun removeSpecks(ink: FloatArray, width: Int, height: Int) {
        val seen = BooleanArray(ink.size)
        val stack = IntArray(ink.size)
        val members = IntArray(ink.size)
        for (start in ink.indices) {
            if (ink[start] == 0f || seen[start]) continue
            var top = 0; var count = 0
            stack[top++] = start; seen[start] = true
            while (top > 0) {
                val p = stack[--top]
                members[count++] = p
                val x = p % width; val y = p / width
                for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val q = ny * width + nx
                    if (ink[q] > 0f && !seen[q]) { seen[q] = true; stack[top++] = q }
                }
            }
            if (count < 25) for (i in 0 until count) ink[members[i]] = 0f
        }
    }

    /**
     * MNIST strokes are 2–3 px wide at this size; a fine pen gives about one pixel. Thin strokes are widened by one
     * pixel so they look like what the model has learnt.
     */
    private fun thicken(patch: FloatArray, w: Int, h: Int) {
        val inked = patch.count { it > 0.3f }
        val solid = patch.count { it > 0.7f }
        if (inked == 0 || solid.toFloat() / inked > 0.5f) return
        val copy = patch.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var m = copy[y * w + x]
            if (x > 0) m = max(m, copy[y * w + x - 1] * 0.8f)
            if (x < w - 1) m = max(m, copy[y * w + x + 1] * 0.8f)
            if (y > 0) m = max(m, copy[(y - 1) * w + x] * 0.8f)
            if (y < h - 1) m = max(m, copy[(y + 1) * w + x] * 0.8f)
            patch[y * w + x] = min(1f, m * 1.15f)
        }
    }
}

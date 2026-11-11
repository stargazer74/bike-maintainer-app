package de.bikemaintainer.app.domain.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the four solid corner markers of a worksheet in a photo and returns their centres in image pixels (top left,
 * top right, bottom left, bottom right), or `null` if the sheet is not completely in the picture.
 */
object MarkerFinder {
    /** Marker search runs on a copy of about this size; the centres are refined on the full image. */
    private const val SEARCH_SIZE = 1000
    private const val MAX_CANDIDATES = 20
    /**
     * Dark = below this share of the local mean. The markers print dark grey, not black; next to a bright table they
     * came out at about 0.7 of the mean, and a limit of 0.7 missed them.
     */
    private const val DARK_RATIO = 0.8
    /** Largest spread (max / min) of measured vs. predicted marker size over the four corners of a frame. */
    private const val MAX_SIZE_SPREAD = 2.0
    private const val QR_MARGIN = 10f

    internal data class Blob(val area: Int, val minX: Int, val minY: Int, val maxX: Int, val maxY: Int, val cx: Double, val cy: Double) {
        val w get() = maxX - minX + 1
        val h get() = maxY - minY + 1
    }

    fun find(image: GrayImage): List<Point>? {
        val (small, factor) = image.downscaled(SEARCH_SIZE)
        val dark = threshold(small)
        val candidates = blobs(small, dark)
            .filter { isSolidSquare(it, dark, small.width) }
            .sortedByDescending { it.area }
            .take(MAX_CANDIDATES)
        val best = bestFrame(candidates) ?: return null
        return best.map { refine(image, it, factor) }
    }

    /** Dark = clearly darker than the neighbourhood (window of about 1/12 of the image), robust to uneven light. */
    internal fun threshold(image: GrayImage): BooleanArray {
        val w = image.width
        val h = image.height
        val stride = w + 1
        val integral = LongArray(stride * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            val nextY = y + 1
            for (x in 0 until w) {
                row += image[x, y]
                integral[nextY * stride + x + 1] = integral[y * stride + x + 1] + row
            }
        }
        val r = max(8, max(w, h) / 24)
        val dark = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w, x + r + 1)
                val sum = integral[y1 * stride + x1] - integral[y0 * stride + x1] - integral[y1 * stride + x0] + integral[y0 * stride + x0]
                val mean = sum.toDouble() / ((x1 - x0) * (y1 - y0))
                dark[y * w + x] = image[x, y] < mean * DARK_RATIO
            }
        }
        return dark
    }

    /** Connected dark regions (4-neighbourhood) of at least a few pixels. */
    internal fun blobs(image: GrayImage, dark: BooleanArray): List<Blob> {
        val w = image.width
        val h = image.height
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val result = mutableListOf<Blob>()
        val minArea = max(12, (w * h) / 40000)
        for (start in 0 until w * h) {
            if (!dark[start] || seen[start]) continue
            var size = 0
            var top = 0
            stack[top++] = start
            seen[start] = true
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            var sx = 0L; var sy = 0L
            while (top > 0) {
                val p = stack[--top]
                val x = p % w
                val y = p / w
                size++
                sx += x; sy += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && dark[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[top++] = p - 1 }
                if (x < w - 1 && dark[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[top++] = p + 1 }
                if (y > 0 && dark[p - w] && !seen[p - w]) { seen[p - w] = true; stack[top++] = p - w }
                if (y < h - 1 && dark[p + w] && !seen[p + w]) { seen[p + w] = true; stack[top++] = p + w }
            }
            if (size >= minArea) result += Blob(size, minX, minY, maxX, maxY, sx.toDouble() / size, sy.toDouble() / size)
        }
        return result
    }

    /**
     * A filled square, also when photographed at an angle: roughly as wide as high, covering at least 45 % of its
     * bounding box (a square turned by 45° covers half), dark in the middle (the QR finder patterns are rings).
     */
    private fun isSolidSquare(blob: Blob, dark: BooleanArray, width: Int): Boolean {
        val aspect = blob.w.toDouble() / blob.h
        if (aspect !in 0.5..2.0 || blob.w < 5 || blob.h < 5) return false
        if (blob.area < 0.45 * blob.w * blob.h) return false
        // Dark around the centroid: a ring or a "C" shaped letter is not.
        val cx = blob.cx.toInt()
        val cy = blob.cy.toInt()
        val d = max(1, min(blob.w, blob.h) / 6)
        for (y in cy - d..cy + d) for (x in cx - d..cx + d) if (!dark[y * width + x]) return false
        return true
    }

    /**
     * The four candidates that form the page frame: one per corner of a convex quadrilateral, of similar size and
     * each as big as the perspective of the frame predicts, with no other marker-sized candidate inside. Among those,
     * the largest frame wins. Without the last two checks a dark knot in the table or the marker of another sheet next
     * to the page made a bigger frame and won – the page was then warped and no field sat where it was read.
     */
    internal fun bestFrame(candidates: List<Blob>): List<Blob>? {
        var best: List<Blob>? = null
        var bestScore = 0.0
        val n = candidates.size
        for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (d in c + 1 until n) {
            val four = listOf(candidates[a], candidates[b], candidates[c], candidates[d])
            val ordered = order(four) ?: continue
            val areas = ordered.map { it.area.toDouble() }
            if (areas.max() / areas.min() > 4.0) continue
            val corners = ordered.map { Point(it.cx, it.cy) }
            val frame = quadArea(corners)
            if (frame <= bestScore) continue
            // Page frame (between marker centres) vs one marker: about 30 × 45 cm² / 0.4 cm² ≈ 1500 on paper.
            val pageW = ScanLayout.markerCentres[1].x - ScanLayout.markerCentres[0].x
            val pageH = ScanLayout.markerCentres[2].y - ScanLayout.markerCentres[0].y
            val expected = pageW * pageH / (ScanLayout.MARKER_SIZE * ScanLayout.MARKER_SIZE)
            val ratio = frame / areas.average()
            if (ratio < expected / 3 || ratio > expected * 3) continue
            if (sizeSpread(ordered) > MAX_SIZE_SPREAD) continue
            if (otherMarkerInside(candidates, ordered)) continue
            bestScore = frame
            best = ordered
        }
        return best
    }

    /**
     * How well the four blobs fit the perspective they span: per corner the measured area over the area of an 18 pt
     * marker mapped through the frame's homography; max over min (1 = perfect). A blob that is not the page's own
     * marker bends the homography, and the predicted sizes no longer match the measured ones.
     */
    internal fun sizeSpread(ordered: List<Blob>): Double {
        val page = Homography.fromPoints(ScanLayout.markerCentres, ordered.map { Point(it.cx, it.cy) }) ?: return Double.MAX_VALUE
        val half = ScanLayout.MARKER_SIZE / 2.0
        val r = ScanLayout.markerCentres.zip(ordered).map { (c, blob) ->
            val square = listOf(page.map(c.x - half, c.y - half), page.map(c.x + half, c.y - half), page.map(c.x - half, c.y + half), page.map(c.x + half, c.y + half))
            blob.area / quadArea(square)
        }
        return r.max() / r.min()
    }

    /**
     * Whether another marker-sized solid square lies on the page spanned by [ordered] – then one of the four is not
     * the page's own marker. The QR code is left out: its solid patches look like small markers.
     */
    private fun otherMarkerInside(candidates: List<Blob>, ordered: List<Blob>): Boolean {
        val corners = ordered.map { Point(it.cx, it.cy) }
        val toPage = Homography.fromPoints(corners, ScanLayout.markerCentres) ?: return true
        val markerSized = ordered.minOf { it.area } / 2
        val qr = QR_MARGIN.let { m ->
            (ScanLayout.QR_LEFT - m).toDouble()..(ScanLayout.QR_LEFT + ScanLayout.QR_SIZE + m).toDouble() to
                (ScanLayout.QR_TOP - m).toDouble()..(ScanLayout.QR_TOP + ScanLayout.QR_SIZE + m).toDouble()
        }
        return candidates.any { blob ->
            if (blob in ordered || blob.area < markerSized || !inside(corners, Point(blob.cx, blob.cy))) return@any false
            val p = toPage.map(blob.cx, blob.cy)
            p.x !in qr.first || p.y !in qr.second
        }
    }

    /** Whether [p] lies inside the convex quadrilateral tl, tr, bl, br. */
    private fun inside(corners: List<Point>, p: Point): Boolean {
        val ring = listOf(corners[0], corners[1], corners[3], corners[2])
        var sign = 0
        for (i in 0 until 4) {
            val a = ring[i]; val b = ring[(i + 1) % 4]
            val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Top left, top right, bottom left, bottom right – or `null` if the four do not form a convex quadrilateral. */
    private fun order(four: List<Blob>): List<Blob>? {
        val tl = four.minBy { it.cx + it.cy }
        val br = four.maxBy { it.cx + it.cy }
        val tr = four.maxBy { it.cx - it.cy }
        val bl = four.minBy { it.cx - it.cy }
        val ordered = listOf(tl, tr, bl, br)
        if (ordered.toSet().size != 4) return null
        val p = listOf(tl, tr, br, bl).map { Point(it.cx, it.cy) }
        var sign = 0
        for (i in 0 until 4) {
            val o = p[i]; val q = p[(i + 1) % 4]; val r = p[(i + 2) % 4]
            val ax = q.x - o.x
            val ay = q.y - o.y
            val bx = r.x - q.x
            val by = r.y - q.y
            val cross = ax * by - ay * bx
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return null
        }
        return ordered
    }

    /** Area of the quadrilateral tl, tr, bl, br (shoelace over tl → tr → br → bl). */
    private fun quadArea(p: List<Point>): Double {
        val ring = listOf(p[0], p[1], p[3], p[2])
        var sum = 0.0
        for (i in 0 until 4) {
            val a = ring[i]; val b = ring[(i + 1) % 4]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2
    }

    /** Centroid of the dark pixels around the candidate on the full-size image (sub-pixel precise). */
    private fun refine(image: GrayImage, blob: Blob, factor: Int): Point {
        val margin = max(blob.w, blob.h) / 2 + 1
        val x0 = max(0, (blob.minX - margin) * factor)
        val y0 = max(0, (blob.minY - margin) * factor)
        val x1 = min(image.width - 1, (blob.maxX + margin + 1) * factor)
        val y1 = min(image.height - 1, (blob.maxY + margin + 1) * factor)
        var lo = 255; var hi = 0
        for (y in y0..y1) for (x in x0..x1) {
            val v = image[x, y]
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val mid = (lo + hi) / 2
        var sx = 0.0; var sy = 0.0; var weight = 0.0
        for (y in y0..y1) for (x in x0..x1) {
            if (image[x, y] < mid) {
                sx += x; sy += y; weight += 1.0
            }
        }
        // Coordinates are pixel indices (as GrayImage.sample uses them); a pixel of the small copy covers `factor` pixels.
        return if (weight == 0.0) {
            Point((blob.cx + 0.5) * factor - 0.5, (blob.cy + 0.5) * factor - 0.5)
        } else {
            Point(sx / weight, sy / weight)
        }
    }
}

package de.bikemaintainer.app.domain.scan

import java.io.File
import kotlin.math.exp
import kotlin.math.roundToInt
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Diagnostics for a real photo, not part of the normal test run. Runs only with
 * `BM_SCAN_PHOTO=<jpg> BM_SCAN_QR=<qr text> ./gradlew testDebugUnitTest --tests '*ScanDiagnosticsTest*'`
 * and writes, per digit box, the box as photographed, the ink map and the 28×28 model input (side by side, enlarged)
 * plus the top-3 guesses to `BM_SCAN_OUT` (default `build/scan-debug`).
 */
class ScanDiagnosticsTest {
    @Test
    fun diagnose() {
        val photoPath = System.getenv("BM_SCAN_PHOTO")
        assumeTrue("set BM_SCAN_PHOTO to run", !photoPath.isNullOrBlank())
        val qr = System.getenv("BM_SCAN_QR") ?: error("set BM_SCAN_QR")
        val out = File(System.getenv("BM_SCAN_OUT") ?: "build/scan-debug").apply { mkdirs() }
        val code = SheetCode.parse(qr) ?: error("bad QR text $qr")
        val classifier = classifier()
        val photo = loadGray(File(photoPath))
        val markers = MarkerFinder.find(photo) ?: error("no markers")
        val page = Homography.fromPoints(ScanLayout.markerCentres, markers) ?: error("no homography")
        // Optional extra offset in points ("dx,dy") on top of the frame alignment, to probe its tolerance.
        val (sx, sy) = (System.getenv("BM_SCAN_SHIFT") ?: "0,0").split(",").map { it.trim().toFloat() }
        val fields = ScanLayout.pages(code.template, code.cylinders, code.valvesPerCylinder)[code.page - 1]
        val report = StringBuilder("photo ${photo.width}x${photo.height}, markers $markers\n")
        for (field in fields.filter { it.kind == ScanLayout.FieldKind.DIGITS }) {
            val (ax, ay) = SheetReader.frameOffset(photo, page, field)
            val crop = SheetReader.rectify(photo, page, field.left + ax - 4f, field.top + ay - 4f, field.width + 8f, field.height + 8f, 3f)
            val paper = SheetReader.paperLevel(crop)
            report.append("${field.id} (paper $paper, frame offset $ax/$ay pt):")
            field.boxLefts().forEachIndexed { i, left ->
                val inner = SheetReader.rectify(photo, page, left + ax + 1.5f + sx, field.top + ay + 1.5f + sy, ScanLayout.DIGIT_WIDTH - 3f, ScanLayout.DIGIT_HEIGHT - 3f, 4f)
                val ink = SheetReader.inkMap(inner, paper)
                val input = DigitBox.normalise(ink, inner.width, inner.height)
                val guess = input?.let { top3(classifier.logits(it)) } ?: "empty"
                report.append("  [$i] $guess")
                writeStrip(File(out, "${field.id}-$i.png"), inner, ink, input)
            }
            report.append('\n')
        }
        File(out, "report.txt").writeText(report.toString())
        println(report)
    }

    private fun top3(logits: FloatArray): String {
        val max = logits.max()
        val e = logits.map { exp((it - max).toDouble()) }
        val sum = e.sum()
        return e.indices.sortedByDescending { e[it] }.take(3).joinToString("/") { "$it:${(e[it] / sum * 100).roundToInt()}%" }
    }

    /** box | ink | model input, each scaled to 112 px high, white = paper. */
    private fun writeStrip(file: File, box: GrayImage, ink: FloatArray, input: FloatArray?) {
        val h = 112
        val panels = listOf(
            Triple(box.width, box.height) { x: Int, y: Int -> box[x, y] },
            Triple(box.width, box.height) { x: Int, y: Int -> 255 - (ink[y * box.width + x] * 255).roundToInt() },
            Triple(28, 28) { x: Int, y: Int -> 255 - ((input?.get(y * 28 + x) ?: 0f) * 255).roundToInt() },
        )
        val widths = panels.map { (w, ph, _) -> w * h / ph }
        val total = widths.sum() + 8 * (panels.size - 1)
        val argb = IntArray(total * h) { 0xff808080.toInt() }
        var x0 = 0
        panels.forEachIndexed { p, (w, ph, value) ->
            for (y in 0 until h) for (x in 0 until widths[p]) {
                val v = value(x * w / widths[p], y * ph / h).coerceIn(0, 255)
                argb[y * total + x0 + x] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
            }
            x0 += widths[p] + 8
        }
        val imageClass = Class.forName("java.awt.image.BufferedImage")
        val image = imageClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(total, h, 1) // TYPE_INT_RGB
        imageClass.getMethod(
            "setRGB",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, total, h, argb, 0, total)
        Class.forName("javax.imageio.ImageIO")
            .getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, File::class.java)
            .invoke(null, image, "png", file)
    }
}

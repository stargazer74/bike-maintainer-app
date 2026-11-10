package de.bikemaintainer.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import de.bikemaintainer.app.domain.scan.DigitClassifier
import de.bikemaintainer.app.domain.scan.GrayImage
import de.bikemaintainer.app.domain.scan.QrReader
import de.bikemaintainer.app.domain.scan.ScanOutcome
import de.bikemaintainer.app.domain.scan.SheetReader
import de.bikemaintainer.app.ui.scan.WorksheetScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import zxingcpp.BarcodeReader

/**
 * QR codes via zxing-cpp (Apache 2.0, native, maintained, works offline, no telemetry). Gets the straightened QR area,
 * so the code is upright and fills the image.
 */
class ZxingQrReader : QrReader {
    private val reader = BarcodeReader().apply {
        options.formats = setOf(BarcodeReader.Format.QR_CODE)
        options.tryHarder = true
        options.tryRotate = true
    }

    override fun read(image: GrayImage): String? {
        val argb = IntArray(image.width * image.height) { i ->
            val v = image.pixels[i].toInt() and 0xff
            (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bitmap = Bitmap.createBitmap(argb, image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            return reader.read(bitmap, Rect(0, 0, image.width, image.height), 0).firstNotNullOfOrNull { it.text }
        } finally {
            bitmap.recycle()
        }
    }
}

/** The real scanner: digit model from the APK's assets, loaded on first use; reading runs on a background thread. */
class AndroidWorksheetScanner(private val context: Context) : WorksheetScanner {
    private val reader by lazy {
        val classifier = context.assets.open("digits.bin").use(DigitClassifier::load)
        SheetReader(classifier, ZxingQrReader())
    }

    override suspend fun read(photo: GrayImage): ScanOutcome = withContext(Dispatchers.Default) { reader.read(photo) }
}

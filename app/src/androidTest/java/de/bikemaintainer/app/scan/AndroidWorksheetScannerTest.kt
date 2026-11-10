package de.bikemaintainer.app.scan

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.scan.GrayImage
import de.bikemaintainer.app.domain.scan.ScanOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole chain on a device: the native QR reader (zxing-cpp, not available in JVM unit tests) and the digit model
 * from the APK's assets, on a sample photo taken at an angle (same file as in the unit tests).
 */
@RunWith(AndroidJUnit4::class)
class AndroidWorksheetScannerTest {
    @Test
    fun readsTheQrCodeAndTheValuesOfASamplePhoto() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.context.assets.open("tires-oblique.jpg").use(BitmapFactory::decodeStream)
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val photo = GrayImage.fromArgb(bitmap.width, bitmap.height, argb)

        val outcome = AndroidWorksheetScanner(instrumentation.targetContext).read(photo)

        assertTrue("$outcome", outcome is ScanOutcome.Read)
        val sheet = (outcome as ScanOutcome.Read).sheet
        assertEquals(MeasurementTemplate.TIRES, sheet.code.template)
        assertEquals(42L, sheet.code.vehicleId)
        assertEquals(6, sheet.readings.size)
    }
}

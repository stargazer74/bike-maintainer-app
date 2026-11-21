package de.bikemaintainer.app.scan

import android.content.Context
import de.bikemaintainer.app.domain.odometer.OdoDetector
import de.bikemaintainer.app.domain.odometer.OdoLine
import de.bikemaintainer.app.domain.odometer.OdoReader
import de.bikemaintainer.app.domain.odometer.Point
import de.bikemaintainer.app.domain.odometer.image
import de.bikemaintainer.app.domain.scan.GrayImage
import de.bikemaintainer.app.ui.odometer.OdoScan
import de.bikemaintainer.app.ui.odometer.OdometerScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The real odometer scanner: both models from the APK's assets, loaded on first use; runs on a background thread. */
class AndroidOdometerScanner(private val context: Context) : OdometerScanner {
    private val detector by lazy { context.assets.open("odo-detector.bin").use(OdoDetector::load) }
    private val reader by lazy { context.assets.open("odo-reader.bin").use(OdoReader::load) }

    override suspend fun detect(photo: GrayImage): OdoLine? = withContext(Dispatchers.Default) { detector.detect(photo) }

    override suspend fun read(photo: GrayImage, corners: List<Point>, last: Int?): OdoScan = withContext(Dispatchers.Default) {
        OdoScan(reader.read(photo, corners, last), OdoReader.crop(photo, corners)?.image())
    }
}

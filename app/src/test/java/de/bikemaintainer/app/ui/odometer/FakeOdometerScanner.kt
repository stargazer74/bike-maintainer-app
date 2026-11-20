package de.bikemaintainer.app.ui.odometer

import de.bikemaintainer.app.domain.odometer.OdoLine
import de.bikemaintainer.app.domain.odometer.OdoReading
import de.bikemaintainer.app.domain.odometer.Point
import de.bikemaintainer.app.domain.scan.GrayImage

/** Finds [line] (null = none) and reads [reading]; remembers what it was asked. */
class FakeOdometerScanner(
    var line: OdoLine? = OdoLine(listOf(Point(100.0, 120.0), Point(300.0, 120.0), Point(300.0, 160.0), Point(100.0, 160.0)), 0.9),
    var reading: OdoReading = OdoReading(12400, false),
    var failure: Exception? = null,
) : OdometerScanner {
    val photos = mutableListOf<GrayImage>()
    val reads = mutableListOf<Pair<List<Point>, Int?>>()

    override suspend fun detect(photo: GrayImage): OdoLine? {
        failure?.let { throw it }
        photos += photo
        return line
    }

    override suspend fun read(photo: GrayImage, corners: List<Point>, last: Int?): OdoScan {
        failure?.let { throw it }
        reads += corners to last
        return OdoScan(reading, GrayImage(60, 32))
    }
}

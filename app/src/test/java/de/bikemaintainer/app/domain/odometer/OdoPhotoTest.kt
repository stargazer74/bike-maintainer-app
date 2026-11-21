package de.bikemaintainer.app.domain.odometer

import de.bikemaintainer.app.domain.scan.GrayImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OdoPhotoTest {
    /** Each pixel's value is its row, so the rows of the cut-out show where it comes from. */
    private fun rows(w: Int, h: Int) = GrayImage(w, h, ByteArray(w * h) { (it / w).toByte() })

    @Test
    fun aPortraitPhotoGivesTheLandscapeBandInTheMiddle() {
        val area = odoPhotoArea(rows(120, 200))
        assertEquals(120, area.width)
        assertEquals(90, area.height)
        assertEquals(55, area[0, 0])
        assertEquals(144, area[119, 89])
    }

    @Test
    fun aWideLandscapePhotoLosesItsSides() {
        val photo = GrayImage(200, 90, ByteArray(200 * 90) { (it % 200).toByte() })
        val area = odoPhotoArea(photo)
        assertEquals(120, area.width)
        assertEquals(90, area.height)
        assertEquals(40, area[0, 0])
        assertEquals(159, area[119, 0])
    }

    @Test
    fun aPhotoIn4To3StaysAsItIs() {
        val photo = rows(400, 300)
        assertSame(photo, odoPhotoArea(photo))
    }

    @Test
    fun theStraightenedLineBecomesAPictureOfItsOwnColumns() {
        val pixels = FloatArray(OdoReader.H * OdoReader.MAX_W) { (it % OdoReader.MAX_W).toFloat() }
        val image = OdoReader.Crop(pixels, 50).image()
        assertEquals(50, image.width)
        assertEquals(OdoReader.H, image.height)
        assertEquals(49, image[49, 31])
    }
}

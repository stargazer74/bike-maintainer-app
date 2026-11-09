package de.bikemaintainer.app

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityIntentTest {
    private fun intent(flags: Int = 0) = Intent().putExtra(MainActivity.EXTRA_VEHICLE_ID, 5L).addFlags(flags)

    @Test fun readsTheVehicleAndRemovesTheExtra() {
        val intent = intent()
        assertEquals(5L, MainActivity.readVehicleExtra(intent))
        assertFalse(intent.hasExtra(MainActivity.EXTRA_VEHICLE_ID))
        assertNull(MainActivity.readVehicleExtra(intent))
    }

    @Test fun ignoresRelaunchFromRecents() {
        val intent = intent(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(MainActivity.readVehicleExtra(intent))
        assertFalse(intent.hasExtra(MainActivity.EXTRA_VEHICLE_ID))
    }

    @Test fun noIntentOrExtraIsNull() {
        assertNull(MainActivity.readVehicleExtra(null))
        assertNull(MainActivity.readVehicleExtra(Intent()))
    }
}

package de.bikemaintainer.app.ui.common

import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadErrorsTest {
    @Test fun errorsMapToMessages() {
        assertEquals(R.string.error_no_connection, AppError.Network.loadMessageRes())
        assertEquals(R.string.error_server_not_responding, AppError.Http(503, null).loadMessageRes())
        assertEquals(R.string.error_server_not_responding, AppError.Http(-1, null).loadMessageRes())
        assertEquals(R.string.error_cleartext_not_allowed, AppError.CleartextNotAllowed.loadMessageRes())
        assertEquals(R.string.error_load_failed, AppError.NotFound.loadMessageRes()) // list: no single vehicle
        assertEquals(R.string.error_load_failed, AppError.Unauthorized.loadMessageRes())
        assertEquals(R.string.error_load_failed, AppError.Http(400, null).loadMessageRes())
    }

    @Test fun vehicleMessagesNameTheMissingVehicle() {
        assertEquals(R.string.error_vehicle_not_found, AppError.NotFound.vehicleLoadMessageRes())
        assertEquals(R.string.error_no_connection, AppError.Network.vehicleLoadMessageRes())
        assertEquals(R.string.error_load_failed, AppError.Unauthorized.vehicleLoadMessageRes())
    }

    @Test fun detectsNotFound() {
        assertTrue(AppError.NotFound.isNotFound())
        assertFalse(AppError.Network.isNotFound())
    }
}

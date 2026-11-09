package de.bikemaintainer.app.ui.navigation

import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.ServerMode
import org.junit.Assert.assertEquals
import org.junit.Test

class StartDestinationTest {
    private val url = "https://x/"
    private val empty = StoredSettings(serverUrl = null, mode = null, encryptedDeviceToken = null, profileId = null)

    @Test fun startDestinationWithoutServerIsSetup() {
        assertEquals(SetupRoute, startDestination(empty))
    }

    @Test fun startDestinationPortalWithoutTokenIsLogin() {
        val settings = empty.copy(serverUrl = url, mode = ServerMode.PORTAL)
        assertEquals(LoginRoute(url), startDestination(settings))
    }

    @Test fun startDestinationHouseholdWithoutProfileIsProfiles() {
        val settings = empty.copy(serverUrl = url, mode = ServerMode.HOUSEHOLD)
        assertEquals(ProfilesRoute(url), startDestination(settings))
    }

    @Test fun startDestinationWithSessionIsVehicles() {
        val portal = empty.copy(serverUrl = url, mode = ServerMode.PORTAL, encryptedDeviceToken = "t")
        val household = empty.copy(serverUrl = url, mode = ServerMode.HOUSEHOLD, profileId = 3)
        assertEquals(VehiclesRoute, startDestination(portal))
        assertEquals(VehiclesRoute, startDestination(household))
    }

    @Test fun startDestinationWithoutModeIsSetup() {
        assertEquals(SetupRoute, startDestination(empty.copy(serverUrl = url)))
    }
}

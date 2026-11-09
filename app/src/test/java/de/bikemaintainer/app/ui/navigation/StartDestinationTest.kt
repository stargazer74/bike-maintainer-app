package de.bikemaintainer.app.ui.navigation

import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.ServerMode
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StartDestinationTest {
    private val url = "https://x/"
    private val empty = StoredSettings(serverUrl = null, mode = null, encryptedDeviceToken = null, profileId = null)

    @Test fun startDestinationWithoutServerIsSetup() {
        assertEquals(SetupRoute, startDestination(empty, OLD_SDK))
    }

    @Test fun startDestinationPortalWithoutTokenIsLogin() {
        val settings = empty.copy(serverUrl = url, mode = ServerMode.PORTAL)
        assertEquals(LoginRoute(url), startDestination(settings, OLD_SDK))
    }

    @Test fun startDestinationHouseholdWithoutProfileIsProfiles() {
        val settings = empty.copy(serverUrl = url, mode = ServerMode.HOUSEHOLD)
        assertEquals(ProfilesRoute(url), startDestination(settings, OLD_SDK))
    }

    @Test fun startDestinationWithSessionIsVehicles() {
        val portal = empty.copy(serverUrl = url, mode = ServerMode.PORTAL, encryptedDeviceToken = "t")
        val household = empty.copy(serverUrl = url, mode = ServerMode.HOUSEHOLD, profileId = 3)
        assertEquals(VehiclesRoute, startDestination(portal, OLD_SDK))
        assertEquals(VehiclesRoute, startDestination(household, OLD_SDK))
    }

    @Test fun startDestinationWithoutModeIsSetup() {
        assertEquals(SetupRoute, startDestination(empty.copy(serverUrl = url), OLD_SDK))
    }

    @Test fun signedInWithoutNotificationQuestionStartsThere() {
        val portal = empty.copy(serverUrl = url, mode = ServerMode.PORTAL, encryptedDeviceToken = "t")
        val household = empty.copy(serverUrl = url, mode = ServerMode.HOUSEHOLD, profileId = 3)
        assertEquals(NotificationPermissionRoute, startDestination(portal, NEW_SDK))
        assertEquals(NotificationPermissionRoute, startDestination(household, NEW_SDK))
        assertEquals(VehiclesRoute, startDestination(portal.copy(notificationsAsked = true), NEW_SDK))
        assertEquals(VehiclesRoute, startDestination(portal, OLD_SDK))
    }

    @Test fun notificationQuestionIsNotAskedBeforeSignIn() {
        assertEquals(LoginRoute(url), startDestination(empty.copy(serverUrl = url, mode = ServerMode.PORTAL), NEW_SDK))
        assertEquals(SetupRoute, startDestination(empty, NEW_SDK))
    }

    @Test fun unreadableSettingsStartAtSetup() {
        assertEquals(SetupRoute, runBlocking { readStartDestination { throw IOException("corrupt") } })
        val portal = empty.copy(serverUrl = url, mode = ServerMode.PORTAL)
        assertEquals(LoginRoute(url), runBlocking { readStartDestination { portal } })
    }

    private companion object {
        const val OLD_SDK = 32
        const val NEW_SDK = 33
    }
}

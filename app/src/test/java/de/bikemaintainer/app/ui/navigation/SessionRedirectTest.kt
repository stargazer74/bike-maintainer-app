package de.bikemaintainer.app.ui.navigation

import de.bikemaintainer.app.data.network.SessionStatus
import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.ServerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRedirectTest {
    private val withServer = StoredSettings("https://h", ServerMode.PORTAL, "tok", null)

    @Test fun loginRequiredNavigatesToLogin() {
        assertEquals(
            LoginRoute("https://h", relogin = true),
            sessionRedirect(SessionStatus.LOGIN_REQUIRED, withServer),
        )
    }

    @Test fun profileSelectionRequiredNavigatesToProfiles() {
        assertEquals(
            ProfilesRoute("https://h"),
            sessionRedirect(SessionStatus.PROFILE_SELECTION_REQUIRED, withServer),
        )
    }

    @Test fun activeStatusDoesNotRedirect() {
        assertNull(sessionRedirect(SessionStatus.ACTIVE, withServer))
    }

    @Test fun withoutServerThereIsNoRedirect() {
        val none = StoredSettings(null, null, null, null)
        assertNull(sessionRedirect(SessionStatus.LOGIN_REQUIRED, none))
    }
}

package de.bikemaintainer.app.ui.common

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class WebLinkOpenerTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun openingLinkWithoutBrowserDoesNotCrash() {
        val app = shadowOf(context as android.app.Application)
        app.checkActivities(true) // startActivity throws ActivityNotFoundException without a resolvable activity
        assertFalse(openWebLink(context, "https://h"))
        assertTrue(ShadowToast.showedToast(context.getString(de.bikemaintainer.app.R.string.no_browser_found)))
    }

    @Test fun securityExceptionWhenOpeningLinkDoesNotCrash() {
        val refusing = object : android.content.ContextWrapper(context) {
            override fun startActivity(intent: Intent?) = throw SecurityException("not exported")
        }
        assertFalse(openWebLink(refusing, "https://h"))
        assertTrue(ShadowToast.showedToast(context.getString(de.bikemaintainer.app.R.string.no_browser_found)))
    }

    @Test fun openingLinkStartsViewIntent() {
        assertTrue(openWebLink(context, "https://h"))
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://h", started.data.toString())
    }
}

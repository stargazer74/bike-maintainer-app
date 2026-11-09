package de.bikemaintainer.app.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.bikemaintainer.app.MainActivity
import de.bikemaintainer.app.domain.DueTask
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.VehicleDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de")
class DueTaskNotifierTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val due = VehicleDue(
        3,
        "Africa Twin",
        listOf(
            DueTask(1, "Ölwechsel", TaskStatus.OVERDUE),
            DueTask(2, "Kette schmieren", TaskStatus.DUE_SOON),
        ),
    )

    @Before fun grant() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted() = shadowOf(manager).allNotifications
    private fun entry(tag: String, id: Int) =
        shadowOf(manager).getNotification(tag, id)

    @Test fun channelIsCreated() {
        DueTaskNotifier(app)
        val channel = manager.getNotificationChannel("due_maintenance")
        assertNotNull(channel)
        assertEquals("Fällige Wartungen", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test fun oneNotificationPerVehicleWithTaskList() {
        DueTaskNotifier(app).showDue(due)
        assertEquals(1, posted().size)
        val n = entry("vehicle", 3)
        assertNotNull(n)
        assertEquals("Africa Twin", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(
            "Ölwechsel überfällig, Kette schmieren bald fällig",
            n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString(),
        )
        assertEquals("due_maintenance", n.channelId)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test fun lockScreenShowsOnlyGenericText() {
        DueTaskNotifier(app).showDue(due)
        val n = entry("vehicle", 3)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion
        assertNotNull(public)
        assertEquals("Wartung fällig", public.extras.getString(Notification.EXTRA_TITLE))
        val text = public.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertFalse(text.contains("Africa"))
        assertFalse(text.contains("Ölwechsel"))
    }

    @Test fun tapOpensVehicle() {
        DueTaskNotifier(app).showDue(due)
        val intent = shadowOf(entry("vehicle", 3).contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(3L, intent.getLongExtra(MainActivity.EXTRA_VEHICLE_ID, -1))
    }

    @Test fun reloginNotification() {
        DueTaskNotifier(app).showRelogin()
        val n = entry("relogin", 0)
        assertNotNull(n)
        assertEquals("Bitte erneut anmelden, sonst keine Erinnerungen.", n.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("due_maintenance", n.channelId)
    }

    @Test fun cancelAllRemovesEverything() {
        val notifier = DueTaskNotifier(app)
        notifier.showDue(due)
        notifier.showRelogin()
        assertEquals(2, posted().size)
        notifier.cancelAll()
        assertTrue(posted().isEmpty())
    }

    @Test fun noPermissionPostsNothing() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)
        val notifier = DueTaskNotifier(app)
        notifier.showDue(due)
        notifier.showRelogin()
        assertTrue(posted().isEmpty())
    }
}

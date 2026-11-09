package de.bikemaintainer.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.bikemaintainer.app.MainActivity
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.VehicleDue

/** What the background job can show; faked in tests. */
interface DueNotifications {
    fun showDue(due: VehicleDue)
    fun showRelogin()
    fun cancelAll()
}

class DueTaskNotifier(private val context: Context) : DueNotifications {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_due),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notif_channel_due_desc) }
        manager.createNotificationChannel(channel)
    }

    override fun showDue(due: VehicleDue) {
        val text = due.tasks.joinToString(", ") {
            val format = if (it.status == TaskStatus.OVERDUE) R.string.notif_task_overdue else R.string.notif_task_due_soon
            context.getString(format, it.name)
        }
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_public_title))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(due.vehicleName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(openApp(due.vehicleId.toInt()) { putExtra(MainActivity.EXTRA_VEHICLE_ID, due.vehicleId) })
            .setAutoCancel(true)
            .build()
        notify(TAG_VEHICLE, due.vehicleId.toInt(), notification)
    }

    override fun showRelogin() {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_relogin_title))
            .setContentText(context.getString(R.string.notif_relogin_text))
            .setContentIntent(openApp(0) {})
            .setAutoCancel(true)
            .build()
        notify(TAG_RELOGIN, 0, notification)
    }

    override fun cancelAll() = manager.cancelAll()

    private fun openApp(requestCode: Int, extras: Intent.() -> Unit): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply(extras)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notify(tag: String, id: Int, notification: Notification) {
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(tag, id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call: nothing to show.
        }
    }

    private companion object {
        const val CHANNEL_ID = "due_maintenance"
        const val TAG_VEHICLE = "vehicle"
        const val TAG_RELOGIN = "relogin"
    }
}

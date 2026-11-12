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
    fun cancelRelogin()

    /** Removes the notification of one vehicle (nothing due there any more). */
    fun cancelVehicle(vehicleId: Long)

    /** Removes the notifications of every vehicle not in [vehicleIds], i.e. vehicles deleted meanwhile. */
    fun cancelVehiclesExcept(vehicleIds: Set<Long>)
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
        val all = due.all
        val named = all.take(MAX_NAMED).joinToString(", ") {
            val format = if (it.status == TaskStatus.OVERDUE) R.string.notif_task_overdue else R.string.notif_task_due_soon
            context.getString(format, it.name)
        }
        val rest = all.size - MAX_NAMED
        // Android cuts long texts off without a hint: name the most important ones and count the rest.
        val text = if (rest > 0) context.resources.getQuantityString(R.plurals.notif_more, rest, named, rest) else named
        val shortText =
            if (rest > 0) context.resources.getQuantityString(R.plurals.notif_count, all.size, all.size) else text
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_public_title))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(due.vehicleName)
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
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

    override fun cancelRelogin() = manager.cancel(TAG_RELOGIN, 0)

    override fun cancelVehicle(vehicleId: Long) = manager.cancel(TAG_VEHICLE, vehicleId.toInt())

    override fun cancelVehiclesExcept(vehicleIds: Set<Long>) {
        val keep = vehicleIds.map { it.toInt() }.toSet()
        manager.activeNotifications
            .filter { it.tag == TAG_VEHICLE && it.id !in keep }
            .forEach { manager.cancel(TAG_VEHICLE, it.id) }
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

    internal companion object {
        const val CHANNEL_ID = "due_maintenance"
        const val TAG_VEHICLE = "vehicle"
        const val TAG_RELOGIN = "relogin"

        /** Tasks named in a vehicle notification; further ones are only counted. */
        const val MAX_NAMED = 5
    }
}

/**
 * Whether reminders can be shown: notifications are on for the app and the reminder channel was not switched off
 * in the system settings (a missing channel is created on first use, so it counts as allowed).
 */
fun notificationsAllowed(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    val channel = NotificationManagerCompat.from(context).getNotificationChannel(DueTaskNotifier.CHANNEL_ID)
    return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
}

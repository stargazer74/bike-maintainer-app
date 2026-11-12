package de.bikemaintainer.app.ui.common

import androidx.annotation.StringRes
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.isSubscriptionRequired

/**
 * User-facing message for a failed load. [notFound] is the message for a 404: in a list just a failed load, for one
 * vehicle or task the message that it was deleted.
 */
@StringRes
fun AppError.loadMessageRes(@StringRes notFound: Int = R.string.error_load_failed): Int =
    when {
        this == AppError.Network -> R.string.error_no_connection
        this is AppError.Http && (status >= 500 || status == -1) -> R.string.error_server_not_responding
        this == AppError.CleartextNotAllowed -> R.string.error_cleartext_not_allowed
        this == AppError.NotFound -> notFound
        else -> R.string.error_load_failed
    }

/** User-facing message for a failed load of one vehicle (detail): NotFound means the vehicle was deleted. */
@StringRes
fun AppError.vehicleLoadMessageRes(): Int = loadMessageRes(notFound = R.string.error_vehicle_not_found)

fun AppError.isNotFound(): Boolean = this == AppError.NotFound

/**
 * User-facing message for a failed save (POST/PUT); Unauthorized is handled by the session redirect. [notFound] is
 * the message for a 404 (the vehicle, or the task that was edited, is gone).
 */
@StringRes
fun AppError.saveMessageRes(@StringRes notFound: Int = R.string.error_vehicle_not_found): Int =
    when {
        this == AppError.Network -> R.string.error_no_connection
        this is AppError.Http && status >= 500 -> R.string.error_server_not_responding
        this == AppError.CleartextNotAllowed -> R.string.error_cleartext_not_allowed
        this == AppError.NotFound -> notFound
        isSubscriptionRequired() -> R.string.error_read_only
        this is AppError.Http && status == 400 -> R.string.error_input_rejected
        else -> R.string.error_save_failed
    }

package de.bikemaintainer.app.ui.common

import androidx.annotation.StringRes
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.isSubscriptionRequired

/** User-facing message for a failed load of the vehicle list; NotFound there is just a failed load. */
@StringRes
fun AppError.loadMessageRes(): Int =
    when {
        this == AppError.Network -> R.string.error_no_connection
        this is AppError.Http && (status >= 500 || status == -1) -> R.string.error_server_not_responding
        this == AppError.CleartextNotAllowed -> R.string.error_cleartext_not_allowed
        else -> R.string.error_load_failed
    }

/** User-facing message for a failed load of one vehicle (detail): NotFound means the vehicle was deleted. */
@StringRes
fun AppError.vehicleLoadMessageRes(): Int =
    if (this == AppError.NotFound) R.string.error_vehicle_not_found else loadMessageRes()

fun AppError.isNotFound(): Boolean = this == AppError.NotFound

/** User-facing message for a failed save (POST/PUT); Unauthorized is handled by the session redirect. */
@StringRes
fun AppError.saveMessageRes(): Int =
    when {
        this == AppError.Network -> R.string.error_no_connection
        this is AppError.Http && status >= 500 -> R.string.error_server_not_responding
        this == AppError.CleartextNotAllowed -> R.string.error_cleartext_not_allowed
        this == AppError.NotFound -> R.string.error_vehicle_not_found
        isSubscriptionRequired() -> R.string.error_read_only
        this is AppError.Http && status == 400 -> R.string.error_input_rejected
        else -> R.string.error_save_failed
    }

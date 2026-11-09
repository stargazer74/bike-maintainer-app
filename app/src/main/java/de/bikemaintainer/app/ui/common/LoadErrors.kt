package de.bikemaintainer.app.ui.common

import androidx.annotation.StringRes
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.AppError

/** User-facing message for a failed load (vehicles, tasks). */
@StringRes
fun AppError.loadMessageRes(): Int =
    when {
        this == AppError.Network -> R.string.error_no_connection
        this is AppError.Http && (status >= 500 || status == -1) -> R.string.error_server_not_responding
        this == AppError.CleartextNotAllowed -> R.string.error_cleartext_not_allowed
        this == AppError.NotFound -> R.string.error_vehicle_not_found
        else -> R.string.error_load_failed
    }

fun AppError.isNotFound(): Boolean = this == AppError.NotFound

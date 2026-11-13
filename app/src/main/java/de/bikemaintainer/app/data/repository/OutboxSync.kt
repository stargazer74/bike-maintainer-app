package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.MileageRequest
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.data.storage.Outbox
import de.bikemaintainer.app.data.storage.OutboxEntry
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.PendingProblem
import java.util.UUID

enum class SyncResult {
    /** Everything that could be sent is sent (entries waiting for the user stay). */
    DONE,

    /** Interrupted (no connection, server trouble): try again later. */
    RETRY,

    /** Not signed in any more: the next login starts it again. */
    STOP,
}

/**
 * Sends the [outbox] in the order it was recorded (app#65). Safe to repeat at any point:
 * - a mileage only raises (`PUT …/mileage`, same value → unchanged, lower → `MILEAGE_BELOW_CURRENT`);
 * - a log carries its `clientEntryId`; one the server already has comes back instead of a second one.
 *
 * Before a log is sent the first time, the vehicle's logs are checked for one on the same day with the same tasks
 * (recorded twice, e.g. once in the web app): such a log waits for the user's decision instead of being sent.
 * What cannot be sent (vehicle or task deleted, read-only account) is marked and waits to be discarded – nothing is
 * dropped silently.
 */
class OutboxSync(private val provider: ApiProvider, private val outbox: Outbox) {
    private sealed interface Step {
        data object Sent : Step

        data object Retry : Step

        data object Stop : Step

        data class Problem(val problem: PendingProblem) : Step

        data class Duplicate(val date: String) : Step

        /** The log is saved, the mileage raise is still to do: the entry continues as a mileage. */
        data class MileageLeft(val mileage: Int) : Step
    }

    suspend fun run(): SyncResult {
        for (entry in outbox.load()) {
            if (!entry.ready) continue
            val step = if (entry.log != null) sendLog(entry) else sendMileage(entry.vehicleId, entry.mileage ?: 0)
            when (step) {
                Step.Sent -> outbox.update(entry.id) { null }
                Step.Retry -> return SyncResult.RETRY
                Step.Stop -> return SyncResult.STOP
                is Step.Problem -> outbox.update(entry.id) { it.copy(problem = step.problem.name) }
                is Step.Duplicate -> outbox.update(entry.id) { it.copy(duplicateOn = step.date) }
                is Step.MileageLeft -> {
                    outbox.update(entry.id) { it.copy(log = null, raiseMileageTo = null, mileage = step.mileage) }
                    return SyncResult.RETRY
                }
            }
        }
        return SyncResult.DONE
    }

    private suspend fun sendMileage(vehicleId: Long, mileage: Int): Step =
        when (val result = provider.callStored { it.vehicles.updateMileage(vehicleId, MileageRequest(mileage)) }) {
            is Outcome.Success -> Step.Sent
            // A 2xx whose body cannot be read: the value is set.
            is Outcome.Failure -> if (result.error == AppError.Http(-1, null)) Step.Sent else step(result.error)
        }

    private suspend fun sendLog(entry: OutboxEntry): Step {
        val clientEntryId = UUID.fromString(entry.id)
        val request = entry.log!!.copy(clientEntryId = clientEntryId)
        var alreadySaved = false
        if (!entry.confirmed) {
            val logs = when (val result = provider.callStored { it.logs.listMaintenanceLogs(entry.vehicleId) }) {
                is Outcome.Failure -> return step(result.error)
                is Outcome.Success -> result.value
            }
            // An earlier attempt reached the server, only its answer got lost.
            alreadySaved = logs.any { it.clientEntryId == clientEntryId }
            val duplicate = logs.firstOrNull {
                it.clientEntryId != clientEntryId &&
                    it.performedAt == request.performedAt &&
                    it.performedTaskIds.orEmpty().toSet() == request.performedTaskIds.orEmpty()
            }
            if (!alreadySaved && duplicate != null) return Step.Duplicate(duplicate.performedAt.toString())
        }
        if (!alreadySaved) {
            val created = provider.callStored { it.logs.createMaintenanceLog(entry.vehicleId, request) }
            if (created is Outcome.Failure && created.error != AppError.Http(-1, null)) return step(created.error)
        }
        val raise = entry.raiseMileageTo ?: return Step.Sent
        return when (sendMileage(entry.vehicleId, raise)) {
            Step.Retry, Step.Stop -> Step.MileageLeft(raise)
            // Raised, or it cannot be (the server is already higher, …): the log is in, that is what counts.
            else -> Step.Sent
        }
    }

    private fun step(error: AppError): Step = when (error) {
        AppError.Network, AppError.Storage -> Step.Retry
        AppError.Unauthorized, AppError.NotConfigured -> Step.Stop
        AppError.NotFound -> Step.Problem(PendingProblem.VEHICLE_GONE)
        AppError.CleartextNotAllowed, AppError.InvalidAddress -> Step.Retry
        is AppError.Http -> when {
            error.status == -1 || error.status == 429 || error.status >= 500 -> Step.Retry
            error.code == "MILEAGE_BELOW_CURRENT" -> Step.Problem(PendingProblem.MILEAGE_BELOW_CURRENT)
            error.code == "SUBSCRIPTION_REQUIRED" -> Step.Problem(PendingProblem.SUBSCRIPTION_REQUIRED)
            else -> Step.Problem(PendingProblem.REJECTED)
        }
    }
}

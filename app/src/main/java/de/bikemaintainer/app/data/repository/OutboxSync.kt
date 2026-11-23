package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.MaintenanceLogResponse
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.data.storage.Outbox
import de.bikemaintainer.app.data.storage.OutboxEntry
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.PendingProblem
import java.io.IOException
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

    /** A read or write error of the outbox is [SyncResult.RETRY]: nothing is lost, the next run tries again. */
    suspend fun run(): SyncResult = try {
        sendAll()
    } catch (_: IOException) {
        SyncResult.RETRY
    }

    private suspend fun sendAll(): SyncResult {
        for (entry in outbox.load()) {
            if (!entry.ready) continue
            val step = if (entry.log != null) sendLog(entry) else sendMileage(entry.vehicleId, entry.mileage ?: 0)
            // What was sent is [entry]; the user may have edited it meanwhile (app#70). Such an edit stays as it is
            // and goes out with the next run – as an update if the old version reached the server (app#96).
            when (step) {
                Step.Sent -> outbox.update(entry.id) { if (it == entry) null else it.copy(edited = true) }
                Step.Retry -> return SyncResult.RETRY
                Step.Stop -> return SyncResult.STOP
                is Step.Problem -> outbox.update(entry.id) { if (it == entry) it.copy(problem = step.problem.name) else it }
                is Step.Duplicate -> outbox.update(entry.id) { if (it == entry) it.copy(duplicateOn = step.date) else it }
                is Step.MileageLeft -> {
                    outbox.update(entry.id) {
                        if (it == entry) it.copy(log = null, raiseMileageTo = null, mileage = step.mileage) else it.copy(edited = true)
                    }
                    return SyncResult.RETRY
                }
            }
        }
        return SyncResult.DONE
    }

    private suspend fun sendMileage(vehicleId: Long, mileage: Int): Step =
        when (val result = provider.raiseMileage(vehicleId, mileage)) {
            is Outcome.Success -> Step.Sent
            // A 2xx whose body cannot be read: the value is set.
            is Outcome.Failure -> if (result.error == AppError.Http(-1, null)) Step.Sent else step(result.error)
        }

    private suspend fun sendLog(entry: OutboxEntry): Step {
        val clientEntryId = UUID.fromString(entry.id)
        val request = entry.log!!.copy(clientEntryId = clientEntryId)
        var saved: MaintenanceLogResponse? = null
        if (!entry.confirmed || entry.edited) {
            val logs = when (val result = provider.callStored { it.logs.listMaintenanceLogs(entry.vehicleId) }) {
                is Outcome.Failure -> return step(result.error)
                is Outcome.Success -> result.value
            }
            // An earlier attempt reached the server, only its answer got lost (or the entry was edited meanwhile).
            saved = logs.firstOrNull { it.clientEntryId == clientEntryId }
            val duplicate = logs.firstOrNull {
                it.clientEntryId != clientEntryId &&
                    it.performedAt == request.performedAt &&
                    it.performedTaskIds.orEmpty().toSet() == request.performedTaskIds.orEmpty()
            }
            if (saved == null && duplicate != null && !entry.confirmed) return Step.Duplicate(duplicate.performedAt.toString())
        }
        val result = when {
            saved == null -> provider.callStored { it.logs.createMaintenanceLog(entry.vehicleId, request) }
            // A repeated create would return the server's version unchanged: the edit replaces it.
            entry.edited -> provider.callStored { it.logs.updateMaintenanceLog(entry.vehicleId, saved.id, request) }
            else -> null
        }
        if (result is Outcome.Failure && result.error != AppError.Http(-1, null)) return step(result.error)
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

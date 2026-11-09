package de.bikemaintainer.app.domain

/** A task that is overdue or due soon ([status] is never OK or DONE). */
data class DueTask(val id: Long, val name: String, val status: TaskStatus)

/** The newly due tasks of one vehicle, most urgent first. */
data class VehicleDue(val vehicleId: Long, val vehicleName: String, val tasks: List<DueTask>)

/** [toNotify] is what to report now; [newState] (task id to status) replaces the stored reported state. */
data class DueUpdate(val toNotify: List<VehicleDue>, val newState: Map<Long, TaskStatus>)

private fun TaskStatus.isDue() = this == TaskStatus.OVERDUE || this == TaskStatus.DUE_SOON

/**
 * Compares the current due tasks with the [previous] reported state. A task is reported when it was not reported
 * before or became overdue after being due soon; everything still due is kept in the new state.
 */
fun dueUpdates(
    previous: Map<Long, TaskStatus>,
    current: List<Pair<Vehicle, List<MaintenanceTask>>>,
): DueUpdate {
    val newState = LinkedHashMap<Long, TaskStatus>()
    val toNotify = mutableListOf<VehicleDue>()
    for ((vehicle, tasks) in current) {
        val due = tasks.forDisplay().filter { it.status.isDue() }
        due.forEach { newState[it.id] = it.status }
        val fresh = due
            .filter {
                val old = previous[it.id]
                old == null || (old == TaskStatus.DUE_SOON && it.status == TaskStatus.OVERDUE)
            }
            .sortedBy { it.status.ordinal } // stable: keeps forDisplay() order within a status
            .map { DueTask(it.id, it.name, it.status) }
        if (fresh.isNotEmpty()) toNotify += VehicleDue(vehicle.id, vehicle.name, fresh)
    }
    return DueUpdate(toNotify, newState)
}

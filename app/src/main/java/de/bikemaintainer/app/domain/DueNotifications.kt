package de.bikemaintainer.app.domain

/** A task that is overdue or due soon ([status] is never OK or DONE). */
data class DueTask(val id: Long, val name: String, val status: TaskStatus)

/**
 * The due tasks of one vehicle: [tasks] are the newly reported ones (most urgent first, the trigger for a
 * notification), [stillDue] the other currently due tasks that were reported before. A notification lists both.
 */
data class VehicleDue(
    val vehicleId: Long,
    val vehicleName: String,
    val tasks: List<DueTask>,
    val stillDue: List<DueTask> = emptyList(),
) {
    /** Everything currently due, newly reported first. */
    val all: List<DueTask> get() = tasks + stillDue
}

/** [toNotify] is what to report now (per vehicle with at least one new task); [newState] (task id to status) replaces the stored reported state. */
data class DueUpdate(
    val toNotify: List<VehicleDue>,
    val newState: Map<Long, TaskStatus>,
    /** Vehicles without any due task now: their notification, if any, is obsolete. */
    val noneDue: List<Long> = emptyList(),
)

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
    val noneDue = mutableListOf<Long>()
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
        if (fresh.isNotEmpty()) {
            val freshIds = fresh.map { it.id }.toSet()
            val others = due
                .filter { it.id !in freshIds }
                .sortedBy { it.status.ordinal }
                .map { DueTask(it.id, it.name, it.status) }
            toNotify += VehicleDue(vehicle.id, vehicle.name, fresh, others)
        }
        if (due.isEmpty()) noneDue += vehicle.id
    }
    return DueUpdate(toNotify, newState, noneDue)
}

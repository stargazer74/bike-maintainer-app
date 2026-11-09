package de.bikemaintainer.app.domain

/** A vehicle with the overall status of its active tasks ([status] is null without active tasks). */
data class VehicleSummary(val vehicle: Vehicle, val status: TaskStatus?, val dueCount: Int)

private fun List<MaintenanceTask>.activeOnly() = filter { it.active }

/** Worst status of the active tasks; DONE counts as OK; null without active tasks. */
fun List<MaintenanceTask>.overallStatus(): TaskStatus? {
    val active = activeOnly()
    if (active.isEmpty()) return null
    return active.map { if (it.status == TaskStatus.DONE) TaskStatus.OK else it.status }.minBy { it.ordinal }
}

/** Active tasks that are overdue or due soon. */
fun List<MaintenanceTask>.dueCount(): Int =
    activeOnly().count { it.status == TaskStatus.OVERDUE || it.status == TaskStatus.DUE_SOON }

/** Active tasks, most urgent first: status, then remaining fraction (unknown last), then name. */
fun List<MaintenanceTask>.forDisplay(): List<MaintenanceTask> =
    activeOnly().sortedWith(
        compareBy<MaintenanceTask> { it.status.ordinal }
            .thenComparator { a, b ->
                val x = a.remainingFraction
                val y = b.remainingFraction
                when {
                    x == null && y == null -> 0
                    x == null -> 1
                    y == null -> -1
                    else -> x.compareTo(y)
                }
            }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
    )

package de.bikemaintainer.app.domain

/** What the task form sends; the same fields as in the web app. */
data class TaskInput(
    val name: String,
    val description: String?,
    val intervalKm: Int?,
    val intervalMonths: Int?,
    val firstDueKm: Int?,
    val firstDueMonths: Int?,
    val oneTime: Boolean,
    val active: Boolean,
)

/** A problem of the task form. */
enum class TaskField { NAME, DESCRIPTION, INTERVAL_KM, INTERVAL_MONTHS, FIRST_DUE_KM, FIRST_DUE_MONTHS, NO_INTERVAL }

/** The task form as typed; empty number fields mean "not set". */
data class TaskDraft(
    val name: String = "",
    val description: String = "",
    val intervalKm: String = "",
    val intervalMonths: String = "",
    val firstDueKm: String = "",
    val firstDueMonths: String = "",
    val oneTime: Boolean = false,
    val active: Boolean = true,
) {
    /**
     * Problems like the API checks them: intervals at least 1, first due at least 0, and a recurring task needs an
     * interval by km and/or months ("whichever comes first").
     */
    fun invalidFields(): Set<TaskField> = buildSet {
        if (name.isBlank() || name.trim().length > MAX_NAME) add(TaskField.NAME)
        if (description.trim().length > MAX_DESCRIPTION) add(TaskField.DESCRIPTION)
        // A one-time task has no interval; hidden values of before the switch do not count.
        if (!oneTime && !optional(intervalKm, 1)) add(TaskField.INTERVAL_KM)
        if (!oneTime && !optional(intervalMonths, 1)) add(TaskField.INTERVAL_MONTHS)
        if (!optional(firstDueKm, 0)) add(TaskField.FIRST_DUE_KM)
        if (!optional(firstDueMonths, 0)) add(TaskField.FIRST_DUE_MONTHS)
        if (!oneTime && intervalKm.isBlank() && intervalMonths.isBlank()) add(TaskField.NO_INTERVAL)
    }

    /** The input to save, `null` while something is invalid. A blank description is not sent. */
    fun toInput(): TaskInput? {
        if (invalidFields().isNotEmpty()) return null
        return TaskInput(
            name = name.trim(),
            description = description.trim().ifEmpty { null },
            intervalKm = if (oneTime) null else number(intervalKm),
            intervalMonths = if (oneTime) null else number(intervalMonths),
            firstDueKm = number(firstDueKm),
            firstDueMonths = number(firstDueMonths),
            oneTime = oneTime,
            active = active,
        )
    }

    companion object {
        const val MAX_NAME = 255
        const val MAX_DESCRIPTION = 1000

        fun of(task: MaintenanceTask) = TaskDraft(
            name = task.name,
            description = task.description.orEmpty(),
            intervalKm = task.intervalKm?.toString().orEmpty(),
            intervalMonths = task.intervalMonths?.toString().orEmpty(),
            firstDueKm = task.firstDueKm?.toString().orEmpty(),
            firstDueMonths = task.firstDueMonths?.toString().orEmpty(),
            oneTime = task.oneTime,
            active = task.active,
        )

        private fun number(text: String): Int? = parseMileage(text)

        /** Empty, or a whole number of at least [min]. */
        private fun optional(text: String, min: Int): Boolean =
            text.isBlank() || (parseMileage(text)?.let { it >= min } ?: false)
    }
}

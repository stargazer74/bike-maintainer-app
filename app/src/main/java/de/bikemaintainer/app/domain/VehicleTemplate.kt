package de.bikemaintainer.app.domain

import java.net.URLEncoder
import java.time.LocalDate

enum class TemplateSourceQuality { MANUFACTURER, SECONDARY }

enum class TemplateSourceKind { MANUFACTURER, DEALER, PRESS, REPAIR_PORTAL, FORUM }

/** One hit of the template search. [yearTo] null: still built. */
data class TemplateSummary(
    val id: String,
    val type: VehicleType,
    val make: String,
    val model: String,
    val yearFrom: Int,
    val yearTo: Int?,
    val taskCount: Int,
    val sourceQuality: TemplateSourceQuality,
)

data class TemplateSource(
    val id: String,
    val kind: TemplateSourceKind,
    val title: String,
    val retrieved: LocalDate,
    val url: String?,
    val locator: String?,
)

data class TemplateDetail(val label: String, val value: String, val quantity: String?, val sourceIds: List<String>)

data class TemplateTask(
    val key: String,
    val name: String,
    val description: String?,
    val oneTime: Boolean,
    val intervalKm: Int?,
    val intervalMonths: Int?,
    val firstDueKm: Int?,
    val firstDueMonths: Int?,
    val sourceIds: List<String>,
    val details: List<TemplateDetail>,
)

data class VehicleTemplate(
    val id: String,
    val type: VehicleType,
    val make: String,
    val model: String,
    val yearFrom: Int,
    val yearTo: Int?,
    val taskCount: Int,
    val sourceQuality: TemplateSourceQuality,
    val notes: String?,
    val sources: List<TemplateSource>,
    val tasks: List<TemplateTask>,
)

/** A task of a template in the apply dialog, with the "last done" values to be sent. */
data class TemplateTaskRow(
    val task: TemplateTask,
    val selected: Boolean,
    /** The vehicle already has this task from the template: never sent again. */
    val existing: Boolean,
    val lastDoneKm: Int? = null,
    val lastDoneDate: LocalDate? = null,
)

data class AppliedTask(val key: String, val lastDoneKm: Int?, val lastDoneDate: LocalDate?)

/** What `POST …/template` gets. */
data class TemplateApplication(val templateId: String, val tasks: List<AppliedTask>)

/** [skipped]: tasks the vehicle already had from the template. */
data class TemplateApplyResult(val created: Int, val skipped: Int)

/** Search parameters for the template search; see [templateSearchParams]. */
data class TemplateSearchParams(val make: String, val type: VehicleType, val model: String, val modelYear: Int?)

private const val MIN_MAKE_LENGTH = 2
private const val REPORT_BASE =
    "https://github.com/stargazer74/bike-maintainer-templates/issues/new?template=fehler-in-vorlage.yml"

/**
 * Search parameters for "is there a template for this vehicle": only with a make of at least two characters and a model.
 * Shared by the hint in the vehicle form and the offer after creating, so both always agree.
 */
fun templateSearchParams(make: String?, model: String?, type: VehicleType, modelYear: Int?): TemplateSearchParams? {
    val trimmedMake = make?.trim().orEmpty()
    val trimmedModel = model?.trim().orEmpty()
    if (trimmedMake.length < MIN_MAKE_LENGTH || trimmedModel.isEmpty()) return null
    return TemplateSearchParams(trimmedMake, type, trimmedModel, modelYear)
}

private fun TemplateTask.firstDueAlreadyPassed(vehicle: Vehicle, today: LocalDate): Boolean {
    if (firstDueKm != null && firstDueKm <= vehicle.currentMileage) return true
    return firstDueMonths != null && !vehicle.firstRegistrationDate.plusMonths(firstDueMonths.toLong()).isAfter(today)
}

/**
 * The rows of the apply dialog. Tasks the vehicle already has ([existingKeys]) are marked and not selected; a one-time
 * task whose first due date has passed already is not selected either.
 */
fun initialRows(
    template: VehicleTemplate,
    existingKeys: Set<String>,
    vehicle: Vehicle,
    today: LocalDate,
): List<TemplateTaskRow> = template.tasks.map { task ->
    val existing = task.key in existingKeys
    val selected = !existing && !(task.oneTime && task.firstDueAlreadyPassed(vehicle, today))
    TemplateTaskRow(task, selected, existing)
}

/** Fills the empty "last done" values of the rows that can be applied; returns new rows. */
fun fillEmpty(rows: List<TemplateTaskRow>, currentMileage: Int, today: LocalDate): List<TemplateTaskRow> =
    rows.map { row ->
        if (row.existing) {
            row.copy()
        } else {
            row.copy(lastDoneKm = row.lastDoneKm ?: currentMileage, lastDoneDate = row.lastDoneDate ?: today)
        }
    }

/** The selected, not yet existing rows as request; empty values are left out. */
fun toApplyRequest(templateId: String, rows: List<TemplateTaskRow>): TemplateApplication = TemplateApplication(
    templateId,
    rows.filter { it.selected && !it.existing }.map { AppliedTask(it.task.key, it.lastDoneKm, it.lastDoneDate) },
)

/** Link to report a mistake in a template (or one of its tasks); opened only on a tap. */
fun reportUrl(templateId: String, taskKey: String? = null): String {
    val task = if (taskKey.isNullOrEmpty()) "" else "&aufgabe=${encode(taskKey)}"
    return "$REPORT_BASE&vorlage=${encode(templateId)}$task"
}

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

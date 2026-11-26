package de.bikemaintainer.app.ui.template

import de.bikemaintainer.app.domain.TemplateApplyResult

/** How the template screen hands its result to the vehicle detail (a SavedStateHandle takes plain values). */
fun TemplateApplyResult.toNavValue(): String = "$created/$skipped"

fun parseTemplateResult(value: String?): TemplateApplyResult? {
    val parts = value?.split('/') ?: return null
    val created = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val skipped = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return if (parts.size == 2) TemplateApplyResult(created, skipped) else null
}

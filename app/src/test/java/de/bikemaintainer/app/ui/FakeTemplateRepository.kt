package de.bikemaintainer.app.ui

import de.bikemaintainer.app.data.repository.TemplateRepository
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TemplateApplication
import de.bikemaintainer.app.domain.TemplateApplyResult
import de.bikemaintainer.app.domain.TemplateSearchParams
import de.bikemaintainer.app.domain.TemplateSummary
import de.bikemaintainer.app.domain.VehicleTemplate
import kotlinx.coroutines.CompletableDeferred

/** Configurable [TemplateRepository] for ViewModel tests: set a `...Result`, read the `...Calls`, hold with a gate. */
class FakeTemplateRepository : TemplateRepository {
    var searchResult: Outcome<List<TemplateSummary>> = Outcome.Success(emptyList())
    val searchCalls = mutableListOf<TemplateSearchParams>()

    var templates = mutableMapOf<String, VehicleTemplate>()
    var getFailure: AppError? = null
    val getCalls = mutableListOf<String>()

    var applyResult: Outcome<TemplateApplyResult> = Outcome.Success(TemplateApplyResult(0, 0))
    var applyGate: CompletableDeferred<Unit>? = null
    val applyCalls = mutableListOf<Pair<Long, TemplateApplication>>()

    override suspend fun search(params: TemplateSearchParams): Outcome<List<TemplateSummary>> {
        searchCalls += params
        return searchResult
    }

    override suspend fun get(id: String): Outcome<VehicleTemplate> {
        getCalls += id
        getFailure?.let { return Outcome.Failure(it) }
        return Outcome.Success(templates[id] ?: error("template $id not set"))
    }

    override suspend fun apply(vehicleId: Long, application: TemplateApplication): Outcome<TemplateApplyResult> {
        applyCalls += vehicleId to application
        applyGate?.await()
        return applyResult
    }
}

package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TemplateApplication
import de.bikemaintainer.app.domain.TemplateApplyResult
import de.bikemaintainer.app.domain.TemplateSearchParams
import de.bikemaintainer.app.domain.TemplateSummary
import de.bikemaintainer.app.domain.VehicleTemplate

/**
 * Vehicle templates; implemented by [ApiTemplateRepository], faked in UI tests. Everything here is online-only:
 * nothing is stored offline and nothing goes into the outbox – without a connection the calls fail with
 * [de.bikemaintainer.app.domain.AppError.Network].
 */
interface TemplateRepository {
    /** Templates matching the vehicle data (see [de.bikemaintainer.app.domain.templateSearchParams]); a blank model is not sent. */
    suspend fun search(params: TemplateSearchParams): Outcome<List<TemplateSummary>>

    suspend fun get(id: String): Outcome<VehicleTemplate>

    /** Creates the chosen tasks for the vehicle; tasks it already has from the template are skipped by the server. */
    suspend fun apply(vehicleId: Long, application: TemplateApplication): Outcome<TemplateApplyResult>
}

class ApiTemplateRepository(private val provider: ApiProvider) : TemplateRepository {
    override suspend fun search(params: TemplateSearchParams): Outcome<List<TemplateSummary>> =
        provider.callStored {
            it.templates.searchVehicleTemplates(params.make, params.type.toApi(), params.model.ifBlank { null }, params.modelYear)
        }.map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): Outcome<VehicleTemplate> =
        provider.callStored { it.templates.getVehicleTemplate(id) }.map { it.toDomain() }

    override suspend fun apply(vehicleId: Long, application: TemplateApplication): Outcome<TemplateApplyResult> =
        provider.callStored { it.templates.applyVehicleTemplate(vehicleId, application.toRequest()) }
            .map { TemplateApplyResult(it.created, it.skipped) }
}

package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.ShimCalculationRequest
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.domain.MeasurementContext
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ShimInput
import de.bikemaintainer.app.domain.ShimSuggestion
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Target values, last measurements and the shim calculator; implemented by [ApiMeasurementRepository]. */
interface MeasurementRepository {
    /** Templates of the vehicle type, target values and last measurements; the first failing call fails it all. */
    suspend fun context(vehicleId: Long): Outcome<MeasurementContext>

    /** Shim suggestions in the order of [inputs]; nothing is stored. */
    suspend fun calculateShims(vehicleId: Long, inputs: List<ShimInput>): Outcome<List<ShimSuggestion>>
}

class ApiMeasurementRepository(private val provider: ApiProvider) : MeasurementRepository {
    override suspend fun context(vehicleId: Long): Outcome<MeasurementContext> = coroutineScope {
        val templates = async { provider.callStored { it.worksheets.listWorksheetTemplates(vehicleId) } }
        val specification = async { provider.callStored { it.measurements.getVehicleSpecifications(vehicleId) } }
        val latest = async { provider.callStored { it.measurements.getLatestMeasurements(vehicleId) } }
        val t = templates.await()
        val s = specification.await()
        val l = latest.await()
        when {
            t is Outcome.Failure -> t
            s is Outcome.Failure -> s
            l is Outcome.Failure -> l
            else -> Outcome.Success(
                MeasurementContext(
                    templates = (t as Outcome.Success).value.map { it.template.toDomain() },
                    specification = (s as Outcome.Success).value.toDomain(),
                    latest = (l as Outcome.Success).value.toDomain(),
                ),
            )
        }
    }

    override suspend fun calculateShims(vehicleId: Long, inputs: List<ShimInput>): Outcome<List<ShimSuggestion>> {
        val request = ShimCalculationRequest(inputs.map { it.toApi() })
        return provider.callStored { it.measurements.calculateShims(vehicleId, request) }
            .map { response -> response.results.map { it.toDomain() } }
    }
}

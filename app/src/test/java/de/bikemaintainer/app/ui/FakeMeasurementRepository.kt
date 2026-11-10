package de.bikemaintainer.app.ui

import de.bikemaintainer.app.data.repository.MeasurementRepository
import de.bikemaintainer.app.domain.LatestMeasurements
import de.bikemaintainer.app.domain.MeasurementContext
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ShimInput
import de.bikemaintainer.app.domain.ShimSuggestion
import de.bikemaintainer.app.domain.Specification
import kotlinx.coroutines.CompletableDeferred

/** Configurable [MeasurementRepository], same style as [FakeVehicleRepository]. By default: no templates. */
class FakeMeasurementRepository : MeasurementRepository {
    var contextResult: Outcome<MeasurementContext> =
        Outcome.Success(MeasurementContext(emptyList(), Specification(), LatestMeasurements()))
    var contextGate: CompletableDeferred<Unit>? = null
    val contextCalls = mutableListOf<Long>()

    var shimResult: Outcome<List<ShimSuggestion>> = Outcome.Success(emptyList())
    val shimCalls = mutableListOf<List<ShimInput>>()

    override suspend fun context(vehicleId: Long): Outcome<MeasurementContext> {
        contextCalls += vehicleId
        contextGate?.await()
        return contextResult
    }

    override suspend fun calculateShims(vehicleId: Long, inputs: List<ShimInput>): Outcome<List<ShimSuggestion>> {
        shimCalls += inputs
        return shimResult
    }
}

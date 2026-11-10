package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.data.repository.RepositoryTestEnv.Companion.json
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.BrakeMeasurement
import de.bikemaintainer.app.domain.Measured
import de.bikemaintainer.app.domain.MeasurementSource
import de.bikemaintainer.app.domain.MeasurementStatus
import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.Measurements
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.domain.ShimHint
import de.bikemaintainer.app.domain.ShimInput
import de.bikemaintainer.app.domain.ShimSuggestion
import de.bikemaintainer.app.domain.ValveAdjustment
import de.bikemaintainer.app.domain.ValveKind
import de.bikemaintainer.app.domain.ValveMeasurement
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeasurementRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var env: RepositoryTestEnv

    private val templates = """[{"template":"VALVE_CLEARANCE"},{"template":"BRAKES"}]"""
    private val specification = """{"cylinders":2,"valvesPerCylinder":4,"valveAdjustment":"SHIM",
        "intakeClearanceMin":0.10,"intakeClearanceMax":0.15,"shimStep":0.05,"brakePadMinFront":1.5}"""
    private val latest = """{"measurements":{
        "valveClearance":{"valves":[{"cylinder":1,"valve":"INTAKE","index":1,"measured":0.08,"shimOld":2.55,
            "shimNew":2.50,"measuredStatus":"OUT_OF_RANGE"}]},
        "brakes":{"padFront":3.2,"padFrontStatus":"OK","padRearStatus":"UNKNOWN","discFrontStatus":"UNKNOWN",
            "discRearStatus":"UNKNOWN","fluidCondition":"klar"}},
        "sources":{"valveClearance":{"logId":9,"performedAt":"2026-10-01","mileageAtPerformed":24000}}}"""

    @After
    fun tearDown() {
        env.close()
    }

    private fun setUp(handler: (RecordedRequest) -> MockResponse): MeasurementRepository {
        env = RepositoryTestEnv(tmp.newFile("s.preferences_pb"), handler = handler)
        runBlocking { env.settings.setServer(env.url, ServerMode.PORTAL) }
        return ApiMeasurementRepository(env.provider)
    }

    private fun contextHandler(failSpecification: Boolean = false): (RecordedRequest) -> MockResponse = { request ->
        when (request.url.encodedPath) {
            "/api/v1/vehicles/3/worksheets" -> json(templates)
            "/api/v1/vehicles/3/specifications" -> if (failSpecification) json("{}", 500) else json(specification)
            "/api/v1/vehicles/3/measurements/latest" -> json(latest)
            else -> json("{}", 404)
        }
    }

    @Test
    fun contextCombinesTemplatesSpecificationAndLatest() {
        val result = runBlocking { setUp(contextHandler()).context(3) }
        val context = (result as Outcome.Success).value
        assertEquals(listOf(MeasurementTemplate.VALVE_CLEARANCE, MeasurementTemplate.BRAKES), context.templates)
        assertEquals(2, context.specification.cylinders)
        assertEquals(ValveAdjustment.SHIM, context.specification.valveAdjustment)
        assertEquals(0, BigDecimal("0.10").compareTo(context.specification.intakeClearanceMin))
        assertTrue(context.specification.canSuggestShims.not()) // exhaust range missing
        val valve = context.latest.measurements.valves!!.single()
        assertEquals(ValveMeasurement(1, ValveKind.INTAKE, 1, valve.measured, valve.shimOld, valve.shimNew, MeasurementStatus.OUT_OF_RANGE), valve)
        assertEquals(0, BigDecimal("2.50").compareTo(valve.shimNew))
        val brakes = context.latest.measurements.brakes!!
        assertEquals(MeasurementStatus.OK, brakes.padFront.status)
        assertEquals(Measured(null, MeasurementStatus.UNKNOWN), brakes.padRear)
        assertEquals("klar", brakes.fluidCondition)
        assertEquals(
            mapOf(MeasurementTemplate.VALVE_CLEARANCE to MeasurementSource(9, LocalDate.of(2026, 10, 1), 24000)),
            context.latest.sources,
        )
    }

    @Test
    fun contextFailsWhenOnePartFails() {
        val result = runBlocking { setUp(contextHandler(failSpecification = true)).context(3) }
        assertTrue(result is Outcome.Failure && result.error is AppError.Http)
    }

    @Test
    fun calculateShimsSendsInputsAndMapsResults() {
        val repo = setUp {
            json("""{"results":[{"suggestedShim":2.50,"resultingClearance":0.13},{"hint":"MISSING_INPUT"}]}""")
        }
        val inputs = listOf(
            ShimInput(ValveKind.INTAKE, BigDecimal("0.08"), BigDecimal("2.55")),
            ShimInput(ValveKind.EXHAUST, null, null),
        )
        val result = runBlocking { repo.calculateShims(3, inputs) }
        val suggestions = (result as Outcome.Success).value
        assertEquals(2, suggestions.size)
        assertEquals(0, BigDecimal("2.50").compareTo(suggestions[0].suggestedShim))
        assertEquals(ShimSuggestion(null, null, ShimHint.MISSING_INPUT), suggestions[1])
        val request = env.requests.single()
        assertEquals("/api/v1/vehicles/3/shim-calculation", request.url.encodedPath)
        val valves = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["valves"]!!.jsonArray
        assertEquals("INTAKE", valves[0].jsonObject["valve"]!!.jsonPrimitive.content)
        assertEquals("0.08", valves[0].jsonObject["measured"]!!.jsonPrimitive.content)
        assertEquals("EXHAUST", valves[1].jsonObject["valve"]!!.jsonPrimitive.content)
    }

    @Test
    fun measurementsAreSentWithTheLog() {
        env = RepositoryTestEnv(tmp.newFile("s.preferences_pb"), handler = {
            json("""{"id":9,"vehicleId":3,"performedAt":"2026-11-09","mileageAtPerformed":24100,"materials":[],
                "currency":"EUR","createdAt":"2026-11-09T10:00:00Z"}""", 201)
        })
        runBlocking { env.settings.setServer(env.url, ServerMode.PORTAL) }
        val measurements = Measurements(
            valves = listOf(ValveMeasurement(1, ValveKind.EXHAUST, 2, BigDecimal("0.2"), null, null)),
            brakes = BrakeMeasurement(padFront = Measured(BigDecimal("3.5")), fluidCondition = "  "),
        )
        runBlocking {
            ApiMaintenanceRepository(env.provider)
                .logMaintenance(3, LocalDate.of(2026, 11, 9), 24100, setOf(1L), null, measurements)
        }
        val body = Json.parseToJsonElement(env.requests.single().body!!.utf8()).jsonObject["measurements"]!!.jsonObject
        val valve = body["valveClearance"]!!.jsonObject["valves"]!!.jsonArray.single().jsonObject
        assertEquals("EXHAUST", valve["valve"]!!.jsonPrimitive.content)
        assertEquals("2", valve["index"]!!.jsonPrimitive.content)
        val brakes = body["brakes"]!!.jsonObject
        assertEquals("3.5", brakes["padFront"]!!.jsonPrimitive.content)
        assertTrue("blank text is left out", brakes["fluidCondition"] == null || brakes["fluidCondition"].toString() == "null")
        assertTrue("unmeasured templates are left out", body["chain"] == null || body["chain"].toString() == "null")
    }
}

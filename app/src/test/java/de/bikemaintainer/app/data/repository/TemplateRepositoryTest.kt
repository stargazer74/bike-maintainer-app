package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.data.repository.RepositoryTestEnv.Companion.json
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.AppliedTask
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.domain.TemplateApplication
import de.bikemaintainer.app.domain.TemplateApplyResult
import de.bikemaintainer.app.domain.TemplateSearchParams
import de.bikemaintainer.app.domain.TemplateSourceKind
import de.bikemaintainer.app.domain.TemplateSourceQuality
import de.bikemaintainer.app.domain.VehicleType
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TemplateRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private var env: RepositoryTestEnv? = null

    @After
    fun tearDown() {
        env?.close()
    }

    private fun setUp(configured: Boolean = true, handler: (RecordedRequest) -> MockResponse): TemplateRepository {
        val e = RepositoryTestEnv(tmp.newFile("s.preferences_pb"), handler = handler)
        env = e
        if (configured) runBlocking { e.settings.setServer(e.url, ServerMode.PORTAL) }
        return ApiTemplateRepository(e.provider)
    }

    private val summaries = """[{"id":"honda-cb650r","type":"MOTORCYCLE","make":"Honda","model":"CB 650 R","yearFrom":2019,
        "taskCount":3,"sourceQuality":"MANUFACTURER"}]"""

    private val template = """{"id":"honda-cb650r","type":"MOTORCYCLE","make":"Honda","model":"CB 650 R","yearFrom":2019,
        "yearTo":2023,"taskCount":1,"sourceQuality":"SECONDARY","notes":"Hint",
        "sources":[{"id":"s1","kind":"FORUM","title":"Forum","retrieved":"2026-01-02","url":"https://x.test","locator":"p. 3"}],
        "tasks":[{"key":"oil","name":"Oil","oneTime":false,"intervalKm":6000,"intervalMonths":12,"sourceIds":["s1"],
        "details":[{"label":"Oil","value":"10W-40","quantity":"2.5 l","sourceIds":["s1"]}]}]}"""

    @Test
    fun searchSendsParametersAndMapsSummaries() {
        val repo = setUp { json(summaries) }
        val result = runBlocking {
            repo.search(TemplateSearchParams("Honda", VehicleType.MOTORCYCLE, "CB 650 R", 2020))
        } as Outcome.Success
        val hit = result.value.single()
        assertEquals("honda-cb650r", hit.id)
        assertEquals(TemplateSourceQuality.MANUFACTURER, hit.sourceQuality)
        assertNull(hit.yearTo)
        val url = env!!.requests.single().url
        assertEquals("/api/v1/vehicle-templates", url.encodedPath)
        assertEquals("Honda", url.queryParameter("make"))
        assertEquals("MOTORCYCLE", url.queryParameter("type"))
        assertEquals("CB 650 R", url.queryParameter("model"))
        assertEquals("2020", url.queryParameter("year"))
    }

    @Test
    fun searchWithoutYearOmitsIt() {
        val repo = setUp { json("[]") }
        runBlocking { repo.search(TemplateSearchParams("Honda", VehicleType.CAR, "X", null)) }
        assertNull(env!!.requests.single().url.queryParameter("year"))
    }

    @Test
    fun searchWithBlankModelOmitsIt() {
        val repo = setUp { json("[]") }
        runBlocking { repo.search(TemplateSearchParams("Honda", VehicleType.CAR, "", 2020)) }
        assertNull(env!!.requests.single().url.queryParameter("model"))
    }

    @Test
    fun getMapsTemplateWithSourcesTasksAndDetails() {
        val repo = setUp { json(template) }
        val value = (runBlocking { repo.get("honda-cb650r") } as Outcome.Success).value
        assertEquals("/api/v1/vehicle-templates/honda-cb650r", env!!.requests.single().url.encodedPath)
        assertEquals(2023, value.yearTo)
        assertEquals("Hint", value.notes)
        assertEquals(TemplateSourceKind.FORUM, value.sources.single().kind)
        assertEquals(LocalDate.of(2026, 1, 2), value.sources.single().retrieved)
        val task = value.tasks.single()
        assertEquals(6000, task.intervalKm)
        assertNull(task.firstDueKm)
        assertEquals("2.5 l", task.details.single().quantity)
    }

    @Test
    fun getUnknownTemplateIsNotFound() {
        val repo = setUp { json("""{"code":"NOT_FOUND"}""", 404) }
        assertEquals(Outcome.Failure(AppError.NotFound), runBlocking { repo.get("x") })
    }

    @Test
    fun applyPostsTheTasks() {
        val repo = setUp { json("""{"created":1,"skipped":1}""") }
        val application = TemplateApplication(
            "honda-cb650r",
            listOf(AppliedTask("oil", 5000, LocalDate.of(2026, 11, 26)), AppliedTask("chain", null, null)),
        )
        val result = runBlocking { repo.apply(7, application) }
        assertEquals(Outcome.Success(TemplateApplyResult(1, 1)), result)
        val request = env!!.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/vehicles/7/template", request.url.encodedPath)
        val body = request.body!!.utf8()
        assertTrue(body.contains(""""lastDoneKm":5000"""))
        assertTrue(body.contains(""""lastDoneDate":"2026-11-26""""))
        assertTrue(body.contains(""""key":"chain""""))
    }

    @Test
    fun applyWithoutConnectionIsNetworkErrorAndNothingIsQueued() {
        val repo = setUp { json("{}") }
        env!!.server.close()
        val result = runBlocking { repo.apply(7, TemplateApplication("t", listOf(AppliedTask("oil", 1, null)))) }
        assertEquals(Outcome.Failure(AppError.Network), result)
    }

    @Test
    fun withoutServerItIsNotConfigured() {
        val repo = setUp(configured = false) { json("[]") }
        assertEquals(
            Outcome.Failure(AppError.NotConfigured),
            runBlocking { repo.search(TemplateSearchParams("Honda", VehicleType.CAR, "X", null)) },
        )
    }
}

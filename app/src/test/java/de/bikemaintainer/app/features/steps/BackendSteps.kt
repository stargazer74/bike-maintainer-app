package de.bikemaintainer.app.features.steps

import de.bikemaintainer.app.api.models.AccessStatus
import de.bikemaintainer.app.api.models.OperatingMode
import de.bikemaintainer.app.api.models.VehicleType
import de.bikemaintainer.app.api.models.WorksheetTemplate
import de.bikemaintainer.app.features.support.World
import io.cucumber.datatable.DataTable
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Seeds the fake backend and the app's stored state, and checks what the app sent (web app: `backend.steps.ts`). */
class BackendSteps {
    private val backend get() = World.backend
    private val app get() = World.app

    // ---- server and session ----

    @Given("the app runs in {word} mode")
    fun mode(mode: String) {
        backend.mode = if (mode == "household") OperatingMode.HOUSEHOLD else OperatingMode.PORTAL
    }

    @Given("an account {string} exists")
    fun account(email: String) {
        backend.addAccount(email, PASSWORD)
    }

    @Given("an unverified account {string} exists")
    fun unverifiedAccount(email: String) {
        backend.addAccount(email, PASSWORD, verified = false)
    }

    @Given("the account {string} is in the trial")
    fun trial(email: String) {
        (backend.accounts[email] ?: backend.addAccount(email, PASSWORD)).access = AccessStatus.TRIAL
    }

    @Given("the access of the account {string} has expired")
    fun expired(email: String) {
        (backend.accounts[email] ?: backend.addAccount(email, PASSWORD)).access = AccessStatus.EXPIRED
    }

    /** Signed in before the scenario: the stored login of the app, as after a device login. */
    @Given("I am logged in as {string}")
    fun loggedIn(email: String) {
        backend.mode = OperatingMode.PORTAL
        val account = backend.accounts[email] ?: backend.addAccount(email, PASSWORD)
        backend.signedIn = account
        runBlocking {
            app.tokens.saveLogin(backend.url, "device-token")
            app.settings.setNotificationsAsked()
        }
    }

    @Given("the household has the profiles:")
    fun profiles(table: DataTable) {
        backend.mode = OperatingMode.HOUSEHOLD
        table.asList().filterNotNull().forEach { backend.addProfile(it) }
    }

    /** Household mode, the profile chosen before the scenario. */
    @Given("I use the household profile {string}")
    fun householdProfile(name: String) {
        backend.mode = OperatingMode.HOUSEHOLD
        if (backend.profiles.none { it.displayName == name }) backend.addProfile(name)
        val id = backend.profiles.first { it.displayName == name }.id
        runBlocking {
            app.settings.saveHouseholdProfile(backend.url, id)
            app.settings.setNotificationsAsked()
        }
    }

    /** The fake backend runs on localhost over http, so the app asks once before connecting without encryption. */
    @When("I enter the address of my server")
    fun enterServerAddress() = UiSteps().fillIn("Server-Adresse", backend.url)

    /** The whole "own server" setup, up to the login or profile page. */
    @When("I connect the app to my server")
    fun connect() {
        val ui = UiSteps()
        ui.click("Eigener Server")
        enterServerAddress()
        ui.click("Weiter")
        ui.click("Trotzdem verbinden")
    }

    // ---- vehicles and tasks ----

    @Given("a {word} {string} with {int} km exists")
    fun vehicle(type: String, name: String, km: Int) {
        backend.addVehicle(name, vehicleType(type), km)
    }

    @Given("the vehicle {string} has the task {string} every {int} km")
    fun task(vehicle: String, task: String, km: Int) {
        backend.addTask(backend.vehicle(vehicle).id, task, km)
    }

    @Given("the vehicle {string} has the task {string} every {int} km, last done at {int} km")
    fun taskDone(vehicle: String, task: String, km: Int, last: Int) {
        backend.addTask(backend.vehicle(vehicle).id, task, km, lastKm = last)
    }

    @Given("the vehicle {string} has the switched off task {string} every {int} km")
    fun inactiveTask(vehicle: String, task: String, km: Int) {
        backend.addTask(backend.vehicle(vehicle).id, task, km, active = false)
    }

    @Given("the vehicle {string} is a {string} {string}")
    fun makeAndModel(vehicle: String, make: String, model: String) = backend.setMakeAndModel(vehicle, make, model)

    @Given("a template for the {word} {string} {string} with the tasks {string} exists")
    fun template(type: String, make: String, model: String, tasks: String) {
        backend.addVehicleTemplate(vehicleType(type), make, model, tasks.split(",").map { it.trim() })
    }

    @Given("the vehicle {string} offers the worksheets {string}")
    fun worksheets(vehicle: String, names: String) {
        backend.templates[backend.vehicle(vehicle).id] = names.split(",").map { template(it.trim()) }
    }

    // ---- connection ----

    @Given("I have no connection")
    fun offline() {
        backend.reachable = false
    }

    @When("the connection is back")
    fun online() {
        backend.reachable = true
    }

    /** What WorkManager does in the app once there is a connection. */
    @When("the app sends what it kept on the device")
    fun sendPending() {
        runBlocking { app.outboxSync.run() }
    }

    // ---- what the app sent ----

    @Then("the backend stored a maintenance log for {string} at {int} km with the task {string}")
    fun storedLog(vehicle: String, km: Int, task: String) {
        val log = backend.logs.singleOrNull { it.vehicleId == backend.vehicle(vehicle).id }
        assertNotNull("No maintenance log for \"$vehicle\": ${backend.logs}", log)
        assertEquals(km, log!!.mileageAtPerformed)
        assertEquals(listOf(backend.task(task).id), log.performedTaskIds)
    }

    @Then("the backend stored no maintenance log")
    fun noLog() = assertEquals(emptyList<Any>(), backend.logs.toList())

    @Then("the backend stored the brake pad front {string} mm for {string}")
    fun storedBrakePad(value: String, vehicle: String) {
        val brakes = backend.logs.single { it.vehicleId == backend.vehicle(vehicle).id }.measurements?.brakes
        assertEquals(0, BigDecimal(value).compareTo(brakes?.padFront))
    }

    @Then("the backend has the vehicle {string} with {int} km")
    fun vehicleMileage(vehicle: String, km: Int) = assertEquals(km, backend.vehicle(vehicle).currentMileage)

    @Then("the backend has a {word} {string}")
    fun hasVehicle(type: String, name: String) = assertEquals(vehicleType(type), backend.vehicle(name).type)

    @Then("the backend has no vehicle {string}")
    fun noVehicle(name: String) = assertTrue(backend.vehicles.values.none { it.name == name })

    @Then("the backend has the task {string} every {int} km for {string}")
    fun hasTask(task: String, km: Int, vehicle: String) {
        val stored = backend.task(task)
        assertEquals(backend.vehicle(vehicle).id, stored.vehicleId)
        assertEquals(km, stored.intervalKm)
    }

    @Then("the backend has the task {string} from the template for {string}")
    fun templateTask(task: String, vehicle: String) {
        val stored = backend.task(task)
        assertEquals(backend.vehicle(vehicle).id, stored.vehicleId)
        assertNotNull("Task \"$task\" does not come from a template", stored.templateKey)
    }

    @Then("the backend has the task {string} for {string} last done at {int} km")
    fun lastDone(task: String, vehicle: String, km: Int) {
        val stored = backend.task(task)
        assertEquals(backend.vehicle(vehicle).id, stored.vehicleId)
        assertEquals(km, stored.lastServiceMileage)
    }

    @Then("the backend received no template search")
    fun noTemplateSearch() = assertTrue(backend.requests.none { it.url.encodedPath.startsWith("/api/v1/vehicle-templates") })

    @Then("the backend has no task {string}")
    fun noTask(task: String) = assertTrue(backend.tasks.values.none { it.name == task })

    @Then("the task {string} is switched off on the backend")
    fun taskOff(task: String) = assertEquals(false, backend.task(task).active)

    @Then("the backend received a registration for {string}")
    fun registration(email: String) = assertEquals(listOf(email), backend.registrations.map { it.email })

    @Then("the backend received no registration")
    fun noRegistration() = assertEquals(emptyList<Any>(), backend.registrations.toList())

    @Then("the backend sent the confirmation e-mail again to {string}")
    fun resent(email: String) = assertEquals(listOf(email), backend.resentVerifications.toList())

    @Then("the backend has the profile {string}")
    fun hasProfile(name: String) = assertTrue(backend.profiles.any { it.displayName == name })

    @Then("the account {string} no longer exists")
    fun accountDeleted(email: String) = assertNull(backend.accounts[email])

    @Then("the account {string} still exists")
    fun accountKept(email: String) = assertNotNull(backend.accounts[email])

    @Then("I am logged out on the backend")
    fun loggedOut() = assertNull(backend.signedIn)

    companion object {
        const val PASSWORD = "geheim-passwort-1"

        fun vehicleType(type: String) = when (type) {
            "motorcycle" -> VehicleType.MOTORCYCLE
            "car" -> VehicleType.CAR
            "bicycle" -> VehicleType.BICYCLE
            else -> error("Unknown vehicle type \"$type\"")
        }

        fun template(name: String) = when (name) {
            "Ventilspiel" -> WorksheetTemplate.VALVE_CLEARANCE
            "Bremsen" -> WorksheetTemplate.BRAKES
            "Kette" -> WorksheetTemplate.CHAIN
            "Reifen" -> WorksheetTemplate.TIRES
            else -> error("Unknown worksheet \"$name\"")
        }
    }
}

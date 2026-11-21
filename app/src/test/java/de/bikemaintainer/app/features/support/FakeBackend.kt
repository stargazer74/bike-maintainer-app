package de.bikemaintainer.app.features.support

import de.bikemaintainer.app.api.infrastructure.Serializer
import de.bikemaintainer.app.api.models.AccessStatus
import de.bikemaintainer.app.api.models.AppConfigResponse
import de.bikemaintainer.app.api.models.BrakeMeasurementResponse
import de.bikemaintainer.app.api.models.Currency
import de.bikemaintainer.app.api.models.DeleteAccountRequest
import de.bikemaintainer.app.api.models.DeviceLoginRequest
import de.bikemaintainer.app.api.models.DeviceLoginResponse
import de.bikemaintainer.app.api.models.DeviceSessionRequest
import de.bikemaintainer.app.api.models.EmailRequest
import de.bikemaintainer.app.api.models.Language
import de.bikemaintainer.app.api.models.LatestMeasurementsResponse
import de.bikemaintainer.app.api.models.LatestMeasurementsResponseSources
import de.bikemaintainer.app.api.models.MaintenanceLogRequest
import de.bikemaintainer.app.api.models.MaintenanceLogResponse
import de.bikemaintainer.app.api.models.MaintenanceMeasurementsResponse
import de.bikemaintainer.app.api.models.MaintenanceTaskRequest
import de.bikemaintainer.app.api.models.MaintenanceTaskResponse
import de.bikemaintainer.app.api.models.MaintenanceTaskStatus
import de.bikemaintainer.app.api.models.MeasurementSource
import de.bikemaintainer.app.api.models.MeasurementStatus
import de.bikemaintainer.app.api.models.MileageRequest
import de.bikemaintainer.app.api.models.OperatingMode
import de.bikemaintainer.app.api.models.ProfileRequest
import de.bikemaintainer.app.api.models.ProfileResponse
import de.bikemaintainer.app.api.models.RegisterRequest
import de.bikemaintainer.app.api.models.ShimCalculationRequest
import de.bikemaintainer.app.api.models.ShimCalculationResponse
import de.bikemaintainer.app.api.models.UserResponse
import de.bikemaintainer.app.api.models.UserRole
import de.bikemaintainer.app.api.models.VehicleRequest
import de.bikemaintainer.app.api.models.VehicleResponse
import de.bikemaintainer.app.api.models.VehicleSpecifications
import de.bikemaintainer.app.api.models.VehicleType
import de.bikemaintainer.app.api.models.WorksheetTemplate
import de.bikemaintainer.app.api.models.WorksheetTemplateResponse
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Collections
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/**
 * The backend of a scenario, in memory, typed with the generated API models (like the web app's
 * `e2e/support/fake-backend.ts`). Rules are kept simple – task status by km only, measurement status unknown – the
 * business rules are tested in the backend's own Cucumber suite. A request to an endpoint the fake does not implement
 * is recorded and fails the scenario: add the endpoint here rather than loosening that.
 */
class FakeBackend {
    data class Account(val email: String, var password: String, val verified: Boolean, var access: AccessStatus)

    private val json = Serializer.kotlinxSerializationJson
    private val created: OffsetDateTime = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    private var nextId = 100L

    var server = MockWebServer()
        private set
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    val unknownRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile var mode: OperatingMode = OperatingMode.PORTAL

    /**
     * Off: the server is stopped, so connecting fails as without network; on again: it starts anew on the same port
     * (the app keeps its address). Its state stays.
     */
    @Volatile var reachable: Boolean = true
        set(value) {
            if (value == field) return
            field = value
            if (value) {
                server = startOnPort()
            } else {
                server.close()
            }
        }

    private val port: Int

    /** The port may still be held for a moment after the stop; try again shortly. */
    private fun startOnPort(): MockWebServer {
        repeat(BIND_ATTEMPTS) { attempt ->
            val next = MockWebServer().also { it.dispatcher = dispatcher }
            try {
                next.start(port)
                return next
            } catch (e: java.net.BindException) {
                next.close()
                if (attempt == BIND_ATTEMPTS - 1) throw e
                Thread.sleep(BIND_PAUSE_MILLIS)
            }
        }
        error("unreachable")
    }

    val accounts = mutableMapOf<String, Account>()
    @Volatile var signedIn: Account? = null
    val profiles = mutableListOf<ProfileResponse>()
    val vehicles = linkedMapOf<Long, VehicleResponse>()
    val tasks = linkedMapOf<Long, MaintenanceTaskResponse>()
    val logs = mutableListOf<MaintenanceLogResponse>()
    val templates = mutableMapOf<Long, List<WorksheetTemplate>>()
    val specifications = mutableMapOf<Long, VehicleSpecifications>()
    val registrations = mutableListOf<RegisterRequest>()
    val resentVerifications = mutableListOf<String>()

    /** The address the app is set up with; it stays the same while the server is off. */
    val url: String

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            return synchronized(this@FakeBackend) { answer(request) }
        }
    }

    init {
        server.dispatcher = dispatcher
        server.start()
        port = server.port
        url = server.url("/").toString()
    }

    fun close() = server.close()

    // ---- seeding ----

    fun addAccount(email: String, password: String, verified: Boolean = true, access: AccessStatus = AccessStatus.FULL) =
        synchronized(this) { Account(email, password, verified, access).also { accounts[email] = it } }

    fun addProfile(name: String) = synchronized(this) { profiles += ProfileResponse(id(), name, 0) }

    fun addVehicle(name: String, type: VehicleType, km: Int): VehicleResponse = synchronized(this) {
        VehicleResponse(id(), name, type, 2020, LocalDate.of(2020, 5, 1), km, created, created).also { vehicles[it.id] = it }
    }

    fun addTask(vehicleId: Long, name: String, intervalKm: Int?, lastKm: Int? = null, active: Boolean = true) =
        synchronized(this) {
            val task = MaintenanceTaskResponse(
                id(), vehicleId, name, oneTime = false, active = active, status = MaintenanceTaskStatus.OK,
                intervalKm = intervalKm, lastServiceMileage = lastKm,
            )
            tasks[task.id] = task
            task
        }

    fun vehicle(name: String): VehicleResponse =
        synchronized(this) { vehicles.values.firstOrNull { it.name == name } ?: error("No vehicle \"$name\"") }

    fun task(name: String): MaintenanceTaskResponse =
        synchronized(this) { tasks.values.firstOrNull { it.name == name } ?: error("No task \"$name\"") }

    // ---- answers ----

    private fun answer(request: RecordedRequest): MockResponse {
        val method = request.method
        val path = request.url.encodedPath
        val segments = path.removePrefix("/api/v1/").split("/")
        return when {
            path == "/api/v1/config" -> ok(
                AppConfigResponse.serializer(),
                AppConfigResponse(mode),
                // The backend's CSRF cookie; the app sends it back as X-XSRF-TOKEN on state-changing requests.
                cookie = "XSRF-TOKEN=csrf; Path=/",
            )
            path.startsWith("/api/v1/auth/") -> auth(method, segments.drop(1), request)
            path.startsWith("/api/v1/profiles") -> profiles(method, segments.drop(1), request)
            path.startsWith("/api/v1/vehicles") -> vehicles(method, segments.drop(1), request)
            else -> unknown(request)
        }
    }

    private fun auth(method: String, rest: List<String>, request: RecordedRequest): MockResponse = when {
        method == "GET" && rest == listOf("challenge") -> MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body(CHALLENGE)
            .build()
        method == "POST" && rest == listOf("register") -> {
            registrations += body(request, RegisterRequest.serializer())
            empty(202)
        }
        method == "POST" && rest == listOf("verify-email", "resend") -> {
            resentVerifications += body(request, EmailRequest.serializer()).email
            empty(202)
        }
        method == "POST" && rest == listOf("device-login") -> {
            val login = body(request, DeviceLoginRequest.serializer())
            val account = accounts[login.email]
            when {
                account == null || account.password != login.password -> error(401, "INVALID_CREDENTIALS")
                !account.verified -> error(403, "EMAIL_NOT_VERIFIED")
                else -> {
                    signedIn = account
                    ok(DeviceLoginResponse.serializer(), DeviceLoginResponse(user(account), "device-token"))
                }
            }
        }
        method == "POST" && rest == listOf("device-session") -> {
            val session = body(request, DeviceSessionRequest.serializer())
            ok(
                DeviceLoginResponse.serializer(),
                DeviceLoginResponse(user(signedIn), session.nextDeviceToken ?: "device-token-2"),
            )
        }
        method == "GET" && rest == listOf("me") -> ok(UserResponse.serializer(), user(signedIn))
        method == "DELETE" && rest == listOf("me") -> {
            val password = body(request, DeleteAccountRequest.serializer()).password
            val account = signedIn
            if (account == null || account.password != password) {
                error(403, "WRONG_PASSWORD")
            } else {
                accounts.remove(account.email)
                signedIn = null
                vehicles.clear()
                empty(204)
            }
        }
        method == "POST" && rest == listOf("logout") -> {
            signedIn = null
            empty(204)
        }
        else -> unknown(request)
    }

    private fun profiles(method: String, rest: List<String>, request: RecordedRequest): MockResponse = when {
        method == "GET" && rest.isEmpty() -> ok(ListSerializer(ProfileResponse.serializer()), profiles.toList())
        method == "POST" && rest.isEmpty() -> {
            val name = body(request, ProfileRequest.serializer()).displayName
            if (profiles.any { it.displayName == name }) {
                error(409, "PROFILE_NAME_TAKEN")
            } else {
                val profile = ProfileResponse(id(), name, 0)
                profiles += profile
                ok(ProfileResponse.serializer(), profile, code = 201)
            }
        }
        method == "POST" && rest.size == 2 && rest[1] == "select" -> {
            val profile = profiles.firstOrNull { it.id == rest[0].toLongOrNull() }
            if (profile == null) {
                error(404, "NOT_FOUND")
            } else {
                ok(UserResponse.serializer(), user(null).copy(displayName = profile.displayName))
            }
        }
        else -> unknown(request)
    }

    private fun vehicles(method: String, rest: List<String>, request: RecordedRequest): MockResponse {
        if (rest.isEmpty()) {
            return when (method) {
                "GET" -> ok(ListSerializer(VehicleResponse.serializer()), vehicles.values.toList())
                "POST" -> {
                    val vehicle = body(request, VehicleRequest.serializer()).toResponse(id())
                    vehicles[vehicle.id] = vehicle
                    ok(VehicleResponse.serializer(), vehicle, code = 201)
                }
                else -> unknown(request)
            }
        }
        val vehicleId = rest[0].toLongOrNull() ?: return unknown(request)
        val vehicle = vehicles[vehicleId] ?: return error(404, "NOT_FOUND")
        val sub = rest.drop(1)
        return when {
            sub.isEmpty() && method == "GET" -> ok(VehicleResponse.serializer(), vehicle)
            sub.isEmpty() && method == "PUT" -> {
                val updated = body(request, VehicleRequest.serializer()).toResponse(vehicleId)
                vehicles[vehicleId] = updated
                ok(VehicleResponse.serializer(), updated)
            }
            sub.isEmpty() && method == "DELETE" -> {
                vehicles.remove(vehicleId)
                tasks.values.removeAll { it.vehicleId == vehicleId }
                logs.removeAll { it.vehicleId == vehicleId }
                empty(204)
            }
            sub == listOf("mileage") && method == "PUT" -> {
                val mileage = body(request, MileageRequest.serializer()).mileage
                if (mileage < vehicle.currentMileage) {
                    error(409, "MILEAGE_DECREASE")
                } else {
                    val updated = vehicle.copy(currentMileage = mileage)
                    vehicles[vehicleId] = updated
                    ok(VehicleResponse.serializer(), updated)
                }
            }
            sub == listOf("maintenance-tasks") && method == "GET" ->
                ok(ListSerializer(MaintenanceTaskResponse.serializer()), tasksOf(vehicle))
            sub == listOf("maintenance-tasks") && method == "POST" -> {
                val task = body(request, MaintenanceTaskRequest.serializer()).toResponse(id(), vehicleId, null)
                tasks[task.id] = task
                ok(MaintenanceTaskResponse.serializer(), withStatus(task, vehicle), code = 201)
            }
            sub.size == 2 && sub[0] == "maintenance-tasks" -> {
                val task = tasks[sub[1].toLongOrNull()]?.takeIf { it.vehicleId == vehicleId }
                    ?: return error(404, "NOT_FOUND")
                when (method) {
                    "GET" -> ok(MaintenanceTaskResponse.serializer(), withStatus(task, vehicle))
                    "PUT" -> {
                        val updated = body(request, MaintenanceTaskRequest.serializer())
                            .toResponse(task.id, vehicleId, task.lastServiceMileage)
                        tasks[task.id] = updated
                        ok(MaintenanceTaskResponse.serializer(), withStatus(updated, vehicle))
                    }
                    "DELETE" -> {
                        tasks.remove(task.id)
                        empty(204)
                    }
                    else -> unknown(request)
                }
            }
            sub == listOf("maintenance-logs") && method == "GET" ->
                ok(ListSerializer(MaintenanceLogResponse.serializer()), logs.filter { it.vehicleId == vehicleId })
            sub == listOf("maintenance-logs") && method == "POST" -> createLog(vehicle, body(request, MaintenanceLogRequest.serializer()))
            sub == listOf("worksheets") && method == "GET" -> ok(
                ListSerializer(WorksheetTemplateResponse.serializer()),
                templates[vehicleId].orEmpty().map { WorksheetTemplateResponse(it) },
            )
            sub == listOf("specifications") && method == "GET" ->
                ok(VehicleSpecifications.serializer(), specifications[vehicleId] ?: VehicleSpecifications())
            sub == listOf("measurements", "latest") && method == "GET" ->
                ok(LatestMeasurementsResponse.serializer(), latestOf(vehicleId))
            sub == listOf("shim-calculation") && method == "POST" -> {
                body(request, ShimCalculationRequest.serializer())
                ok(ShimCalculationResponse.serializer(), ShimCalculationResponse(emptyList()))
            }
            else -> unknown(request)
        }
    }

    private fun createLog(vehicle: VehicleResponse, log: MaintenanceLogRequest): MockResponse {
        val taskIds = log.performedTaskIds.orEmpty()
        if (taskIds.any { tasks[it]?.vehicleId != vehicle.id }) return error(400, "VALIDATION_FAILED")
        val response = MaintenanceLogResponse(
            id = id(),
            vehicleId = vehicle.id,
            performedAt = log.performedAt,
            mileageAtPerformed = log.mileageAtPerformed,
            materials = emptyList(),
            currency = Currency.EUR,
            createdAt = created,
            clientEntryId = log.clientEntryId,
            notes = log.notes,
            performedTaskIds = taskIds.toList(),
            measurements = log.measurements?.let { m ->
                MaintenanceMeasurementsResponse(
                    brakes = m.brakes?.let {
                        BrakeMeasurementResponse(
                            MeasurementStatus.UNKNOWN, MeasurementStatus.UNKNOWN, MeasurementStatus.UNKNOWN,
                            MeasurementStatus.UNKNOWN, it.padFront, it.padRear, it.discFront, it.discRear,
                            it.discConditionFront, it.discConditionRear, it.fluidCondition,
                        )
                    },
                )
            },
        )
        logs += response
        taskIds.forEach { id -> tasks[id]?.let { tasks[id] = it.copy(lastServiceMileage = log.mileageAtPerformed, lastServiceDate = log.performedAt) } }
        return ok(MaintenanceLogResponse.serializer(), response, code = 201)
    }

    private fun latestOf(vehicleId: Long): LatestMeasurementsResponse {
        val withBrakes = logs.lastOrNull { it.vehicleId == vehicleId && it.measurements?.brakes != null }
        return LatestMeasurementsResponse(
            MaintenanceMeasurementsResponse(brakes = withBrakes?.measurements?.brakes),
            LatestMeasurementsResponseSources(
                brakes = withBrakes?.let { MeasurementSource(it.id, it.performedAt, it.mileageAtPerformed) },
            ),
        )
    }

    private fun tasksOf(vehicle: VehicleResponse) =
        tasks.values.filter { it.vehicleId == vehicle.id }.map { withStatus(it, vehicle) }

    /** Status by km only: overdue at 0 km left, due soon within 10 % of the interval. */
    private fun withStatus(task: MaintenanceTaskResponse, vehicle: VehicleResponse): MaintenanceTaskResponse {
        val interval = task.intervalKm ?: return task.copy(status = MaintenanceTaskStatus.OK)
        val left = (task.lastServiceMileage ?: 0) + interval - vehicle.currentMileage
        val status = when {
            left <= 0 -> MaintenanceTaskStatus.OVERDUE
            left <= interval / 10 -> MaintenanceTaskStatus.DUE_SOON
            else -> MaintenanceTaskStatus.OK
        }
        return task.copy(status = status, kmRemaining = left)
    }

    private fun user(account: Account?) = UserResponse(
        id = 1,
        role = UserRole.USER,
        language = Language.DE,
        currency = Currency.EUR,
        active = true,
        emailVerified = true,
        createdAt = created,
        accessStatus = account?.access ?: AccessStatus.FULL,
        hasSubscription = false,
        email = account?.email,
        accessUntil = if (account?.access == AccessStatus.TRIAL) LocalDate.now().plusDays(10) else null,
    )

    private fun VehicleRequest.toResponse(id: Long) =
        VehicleResponse(id, name, type, modelYear, firstRegistrationDate, currentMileage, created, created, make, model)

    private fun MaintenanceTaskRequest.toResponse(id: Long, vehicleId: Long, lastKm: Int?) = MaintenanceTaskResponse(
        id, vehicleId, name, oneTime ?: false, active ?: true, MaintenanceTaskStatus.OK, description, intervalKm,
        intervalMonths, firstDueKm, firstDueMonths, lastServiceMileage = lastKm,
    )

    private fun id() = nextId++

    private fun <T> body(request: RecordedRequest, serializer: KSerializer<T>): T =
        json.decodeFromString(serializer, request.body!!.utf8())

    private fun <T> ok(serializer: KSerializer<T>, value: T, code: Int = 200, cookie: String? = null): MockResponse =
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }
            .body(json.encodeToString(serializer, value))
            .build()

    private fun empty(code: Int) = MockResponse.Builder().code(code).build()

    private fun error(code: Int, errorCode: String) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body("""{"code":"$errorCode","message":"$errorCode"}""")
        .build()

    private fun unknown(request: RecordedRequest): MockResponse {
        unknownRequests += "${request.method} ${request.url.encodedPath}"
        return MockResponse.Builder().code(501).build()
    }

    private companion object {
        const val BIND_ATTEMPTS = 20
        const val BIND_PAUSE_MILLIS = 100L
        /** A cheap ALTCHA challenge (few PBKDF2 rounds, one hex digit): the app really solves it, the fake does not check. */
        const val CHALLENGE = """{"parameters":{"algorithm":"PBKDF2/SHA-256","cost":10,"expiresAt":4102444800,
            "keyLength":32,"keyPrefix":"0","nonce":"f169e76eacde014b37796bb59c45a342",
            "salt":"fc4ded38597e02b195385380f083d497"},"signature":"00"}"""
    }
}

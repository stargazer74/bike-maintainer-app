package de.bikemaintainer.app.api

import de.bikemaintainer.app.api.apis.VehiclesApi
import de.bikemaintainer.app.api.infrastructure.Serializer
import de.bikemaintainer.app.api.models.VehicleResponse
import de.bikemaintainer.app.api.models.VehicleType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import okhttp3.MediaType.Companion.toMediaType
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.LocalDate

class GeneratedClientTest {

    @Test
    fun vehicleResponseDeserializes() {
        val json = """{"id":7,"name":"Africa Twin","type":"MOTORCYCLE","modelYear":2020,
            "firstRegistrationDate":"2020-04-01","currentMileage":23500,
            "createdAt":"2026-01-01T10:00:00Z","updatedAt":"2026-01-02T10:00:00Z","unknownField":1}"""
        val v = Serializer.kotlinxSerializationJson.decodeFromString<VehicleResponse>(json)
        assertEquals(LocalDate.of(2020, 4, 1), v.firstRegistrationDate)
        assertEquals(VehicleType.MOTORCYCLE, v.type)
        assertNull(v.make)
    }

    @Test
    fun vehiclesApiCallsRelativePath() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("[]").addHeader("Content-Type", "application/json").build())
            server.start()
            val api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(
                    Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType())
                )
                .build()
                .create(VehiclesApi::class.java)
            val response = api.listVehicles()
            assertEquals(true, response.isSuccessful)
            assertEquals("/api/v1/vehicles", server.takeRequest().url.encodedPath)
        }
    }
}

package de.bikemaintainer.app.data.network

import de.bikemaintainer.app.api.infrastructure.Serializer
import de.bikemaintainer.app.api.models.AccessStatus
import de.bikemaintainer.app.api.models.DeviceLoginResponse
import de.bikemaintainer.app.api.models.UserResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit

class UserDefaultsConverterFactoryTest {
    private val json = Serializer.kotlinxSerializationJson
    private val factory = UserDefaultsConverterFactory(json)
    private val retrofit = Retrofit.Builder().baseUrl("http://localhost/").build()

    private val oldUser = """{"id":1,"role":"USER","language":"de","currency":"EUR","active":true,"emailVerified":true,
        "createdAt":"2026-01-01T00:00:00Z","displayName":"Chris"}"""

    private fun <T> decode(type: Class<T>, body: String): T {
        @Suppress("UNCHECKED_CAST")
        val converter = factory.responseBodyConverter(type, emptyArray(), retrofit)!! as retrofit2.Converter<okhttp3.ResponseBody, T>
        return converter.convert(body.toResponseBody("application/json".toMediaType()))!!
    }

    @Test fun userOfAnOlderServerGetsFullAccess() {
        val user = decode(UserResponse::class.java, oldUser)
        assertEquals(AccessStatus.FULL, user.accessStatus)
        assertFalse(user.hasSubscription)
    }

    @Test fun userInsideTheDeviceLoginIsCompletedToo() {
        val login = decode(DeviceLoginResponse::class.java, """{"user":$oldUser,"deviceToken":"t"}""")
        assertEquals(AccessStatus.FULL, login.user.accessStatus)
    }

    @Test fun fieldsOfTheServerWin() {
        val user = decode(
            UserResponse::class.java,
            oldUser.replace("\"active\":true", "\"active\":true,\"accessStatus\":\"EXPIRED\",\"hasSubscription\":true"),
        )
        assertEquals(AccessStatus.EXPIRED, user.accessStatus)
        assertEquals(true, user.hasSubscription)
    }

    @Test fun otherTypesAreLeftToTheNextConverter() {
        assertNull(factory.responseBodyConverter(String::class.java, emptyArray(), retrofit))
    }

    @Test fun onlyUsersAreCompleted() {
        val patched = withUserDefaults(Json.parseToJsonElement("""{"role":"USER","emailVerified":true,"other":{"a":1}}""")).jsonObject
        assertEquals("FULL", patched.getValue("accessStatus").jsonPrimitive.content)
        assertFalse(patched.getValue("other").jsonObject.containsKey("accessStatus"))
    }
}

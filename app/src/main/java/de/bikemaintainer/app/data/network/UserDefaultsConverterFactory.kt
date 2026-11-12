package de.bikemaintainer.app.data.network

import de.bikemaintainer.app.api.models.DeviceLoginResponse
import de.bikemaintainer.app.api.models.UserResponse
import java.lang.reflect.Type
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit

/**
 * Servers before the subscription (API 2.16) send users without `accessStatus` and `hasSubscription`, which the
 * generated models require – login, profile selection and `auth/me` would fail on an older (household) server. This
 * factory adds both (`FULL`, `false`) to every user in such a response before decoding, so an older server is never
 * locked. Registered before the kotlinx converter; every other type passes through to it.
 */
class UserDefaultsConverterFactory(private val json: Json) : Converter.Factory() {
    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *>? {
        if (type != UserResponse::class.java && type != DeviceLoginResponse::class.java) return null
        val serializer = json.serializersModule.serializer(type)
        return Converter<ResponseBody, Any?> { body ->
            body.use { json.decodeFromJsonElement(serializer, withUserDefaults(json.parseToJsonElement(it.string()))) }
        }
    }
}

/** Adds the missing access fields to every object that is a user (it has `role` and `emailVerified`). */
internal fun withUserDefaults(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> {
        val patched = element.mapValues { (_, value) -> withUserDefaults(value) }.toMutableMap()
        if ("role" in element && "emailVerified" in element) {
            patched.putIfAbsent("accessStatus", JsonPrimitive("FULL"))
            patched.putIfAbsent("hasSubscription", JsonPrimitive(false))
        }
        JsonObject(patched)
    }
    is JsonArray -> JsonArray(element.map(::withUserDefaults))
    else -> element
}

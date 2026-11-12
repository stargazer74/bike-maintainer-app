package de.bikemaintainer.app.data.network

import de.bikemaintainer.app.api.models.DeleteAccountRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HTTP

/**
 * `DELETE /api/v1/auth/me` with the password in the body. The generated `AuthApi.deleteCurrentUser` uses `@DELETE`
 * with `@Body`, which Retrofit refuses at runtime ("Non-body HTTP method cannot contain @Body").
 */
interface AccountApi {
    @HTTP(method = "DELETE", path = "api/v1/auth/me", hasBody = true)
    suspend fun deleteCurrentUser(@Body request: DeleteAccountRequest): Response<Unit>
}

package de.bikemaintainer.app.data.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET

/**
 * `GET /api/v1/auth/challenge` as raw text. The generated client models the challenge as a map of arbitrary values,
 * which kotlinx.serialization cannot read – and the challenge must reach the solver unchanged anyway, because its
 * signature covers the exact parameters.
 */
interface ChallengeApi {
    @GET("api/v1/auth/challenge")
    suspend fun challenge(): Response<ResponseBody>
}

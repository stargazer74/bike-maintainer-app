package de.bikemaintainer.app.domain

import de.bikemaintainer.app.domain.scan.testResource
import java.util.Base64
import javax.crypto.Mac
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The fixtures in `resources/altcha/` are real challenges of the backend (`GET /api/v1/auth/challenge`, dev stack). The
 * expected counters and keys were computed independently with Python's `hashlib.pbkdf2_hmac`.
 */
class AltchaSolverTest {
    private fun fixture(name: String) = testResource("altcha/$name").readText().trim()

    private fun decode(payload: String) =
        Json.parseToJsonElement(String(Base64.getDecoder().decode(payload), Charsets.UTF_8)).jsonObject

    @Test
    fun solvesRealChallengesOfTheBackend() = runTest {
        val expected = mapOf(
            "challenge-1.json" to (740L to "0052f7e07eb3f71051dc3d35ec2944bc1ab6780810dbcbcd7c263baf7e962543"),
            "challenge-2.json" to (308L to "00bb18640ea640f7211bd338fb0072511fa7c01ce2818a8f5b920058dc4ef832"),
        )
        for ((file, solution) in expected) {
            val solved = decode(AltchaSolver.solve(fixture(file))).getValue("solution").jsonObject
            assertEquals(file, solution.first, solved.getValue("counter").jsonPrimitive.long)
            assertEquals(file, solution.second, solved.getValue("derivedKey").jsonPrimitive.content)
        }
    }

    @Test
    fun keepsTheChallengeExactlyAsTheServerSentIt() = runTest {
        val challenge = fixture("challenge-1.json")
        val payload = String(Base64.getDecoder().decode(AltchaSolver.solve(challenge)), Charsets.UTF_8)
        // Same text, same key order and number format: the signature covers it.
        assertTrue(payload, payload.startsWith("{\"challenge\":$challenge,\"solution\":{\"counter\":740,"))
    }

    @Test
    fun pbkdf2MatchesTheRfcTestVectors() {
        // RFC 7914, section 11 (PBKDF2-HMAC-SHA256).
        val mac = Mac.getInstance("HmacSHA256")
        val key = AltchaSolver.pbkdf2(mac, "passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            key.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun rejectsMalformedChallenges() = runTest {
        val invalid = listOf(
            "",
            "not json",
            "[]",
            """{"signature":"x"}""",
            """{"parameters":{"algorithm":"SHA-256","cost":1,"keyPrefix":"0","nonce":"00","salt":"00"},"signature":"x"}""",
            """{"parameters":{"algorithm":"PBKDF2/SHA-256","cost":0,"keyPrefix":"0","nonce":"00","salt":"00"},"signature":"x"}""",
            """{"parameters":{"algorithm":"PBKDF2/SHA-256","cost":1,"keyPrefix":"zz","nonce":"00","salt":"00"},"signature":"x"}""",
            """{"parameters":{"algorithm":"PBKDF2/SHA-256","cost":1,"keyPrefix":"0","nonce":"0g","salt":"00"},"signature":"x"}""",
        )
        for (json in invalid) {
            val result = runCatching { AltchaSolver.solve(json) }
            assertTrue(json, result.exceptionOrNull() is AltchaSolver.InvalidChallengeException)
        }
    }

    @Test
    fun canBeCancelled() = runTest {
        // A prefix that is practically never reached: the solver keeps going until it is cancelled.
        val hard = """{"parameters":{"algorithm":"PBKDF2/SHA-256","cost":1,"keyLength":32,""" +
            """"keyPrefix":"0000000000000000","nonce":"00","salt":"00"},"signature":"x"}"""
        val job = launch(Dispatchers.Default) { AltchaSolver.solve(hard) }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    /**
     * End-to-end against a running backend in portal mode, e.g. `BM_ALTCHA_LIVE_URL=http://localhost:8100`
     * (bike-maintainer-deploy/dev). Skipped without the variable. A solution is accepted (registration 202) only once.
     */
    @Test
    fun backendAcceptsTheSolutionOnce() = runTest {
        val base = System.getenv("BM_ALTCHA_LIVE_URL")
        assumeTrue("BM_ALTCHA_LIVE_URL not set", !base.isNullOrBlank())
        val client = OkHttpClient()
        val challenge = client.newCall(Request.Builder().url("$base/api/v1/auth/challenge").build()).execute()
            .use { it.body.string() }
        val payload = AltchaSolver.solve(challenge)
        fun register(): Int {
            val body = """{"email":"altcha-test@example.org","password":"altcha-test-1234","altcha":"$payload"}"""
                .toRequestBody("application/json".toMediaType())
            // The CSRF cookie and header come from the first GET; register needs both.
            val config = client.newCall(Request.Builder().url("$base/api/v1/config").build()).execute()
            val token = config.headers("Set-Cookie").firstOrNull { it.startsWith("XSRF-TOKEN=") }
                ?.substringAfter("=")?.substringBefore(";").orEmpty()
            config.close()
            val request = Request.Builder().url("$base/api/v1/auth/register").post(body)
                .header("Cookie", "XSRF-TOKEN=$token").header("X-XSRF-TOKEN", token).build()
            return client.newCall(request).execute().use { it.code }
        }
        assertEquals(202, register())
        assertEquals(400, register())
    }
}

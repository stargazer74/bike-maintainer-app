package de.bikemaintainer.app.domain

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.TimeSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Solves ALTCHA proof-of-work challenges (protocol v2, as produced by `GET /api/v1/auth/challenge`) natively, without a
 * WebView or a third-party library – the same work the ALTCHA widget does in the browser of the web app.
 *
 * The password of attempt `n` is the nonce followed by `n` as a big-endian 32-bit integer; it is derived with
 * PBKDF2 (salt, `cost` iterations, `keyLength` bytes) until the key starts with `keyPrefix`. The result is the
 * base64 payload the backend expects in the `altcha` field of registration and resend requests.
 *
 * The challenge is kept verbatim: its `parameters` and `signature` go into the payload exactly as the server sent them,
 * because the signature covers them (the API spec warns against re-serialising).
 */
object AltchaSolver {
    /** The widget's limit for the counter in its default mode. */
    private const val MAX_COUNTER = 0xFFFF_FFFFL
    private const val DEFAULT_KEY_LENGTH = 32
    private const val COUNTER_BYTES = 4
    private const val BYTE_MASK = 0xFF
    private const val HEX_RADIX = 16
    private const val BITS_PER_BYTE = 8

    private val hashes = mapOf(
        "PBKDF2/SHA-256" to "HmacSHA256",
        "PBKDF2/SHA-384" to "HmacSHA384",
        "PBKDF2/SHA-512" to "HmacSHA512",
    )

    /** The challenge cannot be solved: not JSON, missing or invalid fields, unsupported algorithm. */
    class InvalidChallengeException(message: String) : IllegalArgumentException(message)

    /**
     * Solves [challengeJson] (the body of `GET /auth/challenge`, unchanged) and returns the base64 payload.
     * Cancellable: checks for cancellation between attempts; run it on a background dispatcher.
     *
     * @throws InvalidChallengeException if the challenge is malformed or uses an unsupported algorithm.
     */
    suspend fun solve(challengeJson: String): String {
        val challenge = parse(challengeJson)
        val start = TimeSource.Monotonic.markNow()
        val mac = Mac.getInstance(challenge.macAlgorithm)
        val password = challenge.nonce.copyOf(challenge.nonce.size + COUNTER_BYTES)
        var counter = 0L
        while (counter <= MAX_COUNTER) {
            currentCoroutineContext().ensureActive()
            writeCounter(password, challenge.nonce.size, counter)
            val key = pbkdf2(mac, password, challenge.salt, challenge.cost, challenge.keyLength)
            if (toHex(key).startsWith(challenge.keyPrefix)) {
                return payload(challenge, counter, toHex(key), start.elapsedNow().inWholeMilliseconds)
            }
            counter++
        }
        throw InvalidChallengeException("No solution within the counter range")
    }

    private class Challenge(
        val parameters: JsonObject,
        val signature: JsonPrimitive,
        val macAlgorithm: String,
        val nonce: ByteArray,
        val salt: ByteArray,
        val cost: Int,
        val keyLength: Int,
        val keyPrefix: String,
    )

    private fun parse(json: String): Challenge {
        val root = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: SerializationException) {
            throw InvalidChallengeException("Not JSON: ${e.message}")
        } catch (_: IllegalArgumentException) {
            throw InvalidChallengeException("Not a JSON object")
        }
        try {
            val parameters = root.getValue("parameters").jsonObject
            val signature = root.getValue("signature").jsonPrimitive
            fun text(name: String) = parameters[name]?.jsonPrimitive?.contentOrNull
                ?: throw InvalidChallengeException("Missing parameter $name")
            val algorithm = text("algorithm")
            val macAlgorithm = hashes[algorithm] ?: throw InvalidChallengeException("Unsupported algorithm $algorithm")
            val cost = parameters["cost"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                ?: throw InvalidChallengeException("Invalid cost")
            val keyLength = parameters["keyLength"]?.jsonPrimitive?.intOrNull ?: DEFAULT_KEY_LENGTH
            val keyPrefix = text("keyPrefix").lowercase()
            if (keyLength <= 0 || keyPrefix.isEmpty() || !keyPrefix.all { it in "0123456789abcdef" } ||
                keyPrefix.length > keyLength * 2
            ) {
                throw InvalidChallengeException("Invalid keyPrefix or keyLength")
            }
            return Challenge(
                parameters = parameters,
                signature = signature,
                macAlgorithm = macAlgorithm,
                nonce = fromHex(text("nonce")),
                salt = fromHex(text("salt")),
                cost = cost,
                keyLength = keyLength,
                keyPrefix = keyPrefix,
            )
        } catch (_: NoSuchElementException) {
            throw InvalidChallengeException("Missing parameters or signature")
        } catch (e: IllegalArgumentException) {
            if (e is InvalidChallengeException) throw e
            throw InvalidChallengeException("Malformed challenge: ${e.message}")
        }
    }

    /** Base64 of the JSON the ALTCHA widget sends: the challenge as received plus the solution. */
    private fun payload(challenge: Challenge, counter: Long, derivedKey: String, millis: Long): String {
        val json = buildJsonObject {
            put(
                "challenge",
                buildJsonObject {
                    put("parameters", challenge.parameters)
                    put("signature", challenge.signature)
                },
            )
            put(
                "solution",
                buildJsonObject {
                    put("counter", counter)
                    put("derivedKey", derivedKey)
                    put("time", millis)
                },
            )
        }
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeCounter(buffer: ByteArray, offset: Int, counter: Long) {
        for (i in 0 until COUNTER_BYTES) {
            buffer[offset + i] = (counter shr ((COUNTER_BYTES - 1 - i) * BITS_PER_BYTE) and BYTE_MASK.toLong()).toByte()
        }
    }

    /**
     * PBKDF2 (RFC 8018) with an HMAC on raw password bytes. `javax.crypto`'s PBKDF2 key factories take the password as
     * characters and encode them, which would change a binary nonce; hence computed directly.
     */
    internal fun pbkdf2(mac: Mac, password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        mac.init(SecretKeySpec(password, mac.algorithm))
        val hashLength = mac.macLength
        val result = ByteArray(keyLength)
        var block = 1
        var offset = 0
        while (offset < keyLength) {
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (block ushr 24).toByte(),
                    (block ushr 16).toByte(),
                    (block ushr BITS_PER_BYTE).toByte(),
                    block.toByte(),
                ),
            )
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val length = minOf(hashLength, keyLength - offset)
            t.copyInto(result, offset, 0, length)
            offset += length
            block++
        }
        return result
    }

    private fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toIntOrNull(HEX_RADIX)?.toByte()
                ?: throw IllegalArgumentException("Not hex: $hex")
        }
    }

    private fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and BYTE_MASK
            append("0123456789abcdef"[v ushr 4])
            append("0123456789abcdef"[v and 0x0F])
        }
    }
}

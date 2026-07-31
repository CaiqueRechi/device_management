package br.com.rechi.mobile.security

import android.util.Base64
import br.com.rechi.mobile.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

data class ValidatedConfigurationToken(
    val deviceId: String,
    val firstConnectionDate: String,
    val url: String,
    val tokenId: String
)

sealed class JwtValidationResult {
    data class Valid(val token: ValidatedConfigurationToken) : JwtValidationResult()
    data class Invalid(val reason: String) : JwtValidationResult()
}

class JwtValidator(private val clock: Clock = Clock.systemUTC()) {
    fun validate(
        compactJwt: String,
        expectedDeviceId: String,
        storedFirstConnectionDate: String?
    ): JwtValidationResult {
        return runCatching {
            val parts = compactJwt.trim().split('.')
            require(parts.size == 3) { "Malformed JWT" }

            val header = JSONObject(decodeText(parts[0]))
            require(header.optString("alg") == ALGORITHM) { "Unexpected JWT algorithm" }
            require(header.optString("typ", "JWT") == "JWT") { "Unexpected token type" }
            require(BuildConfig.SERVER_JWT_PUBLIC_KEY_BASE64.isNotBlank()) {
                "Server JWT public key is not configured"
            }

            verifySignature("${parts[0]}.${parts[1]}", parts[2])
            val claims = JSONObject(decodeText(parts[1]))
            validateRegisteredClaims(claims, expectedDeviceId)

            val firstConnectionDate = claims.requiredString("firstConnectionDate")
            val parsedDate = LocalDate.parse(firstConnectionDate)
            if (storedFirstConnectionDate == null) {
                require(parsedDate == LocalDate.now(clock)) {
                    "First connection date is not today"
                }
            } else {
                require(firstConnectionDate == storedFirstConnectionDate) {
                    "First connection date does not match device binding"
                }
            }

            JwtValidationResult.Valid(
                ValidatedConfigurationToken(
                    deviceId = expectedDeviceId,
                    firstConnectionDate = firstConnectionDate,
                    url = claims.requiredString("url"),
                    tokenId = claims.requiredString("jti")
                )
            )
        }.getOrElse {
            JwtValidationResult.Invalid(it.message ?: "JWT validation failed")
        }
    }

    private fun validateRegisteredClaims(claims: JSONObject, expectedDeviceId: String) {
        val now = Instant.now(clock).epochSecond
        val issuer = claims.requiredString("iss")
        require(issuer == BuildConfig.SERVER_JWT_ISSUER) { "Invalid issuer" }
        require(hasAudience(claims.get("aud"), BuildConfig.SERVER_JWT_AUDIENCE)) {
            "Invalid audience"
        }
        require(claims.requiredString("sub") == expectedDeviceId) { "Invalid subject" }

        val expiresAt = claims.getLong("exp")
        require(expiresAt > now - CLOCK_SKEW_SECONDS) { "JWT is expired" }
        if (claims.has("nbf")) {
            require(now + CLOCK_SKEW_SECONDS >= claims.getLong("nbf")) { "JWT is not active" }
        }
        if (claims.has("iat")) {
            require(claims.getLong("iat") <= now + CLOCK_SKEW_SECONDS) {
                "JWT was issued in the future"
            }
        }
    }

    private fun verifySignature(signingInput: String, encodedSignature: String) {
        val publicKeyBytes = Base64.decode(
            BuildConfig.SERVER_JWT_PUBLIC_KEY_BASE64.replace("\\s".toRegex(), ""),
            Base64.DEFAULT
        )
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes)) as RSAPublicKey
        require(publicKey.modulus.bitLength() >= MIN_RSA_BITS) { "RSA key is too small" }
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(signingInput.toByteArray(Charsets.US_ASCII))
        require(verifier.verify(decodeBytes(encodedSignature))) { "Invalid JWT signature" }
    }

    private fun hasAudience(value: Any, expected: String): Boolean = when (value) {
        is String -> value == expected
        is JSONArray -> (0 until value.length()).any { value.optString(it) == expected }
        else -> false
    }

    private fun decodeText(value: String) = String(decodeBytes(value), Charsets.UTF_8)

    private fun decodeBytes(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun JSONObject.requiredString(name: String): String =
        getString(name).trim().also { require(it.isNotEmpty()) { "Missing claim: $name" } }

    private companion object {
        const val ALGORITHM = "RS256"
        const val CLOCK_SKEW_SECONDS = 60L
        const val MIN_RSA_BITS = 2048
    }
}

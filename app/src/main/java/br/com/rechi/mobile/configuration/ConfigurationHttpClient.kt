package br.com.rechi.mobile.configuration

import br.com.rechi.mobile.BuildConfig
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ConfigurationHttpClient {
    fun fetch(deviceId: String): RemoteTokenResult {
        val baseUrl = BuildConfig.CONFIGURATION_API_BASE_URL.trimEnd('/') + "/"
        val baseUri = runCatching { URI(baseUrl) }.getOrElse {
            return RemoteTokenResult.Failure("Invalid configuration API URL")
        }
        if (
            baseUri.scheme != "https" ||
            baseUri.host.isNullOrBlank() ||
            baseUri.userInfo != null ||
            baseUri.query != null ||
            baseUri.fragment != null
        ) {
            return RemoteTokenResult.Failure("Configuration API must use HTTPS")
        }
        if (baseUri.host == "example.invalid") {
            return RemoteTokenResult.Failure("Configuration API URL is not configured")
        }

        val encodedId = URLEncoder.encode(deviceId, StandardCharsets.UTF_8.name())
        var connection: HttpURLConnection? = null

        return try {
            val activeConnection = baseUri.resolve("api/v1/devices/$encodedId/configuration")
                .toURL()
                .openConnection() as HttpURLConnection
            connection = activeConnection
            activeConnection.requestMethod = "GET"
            activeConnection.connectTimeout = 10_000
            activeConnection.readTimeout = 15_000
            activeConnection.instanceFollowRedirects = false
            activeConnection.setRequestProperty("Accept", "application/jwt")

            if (activeConnection.responseCode !in 200..299) {
                RemoteTokenResult.Failure("HTTP ${activeConnection.responseCode}")
            } else {
                if (activeConnection.contentLengthLong > MAX_RESPONSE_BYTES) {
                    return RemoteTokenResult.Failure("JWT response is too large")
                }
                val body = activeConnection.inputStream.bufferedReader().use { reader ->
                    val result = StringBuilder()
                    val buffer = CharArray(2048)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        result.append(buffer, 0, count)
                        require(result.length <= MAX_RESPONSE_BYTES) { "JWT response is too large" }
                    }
                    result.toString()
                }
                if (body.count { it == '.' } != 2) {
                    RemoteTokenResult.Failure("Response is not a compact JWT")
                } else {
                    RemoteTokenResult.Success(body.trim())
                }
            }
        } catch (error: Exception) {
            RemoteTokenResult.Failure(error.message ?: error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}

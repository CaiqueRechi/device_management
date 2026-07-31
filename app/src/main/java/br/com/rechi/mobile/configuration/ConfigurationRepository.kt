package br.com.rechi.mobile.configuration

import android.net.Uri
import br.com.rechi.mobile.security.JwtValidationResult
import br.com.rechi.mobile.security.JwtValidator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ConfigurationRepository(
    private val storage: ConfigurationStorage,
    private val client: ConfigurationHttpClient = ConfigurationHttpClient(),
    private val jwtValidator: JwtValidator = JwtValidator()
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun cachedUrl(): String? {
        if (storage.firstConnectionDate() == null) return null
        return storage.activeUrl()?.takeIf(::isAllowedWebUrl)
    }

    fun refresh(callback: (ConfigurationResult) -> Unit) {
        executor.execute {
            val deviceId = storage.deviceId()
            val remoteResult = client.fetch(deviceId)
            val validatedResult = when (remoteResult) {
                is RemoteTokenResult.Success -> {
                    when (
                        val jwtResult = jwtValidator.validate(
                            remoteResult.compactJwt,
                            deviceId,
                            storage.firstConnectionDate()
                        )
                    ) {
                        is JwtValidationResult.Valid -> {
                            if (!isAllowedWebUrl(jwtResult.token.url)) {
                                ConfigurationResult.Failure("Invalid web URL")
                            } else {
                                storage.saveValidatedConfiguration(
                                    jwtResult.token.url,
                                    jwtResult.token.firstConnectionDate,
                                    jwtResult.token.tokenId
                                )
                                ConfigurationResult.Success(
                                    DeviceConfiguration(jwtResult.token.url)
                                )
                            }
                        }
                        is JwtValidationResult.Invalid ->
                            ConfigurationResult.Failure(jwtResult.reason)
                    }
                }
                is RemoteTokenResult.Failure -> ConfigurationResult.Failure(remoteResult.reason)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(validatedResult)
            }
        }
    }

    fun close() = executor.shutdownNow()

    private fun isAllowedWebUrl(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo.isNullOrBlank()
    }
}

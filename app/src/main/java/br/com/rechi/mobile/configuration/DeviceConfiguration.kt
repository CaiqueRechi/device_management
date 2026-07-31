package br.com.rechi.mobile.configuration

data class DeviceConfiguration(val url: String)

sealed class ConfigurationResult {
    data class Success(val configuration: DeviceConfiguration) : ConfigurationResult()
    data class Failure(val reason: String) : ConfigurationResult()
}

sealed class RemoteTokenResult {
    data class Success(val compactJwt: String) : RemoteTokenResult()
    data class Failure(val reason: String) : RemoteTokenResult()
}

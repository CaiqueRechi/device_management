package br.com.rechi.mobile.configuration

import android.content.Context
import br.com.rechi.mobile.security.EncryptedPreferences
import java.util.UUID

class ConfigurationStorage(context: Context) {
    private val preferences = EncryptedPreferences(context)

    init {
        val legacy = context.getSharedPreferences("rechi_device_configuration", Context.MODE_PRIVATE)
        if (preferences.getString(KEY_DEVICE_ID) == null) {
            legacy.getString(KEY_DEVICE_ID, null)?.let {
                preferences.putString(KEY_DEVICE_ID, it)
            }
        }
        legacy.edit().clear().apply()
    }

    fun deviceId(): String {
        preferences.getString(KEY_DEVICE_ID)?.let { return it }
        return UUID.randomUUID().toString().also {
            preferences.putString(KEY_DEVICE_ID, it)
        }
    }

    fun activeUrl(): String? = preferences.getString(KEY_ACTIVE_URL)
    fun firstConnectionDate(): String? = preferences.getString(KEY_FIRST_CONNECTION_DATE)
    fun lastTokenId(): String? = preferences.getString(KEY_LAST_TOKEN_ID)

    fun saveValidatedConfiguration(url: String, firstConnectionDate: String, tokenId: String) {
        preferences.putString(KEY_ACTIVE_URL, url)
        preferences.putString(KEY_FIRST_CONNECTION_DATE, firstConnectionDate)
        preferences.putString(KEY_LAST_TOKEN_ID, tokenId)
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ACTIVE_URL = "active_url"
        const val KEY_FIRST_CONNECTION_DATE = "first_connection_date"
        const val KEY_LAST_TOKEN_ID = "last_token_id"
    }
}

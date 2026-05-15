package com.example.test.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePreferences(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveToken(token: String?) {
        if (token != null) {
            prefs.edit().putString(KEY_JWT, token).apply()
        }
    }

    fun getToken(): String? = prefs.getString(KEY_JWT, null)

    fun saveRefreshToken(refreshToken: String?) {
        if (refreshToken != null) {
            prefs.edit().putString(KEY_REFRESH_JWT, refreshToken).apply()
        }
    }

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_JWT, null)

    fun clearToken() {
        prefs.edit().remove(KEY_JWT).apply()
    }

    fun clearAll() {
        prefs.edit().remove(KEY_JWT).remove(KEY_REFRESH_JWT).apply()
    }

    fun saveBackendUrl(url: String) {
        prefs.edit().putString(KEY_BACKEND_URL, url).apply()
    }

    fun getBackendUrl(): String = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL

    fun saveOfflineMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OFFLINE_MODE, enabled).apply()
    }

    fun isOfflineMode(): Boolean = prefs.getBoolean(KEY_OFFLINE_MODE, true)

    fun savePrinterAddress(address: String) {
        prefs.edit().putString(KEY_PRINTER_ADDRESS, address.trim()).apply()
    }

    fun getPrinterAddress(): String? = prefs.getString(KEY_PRINTER_ADDRESS, null)?.takeIf { it.isNotBlank() }

    fun savePrinterName(name: String) {
        prefs.edit().putString(KEY_PRINTER_NAME, name.trim()).apply()
    }

    fun getPrinterName(): String? = prefs.getString(KEY_PRINTER_NAME, null)?.takeIf { it.isNotBlank() }

    companion object {
        private const val PREFS_NAME = "secure_prefs"
        private const val KEY_JWT = "jwt_token"
        private const val KEY_REFRESH_JWT = "refresh_jwt_token"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_OFFLINE_MODE = "offline_mode"
        private const val KEY_PRINTER_ADDRESS = "printer_bt_address"
        private const val KEY_PRINTER_NAME = "printer_bt_name"
        private const val DEFAULT_BACKEND_URL = "http://10.0.2.2:3000"
    }
}

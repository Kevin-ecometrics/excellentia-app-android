package com.example.test.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException

class SecurePreferences(context: Context) {

    private val prefs: SharedPreferences by lazy {
        createEncryptedPrefs(context)
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            // Keystore key no coincide con el archivo cifrado (p.ej. tras backup/restore
            // o reinstalación que dejó el archivo pero invalidó la llave). Se descarta el
            // archivo corrupto y se recrea uno nuevo para que la app pueda arrancar.
            Log.w("SecurePreferences", "EncryptedSharedPreferences corrupta, recreando", e)
            context.deleteSharedPreferences(PREFS_NAME)
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
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
        prefs.edit().remove(KEY_JWT).remove(KEY_REFRESH_JWT).remove(KEY_ACTIVE_CUSTOMER_ID).remove(KEY_ACTIVE_CUSTOMER_NAME).apply()
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

    fun setActiveCustomer(id: String, name: String, address: String? = null) {
        prefs.edit()
            .putString(KEY_ACTIVE_CUSTOMER_ID, id)
            .putString(KEY_ACTIVE_CUSTOMER_NAME, name)
            .putString(KEY_ACTIVE_CUSTOMER_ADDRESS, address)
            .apply()
    }

    fun getActiveCustomerId(): String? = prefs.getString(KEY_ACTIVE_CUSTOMER_ID, null)?.takeIf { it.isNotBlank() }

    fun getActiveCustomerName(): String? = prefs.getString(KEY_ACTIVE_CUSTOMER_NAME, null)?.takeIf { it.isNotBlank() }

    fun getActiveCustomerAddress(): String? = prefs.getString(KEY_ACTIVE_CUSTOMER_ADDRESS, null)?.takeIf { it.isNotBlank() }

    fun clearActiveCustomer() {
        prefs.edit()
            .remove(KEY_ACTIVE_CUSTOMER_ID)
            .remove(KEY_ACTIVE_CUSTOMER_NAME)
            .remove(KEY_ACTIVE_CUSTOMER_ADDRESS)
            .apply()
    }

    // Contexto de "vender desde una parada" (Módulo Almacén) — sobrevive la
    // navegación MyRouteDetailActivity → MainActivity → ... → CurrentOrderActivity
    // (no es un solo salto con Activity result, son varias pantallas
    // conectadas por el bottom nav) para poder marcar la parada como
    // entregada automáticamente recién cuando el pedido se manda de verdad,
    // no antes. Se limpia apenas se consume (ver CurrentOrderActivity).
    fun saveActiveRouteStop(routeId: Int, stopId: Int) {
        prefs.edit().putInt(KEY_ACTIVE_ROUTE_ID, routeId).putInt(KEY_ACTIVE_STOP_ID, stopId).apply()
    }
    fun getActiveRouteId(): Int? = prefs.getInt(KEY_ACTIVE_ROUTE_ID, -1).takeIf { it != -1 }
    fun getActiveStopId(): Int? = prefs.getInt(KEY_ACTIVE_STOP_ID, -1).takeIf { it != -1 }
    fun clearActiveRouteStop() {
        prefs.edit().remove(KEY_ACTIVE_ROUTE_ID).remove(KEY_ACTIVE_STOP_ID).apply()
    }

    fun saveCompanySettings(name: String, subtitle: String, address: String?, phone: String?, city: String?) {
        prefs.edit()
            .putString(KEY_COMPANY_NAME, name)
            .putString(KEY_COMPANY_SUBTITLE, subtitle)
            .putString(KEY_COMPANY_ADDRESS, address)
            .putString(KEY_COMPANY_PHONE, phone)
            .putString(KEY_COMPANY_CITY, city)
            .apply()
    }

    fun saveUserInfo(email: String, name: String?, role: String?, id: Int? = null) {
        prefs.edit()
            .putString(KEY_USER_EMAIL, email)
            .putString(KEY_USER_NAME, name)
            .putString(KEY_USER_ROLE, role)
            .apply()
        if (id != null) prefs.edit().putInt(KEY_USER_ID, id).apply()
    }
    fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)
    fun getUserName(): String? = prefs.getString(KEY_USER_NAME, null)?.takeIf { it.isNotBlank() }
    fun getUserRole(): String? = prefs.getString(KEY_USER_ROLE, null)
    // Necesario para "mis rutas" (Módulo Almacén) — filtrar/validar que una
    // ruta es del repartidor logueado. -1 si nunca se guardó (login viejo,
    // antes de este campo) — sin id no se puede filtrar por driver_user_id.
    fun getUserId(): Int? = prefs.getInt(KEY_USER_ID, -1).takeIf { it != -1 }

    fun saveLastScan(barcode: String, productName: String, timestamp: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_LAST_SCAN_BARCODE, barcode)
            .putString(KEY_LAST_SCAN_NAME, productName)
            .putLong(KEY_LAST_SCAN_TIME, timestamp)
            .apply()
    }
    fun getLastScanBarcode(): String? = prefs.getString(KEY_LAST_SCAN_BARCODE, null)
    fun getLastScanName(): String? = prefs.getString(KEY_LAST_SCAN_NAME, null)
    fun getLastScanTime(): Long = prefs.getLong(KEY_LAST_SCAN_TIME, 0L)

    fun saveLanguage(lang: String) = prefs.edit().putString(KEY_LANGUAGE, lang).apply()
    fun getLanguage(): String = prefs.getString(KEY_LANGUAGE, "en") ?: "en"

    fun getCompanyName(): String = prefs.getString(KEY_COMPANY_NAME, "EXCELLENTIA") ?: "EXCELLENTIA"
    fun getCompanySubtitle(): String = prefs.getString(KEY_COMPANY_SUBTITLE, "Ticket de Venta") ?: "Ticket de Venta"
    fun getCompanyAddress(): String? = prefs.getString(KEY_COMPANY_ADDRESS, null)?.takeIf { it.isNotBlank() }
    fun getCompanyPhone(): String? = prefs.getString(KEY_COMPANY_PHONE, null)?.takeIf { it.isNotBlank() }
    fun getCompanyCity(): String? = prefs.getString(KEY_COMPANY_CITY, null)?.takeIf { it.isNotBlank() }

    companion object {
        private const val PREFS_NAME = "secure_prefs"
        private const val KEY_JWT = "jwt_token"
        private const val KEY_REFRESH_JWT = "refresh_jwt_token"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_OFFLINE_MODE = "offline_mode"
        private const val KEY_PRINTER_ADDRESS = "printer_bt_address"
        private const val KEY_PRINTER_NAME = "printer_bt_name"
        private const val KEY_ACTIVE_CUSTOMER_ID = "active_customer_id"
        private const val KEY_ACTIVE_CUSTOMER_NAME = "active_customer_name"
        private const val KEY_ACTIVE_CUSTOMER_ADDRESS = "active_customer_address"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME  = "user_name"
        private const val KEY_USER_ROLE  = "user_role"
        private const val KEY_USER_ID    = "user_id"
        private const val KEY_ACTIVE_ROUTE_ID = "active_route_id"
        private const val KEY_ACTIVE_STOP_ID  = "active_stop_id"
        private const val KEY_LAST_SCAN_BARCODE = "last_scan_barcode"
        private const val KEY_LAST_SCAN_NAME = "last_scan_name"
        private const val KEY_LAST_SCAN_TIME = "last_scan_time"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_COMPANY_NAME = "company_name"
        private const val KEY_COMPANY_SUBTITLE = "company_subtitle"
        private const val KEY_COMPANY_ADDRESS = "company_address"
        private const val KEY_COMPANY_PHONE = "company_phone"
        private const val KEY_COMPANY_CITY = "company_city"
        private const val DEFAULT_BACKEND_URL = "https://app.excellentiafoods.com"
    }
}

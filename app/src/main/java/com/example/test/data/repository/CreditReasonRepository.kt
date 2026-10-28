package com.example.test.data.repository

import android.content.Context
import com.example.test.data.CreditReasonDto
import com.example.test.data.network.RetrofitClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Motivos de crédito (2026-10-26) — la lista la arma el admin en Settings de la
// webapp (GET /api/credits/reasons, solo activos). Se guarda la última lista
// recibida en SharedPreferences (JSON) para poder elegir el motivo sin
// conexión; no hace falta una tabla SQLite para una lista corta.
class CreditReasonRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun cached(): List<CreditReasonDto> {
        val json = prefs.getString(KEY_REASONS, null) ?: return emptyList()
        return try {
            Gson().fromJson<List<CreditReasonDto>>(json, object : TypeToken<List<CreditReasonDto>>() {}.type)
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // null si no hubo respuesta (sin conexión / error) — la caché queda como estaba.
    suspend fun refresh(timeoutMs: Long = 5_000): List<CreditReasonDto>? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            try {
                val resp = RetrofitClient.getApi().getCreditReasons()
                if (!resp.isSuccessful) return@withTimeoutOrNull null
                val list = resp.body()?.data?.filter { it.isActive }?.sortedBy { it.sortOrder } ?: emptyList()
                prefs.edit().putString(KEY_REASONS, Gson().toJson(list)).apply()
                list
            } catch (_: Exception) {
                null
            }
        }
    }

    // Lista para el selector: la del servidor si responde, si no la última guardada.
    suspend fun load(): List<CreditReasonDto> = refresh() ?: cached()

    private companion object {
        const val PREFS_NAME = "credit_reasons_cache"
        const val KEY_REASONS = "reasons_json"
    }
}

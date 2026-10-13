package com.example.test.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import com.example.test.data.local.NotificationHelper
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import java.util.concurrent.TimeUnit

// Solo almacenista: avisa cuando una pre-orden pasa a CONFIRMED y cuando una ya
// CONFIRMED se modifica. Mismo patrón que OrderStatusWorker (polling cada 2 min,
// notificación local) — el backend no tiene push. La primera corrida solo toma una
// foto de lo que ya está confirmado, para no inundar de avisos al iniciar sesión.
class PreOrderStatusWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            // Si ya no es almacenista (cerró sesión / cambió de usuario) el ciclo termina.
            if (SecurePreferences(applicationContext).getUserRole() != "almacenista") return Result.success()

            val api = try {
                RetrofitClient.getApi()
            } catch (_: IllegalStateException) {
                scheduleNext(applicationContext)
                return Result.success()
            }

            val response = api.listPreOrders(status = "CONFIRMED", page = 1, limit = 100)
            if (!response.isSuccessful) {
                scheduleNext(applicationContext)
                return Result.success()
            }
            val confirmed = response.body()?.data ?: emptyList()

            val prefs = getPrefs(applicationContext)
            val firstRun = !prefs.getBoolean(KEY_INITIALIZED, false)
            // "id|editedAt" por pre-orden ya vista.
            val seen = HashMap<Int, String>()
            for (entry in prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()) {
                val parts = entry.split("|", limit = 2)
                val id = parts.getOrNull(0)?.toIntOrNull() ?: continue
                seen[id] = parts.getOrNull(1) ?: ""
            }

            val next = mutableSetOf<String>()
            for (po in confirmed) {
                val edited = po.confirmedEditedAt ?: ""
                next.add("${po.id}|$edited")
                if (firstRun) continue
                val prevEdited = seen[po.id]
                when {
                    prevEdited == null ->
                        NotificationHelper.showPreOrderConfirmed(applicationContext, po.id, po.customerName)
                    prevEdited != edited ->
                        NotificationHelper.showPreOrderEdited(applicationContext, po.id, po.customerName)
                }
            }

            prefs.edit()
                .putBoolean(KEY_INITIALIZED, true)
                .putStringSet(KEY_SEEN, next)
                .apply()
            scheduleNext(applicationContext)
        } catch (_: Exception) {
            scheduleNext(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "preorder_status_poller"
        private const val PREFS_NAME = "preorder_status_prefs"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_SEEN = "seen_confirmed"
        private const val POLL_INTERVAL_MINUTES = 2L

        private fun getPrefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private fun request(delayMinutes: Long) = OneTimeWorkRequestBuilder<PreOrderStatusWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        private fun scheduleNext(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request(POLL_INTERVAL_MINUTES))
        }

        // KEEP: abrir WarehouseActivity varias veces no reinicia el temporizador.
        fun enqueue(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request(0))
        }
    }
}

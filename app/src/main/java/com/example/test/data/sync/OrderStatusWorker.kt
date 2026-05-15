package com.example.test.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import com.example.test.data.local.NotificationHelper
import com.example.test.data.network.RetrofitClient
import java.util.concurrent.TimeUnit

class OrderStatusWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val api = try {
                RetrofitClient.getApi()
            } catch (_: IllegalStateException) {
                scheduleNext(applicationContext)
                return Result.success()
            }

            val response = api.listOrders(status = "SENT", page = 1, limit = 50)
            if (!response.isSuccessful) {
                scheduleNext(applicationContext)
                return Result.success()
            }

            val body = response.body()
            val orders = body?.data ?: emptyList()
            if (orders.isEmpty()) {
                scheduleNext(applicationContext)
                return Result.success()
            }

            val prefs = getPrefs(applicationContext)
            val lastNotifiedId = prefs.getInt(KEY_LAST_NOTIFIED, 0)

            var maxId = lastNotifiedId
            for (order in orders) {
                if (order.id > lastNotifiedId) {
                    NotificationHelper.showOrderSynced(
                        context = applicationContext,
                        orderId = order.id,
                        productName = order.productName,
                        quantity = order.quantity,
                        total = order.total
                    )
                }
                if (order.id > maxId) maxId = order.id
            }

            prefs.edit().putInt(KEY_LAST_NOTIFIED, maxId).apply()
            scheduleNext(applicationContext)
            Result.success()
        } catch (_: Exception) {
            scheduleNext(applicationContext)
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "order_status_poller"
        private const val PREFS_NAME = "order_status_prefs"
        private const val KEY_LAST_NOTIFIED = "last_notified_order_id"
        private const val POLL_INTERVAL_MINUTES = 2L

        private fun getPrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        private fun scheduleNext(context: Context) {
            val request = OneTimeWorkRequestBuilder<OrderStatusWorker>()
                .setInitialDelay(POLL_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OrderStatusWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

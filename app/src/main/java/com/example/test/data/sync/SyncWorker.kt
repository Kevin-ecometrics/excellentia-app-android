package com.example.test.data.sync

import android.content.Context
import androidx.work.*
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.dao.OrderDao
import com.example.test.data.network.RetrofitClient
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getInstance(applicationContext)
            val orderDao = OrderDao(db)
            val pending = orderDao.getAllPending()

            if (pending.isEmpty()) return Result.success()

            var successCount = 0
            for (order in pending) {
                try {
                    val response = RetrofitClient.getApi().createOrder(
                        com.example.test.data.CreateOrderRequest(
                            barcode = order.barcode,
                            productName = order.productName,
                            price = order.price,
                            quantity = order.quantity,
                            total = order.price * order.quantity,
                            deviceId = order.deviceId
                        )
                    )
                    if (response.isSuccessful) {
                        orderDao.deleteById(order.id)
                        successCount++
                    } else if (order.retryCount >= 3) {
                        orderDao.deleteById(order.id)
                    } else {
                        orderDao.incrementRetry(order.id)
                    }
                } catch (_: Exception) {
                    if (order.retryCount >= 3) {
                        orderDao.deleteById(order.id)
                    } else {
                        orderDao.incrementRetry(order.id)
                    }
                }
            }

            if (successCount > 0) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "sync_pending_orders"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }
    }
}

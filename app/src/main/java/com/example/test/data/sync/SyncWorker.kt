package com.example.test.data.sync

import android.content.Context
import androidx.work.*
import com.example.test.data.BatchRequest
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.dao.PendingBatchDao
import com.example.test.data.network.RetrofitClient
import com.google.gson.Gson
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val gson = Gson()

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getInstance(applicationContext)
            var anythingSynced = false

            // NOTA: pending_orders es el carrito local (ProductDetailActivity.savePendingOrder
            // inserta ahí directamente en cada escaneo). No se sincroniza individualmente aquí:
            // solo debe salir de esa tabla cuando el usuario finaliza el pedido
            // (CurrentOrderActivity.finalizeOrder -> sendBatch -> clearPending) o lo borra a mano.
            // Antes este worker reenviaba cada fila via el endpoint legacy createOrder() y la
            // borraba al tener éxito, lo que vaciaba el carrito en segundo plano (incluso con la
            // app cerrada) sin que el usuario llegara a elegir cliente/firma/pago.

            // ── Enviar pending_batches offline ──
            val batchDao = PendingBatchDao(db)
            val pendingBatches = batchDao.getAll()
            for (batch in pendingBatches) {
                try {
                    val request = gson.fromJson(batch.batchJson, BatchRequest::class.java)
                    val response = RetrofitClient.getApi().createBatch(request)
                    if (response.isSuccessful && response.body() != null) {
                        batchDao.deleteById(batch.id)
                        anythingSynced = true
                        // Notificar al usuario que el pedido se sincronizó
                        val batchResponse = response.body()!!
                        val firstItem = request.items.firstOrNull()
                        if (firstItem != null) {
                            com.example.test.data.local.NotificationHelper.showOrderSynced(
                                context     = applicationContext,
                                orderId     = batchResponse.batchId.hashCode(),
                                productName = firstItem.productName,
                                quantity    = request.items.sumOf { it.quantity },
                                total       = request.items.sumOf { it.total }
                            )
                        }
                    }
                } catch (_: Exception) {
                    // Sin conexión — se reintenta en el próximo ciclo
                }
            }

            if (anythingSynced) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "sync_pending_orders"
        private const val WORK_NAME_ONE_TIME = "sync_pending_orders_immediate"

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

        fun enqueueOneTime(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME_ONE_TIME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }
}

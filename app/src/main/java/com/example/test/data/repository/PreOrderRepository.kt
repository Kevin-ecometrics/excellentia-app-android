package com.example.test.data.repository

import com.example.test.data.ConvertPreOrderRequest
import com.example.test.data.ConvertPreOrderResponse
import com.example.test.data.PreOrderRequest
import com.example.test.data.PreOrderResponse
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.dao.PendingPreOrderConversionDao
import com.example.test.data.local.dao.PendingPreOrderDao
import com.example.test.data.local.entities.PendingPreOrderConversionEntity
import com.example.test.data.local.entities.PendingPreOrderEntity
import com.example.test.data.network.RetrofitClient
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Soporte offline para pre-órdenes — espejo de
// OrderRepository.sendBatch()/saveOfflineBatch()/attachPaymentMethodOffline().
// Solo crear y convertir tienen variante offline: son las dos operaciones que
// de verdad pueden pasar sin señal (armar la pre-orden en el depósito, cerrar
// la venta en la ruta). Confirmar/cancelar y el resto de la app ya fallan de
// forma silenciosa con un Snackbar y son reintentables a mano por el usuario
// sin perder ningún dato — no valía la pena encolarlos también.
class PreOrderRepository(private val db: AppDatabase) {
    private val gson = Gson()

    suspend fun createPreOrder(request: PreOrderRequest): Result<PreOrderResponse> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().createPreOrder(request)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Error del servidor: ${response.code()}"))
            }
        } catch (e: Exception) {
            saveOfflinePreOrder(request)
        }
    }

    private fun saveOfflinePreOrder(request: PreOrderRequest): Result<PreOrderResponse> {
        return try {
            val json = gson.toJson(request)
            val localId = PendingPreOrderDao(db).insert(PendingPreOrderEntity(requestJson = json))
            Result.success(PreOrderResponse(id = 0, status = "OFFLINE_PENDING", localPendingId = localId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun convertPreOrder(preOrderId: Int, request: ConvertPreOrderRequest): Result<ConvertPreOrderResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = RetrofitClient.getApi().convertPreOrder(preOrderId, request)
                if (response.isSuccessful && response.body() != null) {
                    Result.success(response.body()!!)
                } else {
                    Result.failure(Exception("Error del servidor: ${response.code()}"))
                }
            } catch (e: Exception) {
                saveOfflineConversion(preOrderId, request)
            }
        }

    private fun saveOfflineConversion(preOrderId: Int, request: ConvertPreOrderRequest): Result<ConvertPreOrderResponse> {
        return try {
            val json = gson.toJson(request)
            val localId = PendingPreOrderConversionDao(db).insert(
                PendingPreOrderConversionEntity(preOrderId = preOrderId, requestJson = json)
            )
            Result.success(
                ConvertPreOrderResponse(
                    batchId = "OFFLINE_PENDING",
                    invoiceId = null,
                    invoiceNumber = null,
                    preOrderId = preOrderId.toString(),
                    creditsTotal = null,
                    creditApplied = request.applyCredit,
                    localPendingId = localId
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Espejo de OrderRepository.attachPaymentMethodOffline() — re-serializa la
    // conversión ya encolada con el payment_method/check_number recién
    // elegidos (se preguntan después del ticket #1), para que SyncWorker la
    // mande completa cuando recupere conexión.
    suspend fun attachConversionPaymentOffline(localPendingId: Long, paymentMethod: String?, checkNumber: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val dao = PendingPreOrderConversionDao(db)
                val entity = dao.getAll().find { it.id == localPendingId.toInt() }
                    ?: return@withContext Result.failure(Exception("Pending conversion $localPendingId not found"))
                val request = gson.fromJson(entity.requestJson, ConvertPreOrderRequest::class.java)
                    .copy(paymentMethod = paymentMethod, checkNumber = checkNumber)
                dao.updateRequestJson(localPendingId, gson.toJson(request))
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

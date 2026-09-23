package com.example.test.data.repository

import com.example.test.data.CreateReceiptRequest
import com.example.test.data.CreateReceiptResponse
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.local.dao.PendingReceiptDao
import com.example.test.data.local.entities.PendingReceiptEntity
import com.example.test.data.network.RetrofitClient
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Backlog cliente (2026-09-22) — el almacén tiene pésima conexión. Antes,
// Recepción (ReceivingActivity) dependía 100% de la red: buscar producto por
// barcode/nombre y guardar la recepción llamaban al API directo, sin ningún
// fallback — a diferencia de Venta/Pre-órdenes, que ya tienen offline-first
// desde hace varias fases (ProductRepository.findByBarcode + OrderRepository/
// PreOrderRepository.saveOffline* + SyncWorker). Mismo patrón acá, solo para
// Recepción por ahora (decisión explícita del usuario — las demás pantallas
// de Almacén, como cargar una ruta, dependen de calcular FIFO en vivo contra
// el servidor y quedan fuera de esta ronda): si `isOfflineMode()` está
// prendido, ni siquiera se intenta la red (mismo criterio que
// OrderRepository.createBatch); si la llamada falla en el momento
// (excepción de red — típico en el almacén con señal intermitente), se
// encola local para que SyncWorker la mande cuando vuelva la conexión.
class WarehouseRepository(
    private val db: AppDatabase,
    private val securePrefs: SecurePreferences
) {
    private val gson = Gson()

    suspend fun createReceipt(request: CreateReceiptRequest): Result<CreateReceiptResponse> =
        withContext(Dispatchers.IO) {
            if (securePrefs.isOfflineMode()) {
                return@withContext saveOfflineReceipt(request)
            }
            try {
                val response = RetrofitClient.getApi().createReceipt(request)
                if (response.isSuccessful && response.body() != null) {
                    Result.success(response.body()!!)
                } else {
                    Result.failure(Exception("Error del servidor: ${response.code()}"))
                }
            } catch (e: Exception) {
                // Sin red — guardar localmente para sincronizar cuando haya conexión
                saveOfflineReceipt(request)
            }
        }

    private fun saveOfflineReceipt(request: CreateReceiptRequest): Result<CreateReceiptResponse> {
        return try {
            val json = gson.toJson(request)
            val localId = PendingReceiptDao(db).insert(PendingReceiptEntity(requestJson = json))
            // Sin lot_id real ni confirmación de QBO todavía — el ticket
            // impreso offline se arma del lado del cliente con lo que ya se
            // tipeó (ReceivingActivity.buildOfflineReceiptItems()), no con
            // este `items` vacío.
            Result.success(
                CreateReceiptResponse(receiptBatchId = "OFFLINE_PENDING", items = emptyList(), localPendingId = localId)
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

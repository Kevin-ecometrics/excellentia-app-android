package com.example.test.data.repository

import com.example.test.data.*
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.local.dao.CustomerDao
import com.example.test.data.local.dao.OrderDao
import com.example.test.data.local.dao.PendingBatchDao
import com.example.test.data.local.dao.ProductDao
import com.example.test.data.local.entities.CachedCustomerEntity
import com.example.test.data.local.entities.CachedProductEntity
import com.example.test.data.local.entities.PendingBatchEntity
import com.example.test.data.network.RetrofitClient
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OrderRepository(
    private val db: AppDatabase,
    private val securePrefs: SecurePreferences
) {
    private val orderDao = OrderDao(db)
    private val gson = Gson()

    suspend fun createOrder(
        barcode: String,
        productName: String,
        price: Double,
        quantity: Double,
        total: Double = price * quantity,
        deviceId: Int? = null
    ): Result<OrderResponse> = withContext(Dispatchers.IO) {
        val offlineMode = securePrefs.isOfflineMode()

        if (!offlineMode) {
            try {
                val response = RetrofitClient.getApi().createOrder(
                    CreateOrderRequest(
                        barcode = barcode,
                        productName = productName,
                        price = price,
                        quantity = quantity,
                        total = total,
                        deviceId = deviceId
                    )
                )
                if (response.isSuccessful && response.body() != null) {
                    return@withContext Result.success(response.body()!!)
                } else {
                    return@withContext Result.failure(Exception("Error del servidor: ${response.code()}"))
                }
            } catch (e: Exception) {
                savePendingOrder(barcode, productName, price, quantity, deviceId)
                return@withContext Result.failure(e)
            }
        } else {
            savePendingOrder(barcode, productName, price, quantity, deviceId)
            return@withContext Result.success(OrderResponse(id = 0, barcode = barcode, status = "PENDING"))
        }
    }

    fun savePendingOrder(
        barcode: String,
        productName: String,
        price: Double,
        quantity: Double,
        deviceId: Int? = null,
        unit: String? = null
    ) {
        orderDao.insert(
            com.example.test.data.local.entities.PendingOrderEntity(
                barcode = barcode,
                productName = productName,
                price = price,
                quantity = quantity,
                deviceId = deviceId,
                customerId = securePrefs.getActiveCustomerId(),
                customerName = securePrefs.getActiveCustomerName(),
                unit = unit
            )
        )
    }

    suspend fun getPendingOrders(): List<com.example.test.data.local.entities.PendingOrderEntity> = withContext(Dispatchers.IO) {
        orderDao.getAllForHistory()
    }

    fun getPendingCount(): Int = orderDao.count()

    suspend fun clearPending() = withContext(Dispatchers.IO) {
        orderDao.deleteAll()
    }

    fun updatePendingOrder(id: Int, price: Double, quantity: Double) {
        orderDao.update(id, price, quantity)
    }

    suspend fun getById(id: Int): com.example.test.data.local.entities.PendingOrderEntity? = withContext(Dispatchers.IO) {
        orderDao.getById(id)
    }

    suspend fun deletePendingOrder(id: Int) = withContext(Dispatchers.IO) {
        orderDao.deleteById(id)
    }

    suspend fun sendBatch(
        items: List<BatchItem>,
        customerId: String? = null,
        customerName: String? = null,
        signature: String? = null,
        damageItems: List<DamageItem> = emptyList(),
        paymentMethod: String? = null
    ): Result<BatchResponse> = withContext(Dispatchers.IO) {
        val request = BatchRequest(
            items = items,
            customerId = customerId,
            customerName = customerName,
            signature = signature,
            damageItems = damageItems.ifEmpty { null },
            paymentMethod = paymentMethod
        )

        // Si ya estamos en modo offline, guardar directamente sin intentar el API
        if (securePrefs.isOfflineMode()) {
            return@withContext saveOfflineBatch(request)
        }

        try {
            val response = RetrofitClient.getApi().createBatch(request)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Error del servidor: ${response.code()}"))
            }
        } catch (e: Exception) {
            // Sin red — guardar localmente para sincronizar cuando haya conexión
            saveOfflineBatch(request)
        }
    }

    private fun saveOfflineBatch(request: BatchRequest): Result<BatchResponse> {
        return try {
            val json = gson.toJson(request)
            PendingBatchDao(db).insert(PendingBatchEntity(batchJson = json))
            Result.success(BatchResponse(batchId = "OFFLINE_PENDING", invoiceId = null, orders = emptyList()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRemoteOrders(page: Int = 1, limit: Int = 20): Result<ApiResponse<List<OrderDto>>> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().listOrders(page = page, limit = limit)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getProductPriceHistory(barcode: String, customerId: String): Result<PriceHistoryResponse> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().getProductPriceHistory(barcode, customerId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Pre-caché masivo al conectar ──

    suspend fun prefetchAllProducts() = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().getAllProducts(limit = 500)
            if (response.isSuccessful) {
                val products = response.body()?.data ?: return@withContext
                val dao = ProductDao(db)
                for (dto in products) {
                    dao.upsert(
                        CachedProductEntity(
                            barcode = dto.barcode ?: "QBO-${dto.id}",
                            name = dto.name,
                            price = dto.price,
                            category = dto.category,
                            brand = dto.brand,
                            stock = dto.stock,
                            weightPerUnit = dto.weightPerUnit,
                            unit = dto.unit,
                            caseQty = dto.caseQty
                        )
                    )
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun prefetchAllCustomers() = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().getCustomers()
            if (response.isSuccessful) {
                val customers = response.body()
                    ?.queryResponse?.customers
                    ?.filter { it.active }
                    ?: return@withContext
                CustomerDao(db).insertAll(customers.map { c ->
                    CachedCustomerEntity(
                        id           = c.id,
                        displayName  = c.displayName,
                        addressLine1 = c.addressLine1,
                        city         = c.city,
                        stateCode    = c.stateCode,
                        postalCode   = c.postalCode
                    )
                })
            }
        } catch (_: Exception) {}
    }
}

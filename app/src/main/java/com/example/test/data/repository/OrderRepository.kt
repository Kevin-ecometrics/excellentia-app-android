package com.example.test.data.repository

import com.example.test.data.*
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.local.dao.OrderDao
import com.example.test.data.local.entities.PendingOrderEntity
import com.example.test.data.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OrderRepository(
    private val db: AppDatabase,
    private val securePrefs: SecurePreferences
) {
    private val orderDao = OrderDao(db)

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
        deviceId: Int? = null
    ) {
        orderDao.insert(
            PendingOrderEntity(
                barcode = barcode,
                productName = productName,
                price = price,
                quantity = quantity,
                deviceId = deviceId
            )
        )
    }

    suspend fun getPendingOrders(): List<PendingOrderEntity> = withContext(Dispatchers.IO) {
        orderDao.getAllPending()
    }

    fun getPendingCount(): Int = orderDao.count()

    suspend fun clearPending() = withContext(Dispatchers.IO) {
        orderDao.deleteAll()
    }

    fun updatePendingOrder(id: Int, price: Double, quantity: Double) {
        orderDao.update(id, price, quantity)
    }

    suspend fun deletePendingOrder(id: Int) = withContext(Dispatchers.IO) {
        orderDao.deleteById(id)
    }

    suspend fun sendBatch(
        items: List<BatchItem>,
        customerId: String? = null,
        customerName: String? = null
    ): Result<BatchResponse> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().createBatch(
                BatchRequest(items = items, customerId = customerId, customerName = customerName)
            )
            if (response.isSuccessful && response.body() != null) {
                return@withContext Result.success(response.body()!!)
            } else {
                return@withContext Result.failure(Exception("Error del servidor: ${response.code()}"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun getRemoteOrders(page: Int = 1, limit: Int = 20): Result<ApiResponse<List<OrderDto>>> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().listOrders(page = page, limit = limit)
            if (response.isSuccessful && response.body() != null) {
                return@withContext Result.success(response.body()!!)
            } else {
                return@withContext Result.failure(Exception("Error: ${response.code()}"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
}

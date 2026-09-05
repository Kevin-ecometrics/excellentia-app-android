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

    // merge=true (default): si ya hay una fila activa del mismo barcode + mismo
    // precio en el carrito, suma la cantidad ahí en vez de duplicar la fila —
    // evita ver el mismo producto dos veces solo por haberlo re-escaneado.
    // merge=false: siempre inserta una fila nueva — usado para productos por
    // peso, donde cada unidad pesada individualmente debe quedar editable por
    // separado (no tiene sentido sumar pesos de artículos físicos distintos).
    fun savePendingOrder(
        barcode: String,
        productName: String,
        price: Double,
        quantity: Double,
        deviceId: Int? = null,
        unit: String? = null,
        caseQty: Int? = null,
        merge: Boolean = true,
        shortName: String? = null
    ) {
        val existing = if (merge) orderDao.findActiveByBarcodeAndPrice(barcode, price) else null
        if (existing != null) {
            orderDao.update(existing.id, price, existing.quantity + quantity)
            return
        }
        orderDao.insert(
            com.example.test.data.local.entities.PendingOrderEntity(
                barcode = barcode,
                productName = productName,
                price = price,
                quantity = quantity,
                deviceId = deviceId,
                customerId = securePrefs.getActiveCustomerId(),
                customerName = securePrefs.getActiveCustomerName(),
                unit = unit,
                caseQty = caseQty,
                shortName = shortName
            )
        )
    }

    suspend fun getPendingOrders(): List<com.example.test.data.local.entities.PendingOrderEntity> = withContext(Dispatchers.IO) {
        orderDao.getAllForHistory()
    }

    // Fase 86 — persiste un "credit item" (botón "+ Agregar crédito" en
    // CurrentOrderActivity) en la misma tabla que el carrito normal
    // (is_credit = true), para que sobreviva el cierre de la app igual que
    // los productos normales. Mergea por barcode si ya había un crédito
    // activo del mismo producto (suma qty, mantiene el precio ya guardado).
    suspend fun saveCreditItem(barcode: String, productName: String, qty: Double, unitPrice: Double, unit: String? = null) = withContext(Dispatchers.IO) {
        val existing = orderDao.findActiveCreditByBarcode(barcode)
        if (existing != null) {
            orderDao.update(existing.id, existing.price, existing.quantity + qty)
        } else {
            orderDao.insert(
                com.example.test.data.local.entities.PendingOrderEntity(
                    barcode = barcode,
                    productName = productName,
                    price = unitPrice,
                    quantity = qty,
                    customerId = securePrefs.getActiveCustomerId(),
                    customerName = securePrefs.getActiveCustomerName(),
                    unit = unit,
                    isCredit = true
                )
            )
        }
    }

    fun getPendingCount(): Int = orderDao.count()

    suspend fun clearPending() = withContext(Dispatchers.IO) {
        orderDao.deleteAll()
    }

    fun updatePendingOrder(id: Int, price: Double, quantity: Double) {
        orderDao.update(id, price, quantity)
    }

    // Fase 115.5 — toggle "Marcar como cortesía" por fila del carrito.
    fun setCourtesy(id: Int, isCourtesy: Boolean) {
        orderDao.setCourtesy(id, isCourtesy)
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
        paymentMethod: String? = null,
        checkNumber: String? = null,
        applyCredit: Double? = null
    ): Result<BatchResponse> = withContext(Dispatchers.IO) {
        // route_id/stop_id: si hay una venta "por scratch" en curso (ver
        // MyRouteDetailActivity.activateCustomerAndSell()), estos ya están
        // seteados en SecurePreferences en este punto — recién se limpian
        // en markRouteStopDeliveredIfAny(), DESPUÉS de que este envío tenga
        // éxito. El backend los usa para no descontar stock dos veces (ya
        // se descontó al cargar la ruta) y para vincular la venta a la
        // parada (createBatch, orderController.ts).
        val request = BatchRequest(
            items = items,
            customerId = customerId,
            customerName = customerName,
            signature = signature,
            damageItems = damageItems.ifEmpty { null },
            paymentMethod = paymentMethod,
            checkNumber = checkNumber,
            applyCredit = applyCredit,
            routeId = securePrefs.getActiveRouteId(),
            stopId = securePrefs.getActiveStopId()
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
            val localId = PendingBatchDao(db).insert(PendingBatchEntity(batchJson = json))
            Result.success(BatchResponse(batchId = "OFFLINE_PENDING", invoiceId = null, orders = emptyList(), localPendingId = localId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Fase 82 — adjunta el payment_method/check_number (elegido después del
    // ticket #1, cuando el batch ya se mandó y tiene invoice real) a un
    // batch que sí llegó al servidor.
    suspend fun attachPaymentMethod(batchId: String, paymentMethod: String?, checkNumber: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val response = RetrofitClient.getApi().updateBatchPayment(
                    batchId, UpdatePaymentRequest(paymentMethod, checkNumber)
                )
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(Exception("Error del servidor: ${response.code()}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // Fase 82 — mismo propósito que attachPaymentMethod() pero para un
    // batch que quedó encolado offline (nunca llegó al servidor todavía):
    // re-serializa el BatchRequest ya guardado en pending_batches con el
    // payment_method/check_number correctos, para que SyncWorker lo mande
    // bien cuando recupere conexión (createBatch de siempre, sin endpoint
    // especial para este caso).
    suspend fun attachPaymentMethodOffline(localPendingId: Long, paymentMethod: String?, checkNumber: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val dao = PendingBatchDao(db)
                val entity = dao.getAll().find { it.id == localPendingId.toInt() }
                    ?: return@withContext Result.failure(Exception("Pending batch $localPendingId not found"))
                val request = gson.fromJson(entity.batchJson, BatchRequest::class.java)
                    .copy(paymentMethod = paymentMethod, checkNumber = checkNumber)
                dao.updateBatchJson(localPendingId, gson.toJson(request))
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // Reenvía a QuickBooks los items de un batch que quedaron PENDING/FAILED.
    suspend fun retryBatchSync(batchId: String): Result<RetryBatchResponse> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().retryBatchSync(batchId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val message = try {
                    response.errorBody()?.string()?.let { gson.fromJson(it, ApiErrorBody::class.java)?.error }
                } catch (_: Exception) { null }
                Result.failure(Exception(message ?: "Error del servidor: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Fase 117 — solo aplican mientras el batch sigue AWAITING_APPROVAL,
    // 100% local (no tocan QBO). Mismo manejo de error que retryBatchSync.
    suspend fun cancelBatch(batchId: String, reason: String? = null): Result<CancelBatchResponse> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().cancelBatch(batchId, CancelBatchRequest(reason))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val message = try {
                    response.errorBody()?.string()?.let { gson.fromJson(it, ApiErrorBody::class.java)?.error }
                } catch (_: Exception) { null }
                Result.failure(Exception(message ?: "Error del servidor: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun editBatch(batchId: String, items: List<BatchItem>): Result<EditBatchResponse> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApi().editBatch(batchId, EditBatchRequest(items))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val message = try {
                    response.errorBody()?.string()?.let { gson.fromJson(it, ApiErrorBody::class.java)?.error }
                } catch (_: Exception) { null }
                Result.failure(Exception(message ?: "Error del servidor: ${response.code()}"))
            }
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

    // Trae el catálogo completo (el backend ya excluye productos ocultos/inactivos,
    // hidden = 1) y podá del cache local cualquier producto que ya no vino en este
    // barrido — así un producto que se desactiva en la webapp deja de aparecer en
    // búsquedas/escaneos offline en vez de quedar cacheado para siempre.
    suspend fun prefetchAllProducts() = withContext(Dispatchers.IO) {
        val syncStartedAt = System.currentTimeMillis()
        val dao = ProductDao(db)
        try {
            var page = 1
            val pageSize = 500
            var fetched = 0
            var total = Int.MAX_VALUE
            while (fetched < total) {
                val response = RetrofitClient.getApi().getAllProducts(page = page, limit = pageSize)
                if (!response.isSuccessful) return@withContext
                val body = response.body() ?: return@withContext
                val products = body.data ?: emptyList()
                if (products.isEmpty()) break

                for (dto in products) {
                    dao.upsert(
                        CachedProductEntity(
                            barcode = dto.barcode ?: "QBO-${dto.id}",
                            sku = dto.sku,
                            name = dto.name,
                            price = dto.price,
                            category = dto.category,
                            brand = dto.brand,
                            stock = dto.stock,
                            weightPerUnit = dto.weightPerUnit,
                            unit = dto.unit,
                            caseQty = dto.caseQty,
                            qty = dto.qty,
                            qbItemId = dto.qbItemId,
                            qbActive = dto.qbActive,
                            shortName = dto.shortName
                        )
                    )
                }

                fetched += products.size
                total = body.meta?.total ?: fetched
                page++
            }
            // Solo podamos si el barrido completo terminó sin errores — si se cortó a
            // mitad de camino, podar borraría productos activos que no llegamos a tocar.
            dao.deleteOldCache(syncStartedAt)
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

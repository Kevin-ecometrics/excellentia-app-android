package com.example.test.data.repository

import com.example.test.data.Product
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.local.dao.ProductDao
import com.example.test.data.local.entities.CachedProductEntity
import com.example.test.data.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProductRepository(
    private val db: AppDatabase,
    private val securePrefs: SecurePreferences
) {
    private val productDao = ProductDao(db)

    suspend fun findByBarcode(barcode: String): Product? = withContext(Dispatchers.IO) {
        // Offline mode: ir directo al cache sin intentar el API
        if (securePrefs.isOfflineMode()) {
            return@withContext fromCache(barcode)
        }

        try {
            val response = RetrofitClient.getApi().getProductByBarcode(barcode)
            if (response.isSuccessful) {
                val dto = response.body()?.data
                if (dto != null) {
                    productDao.upsert(
                        CachedProductEntity(
                            barcode = dto.barcode ?: barcode,
                            name = dto.name,
                            price = dto.price,
                            category = dto.category,
                            brand = dto.brand,
                            stock = dto.stock,
                            weightPerUnit = dto.weightPerUnit,
                            unit = dto.unit,
                            caseQty = dto.caseQty,
                            qty = dto.qty
                        )
                    )
                    return@withContext dto.toProduct()
                }
                return@withContext null
            }
            if (response.code() == 404) {
                // El backend confirmó que no existe (o está oculto/inactivo) — no
                // servirlo desde un cache viejo, y sacarlo si había quedado cacheado
                // de antes de que se ocultara.
                productDao.deleteByBarcode(barcode)
                return@withContext null
            }
            // Error de servidor — fallback al cache
            fromCache(barcode)
        } catch (_: Exception) {
            // Sin red — fallback al cache
            fromCache(barcode)
        }
    }

    fun searchOffline(query: String): List<CachedProductEntity> =
        productDao.searchByQuery(query)

    private fun fromCache(barcode: String): Product? {
        val cached = productDao.findByBarcode(barcode)
        return if (cached != null) {
            Product(cached.barcode, cached.name, cached.price, cached.weightPerUnit, cached.stock, cached.unit, cached.qty, cached.caseQty)
        } else null
    }
}

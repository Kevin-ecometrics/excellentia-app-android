package com.example.test.data.repository

import com.example.test.data.Product
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.dao.ProductDao
import com.example.test.data.local.entities.CachedProductEntity
import com.example.test.data.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProductRepository(
    private val db: AppDatabase
) {
    private val productDao = ProductDao(db)

    suspend fun findByBarcode(barcode: String): Product? = withContext(Dispatchers.IO) {
        try {
            // 1. Try API
            val response = RetrofitClient.getApi().getProductByBarcode(barcode)
            if (response.isSuccessful) {
                val dto = response.body()?.data
                if (dto != null) {
                    // Cache in SQLite
                    productDao.upsert(
                        CachedProductEntity(
                            barcode = dto.barcode ?: barcode,
                            name = dto.name,
                            price = dto.price,
                            category = dto.category,
                            brand = dto.brand,
                            stock = dto.stock,
                            weightPerUnit = dto.weightPerUnit
                        )
                    )
                    return@withContext dto.toProduct()
                }
            }
            // 2. Fallback to cache
            val cached = productDao.findByBarcode(barcode)
            if (cached != null) {
                return@withContext Product(cached.barcode, cached.name, cached.price, cached.weightPerUnit)
            }
            null
        } catch (_: Exception) {
            // Offline: try cache
            val cached = productDao.findByBarcode(barcode)
            return@withContext if (cached != null) {
                Product(cached.barcode, cached.name, cached.price, cached.weightPerUnit)
            } else null
        }
    }
}

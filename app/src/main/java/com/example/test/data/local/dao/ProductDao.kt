package com.example.test.data.local.dao

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.entities.CachedProductEntity

class ProductDao(private val db: AppDatabase) {

    fun findByBarcode(barcode: String): CachedProductEntity? {
        val cursor = db.readableDatabase.query(
            "cached_products", null,
            "barcode = ?", arrayOf(barcode),
            null, null, null, "1"
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToEntity(it) else null
        }
    }

    fun upsert(product: CachedProductEntity) {
        val values = ContentValues().apply {
            put("barcode", product.barcode)
            product.sku?.let { put("sku", it) } ?: putNull("sku")
            put("name", product.name)
            put("price", product.price)
            put("category", product.category)
            put("brand", product.brand)
            put("stock", product.stock)
            product.weightPerUnit?.let { put("weight_per_unit", it) }
            product.unit?.let { put("unit", it) }
            product.caseQty?.let { put("case_qty", it) }
            put("qty", product.qty)
            put("qb_item_id", product.qbItemId)
            product.qbActive?.let { put("qb_active", if (it) 1 else 0) } ?: putNull("qb_active")
            product.shortName?.let { put("short_name", it) } ?: putNull("short_name")
            put("cached_at", product.cachedAt)
        }
        db.writableDatabase.insertWithOnConflict(
            "cached_products", null, values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getAll(): List<CachedProductEntity> {
        val cursor = db.readableDatabase.query(
            "cached_products", null, null, null,
            null, null, "name ASC"
        )
        return cursor.use {
            val list = mutableListOf<CachedProductEntity>()
            while (it.moveToNext()) {
                list.add(cursorToEntity(it))
            }
            list
        }
    }

    fun searchByQuery(query: String): List<CachedProductEntity> {
        val like = "%$query%"
        val cursor = db.readableDatabase.rawQuery(
            "SELECT * FROM cached_products WHERE barcode LIKE ? OR name LIKE ? OR sku LIKE ? ORDER BY name ASC LIMIT 30",
            arrayOf(like, like, like)
        )
        return cursor.use {
            val list = mutableListOf<CachedProductEntity>()
            while (it.moveToNext()) list.add(cursorToEntity(it))
            list
        }
    }

    fun deleteByBarcode(barcode: String) {
        db.writableDatabase.delete(
            "cached_products",
            "barcode = ?", arrayOf(barcode)
        )
    }

    fun deleteOldCache(before: Long) {
        db.writableDatabase.delete(
            "cached_products",
            "cached_at < ?", arrayOf(before.toString())
        )
    }

    private fun cursorToEntity(c: Cursor): CachedProductEntity {
        val weightIdx = try { c.getColumnIndexOrThrow("weight_per_unit") } catch (_: Exception) { -1 }
        val unitIdx = try { c.getColumnIndexOrThrow("unit") } catch (_: Exception) { -1 }
        val caseQtyIdx = try { c.getColumnIndexOrThrow("case_qty") } catch (_: Exception) { -1 }
        val qtyIdx = try { c.getColumnIndexOrThrow("qty") } catch (_: Exception) { -1 }
        val qbItemIdIdx = try { c.getColumnIndexOrThrow("qb_item_id") } catch (_: Exception) { -1 }
        val qbActiveIdx = try { c.getColumnIndexOrThrow("qb_active") } catch (_: Exception) { -1 }
        val shortNameIdx = try { c.getColumnIndexOrThrow("short_name") } catch (_: Exception) { -1 }
        val skuIdx = try { c.getColumnIndexOrThrow("sku") } catch (_: Exception) { -1 }
        return CachedProductEntity(
            id = c.getInt(c.getColumnIndexOrThrow("id")),
            barcode = c.getString(c.getColumnIndexOrThrow("barcode")),
            sku = if (skuIdx >= 0 && !c.isNull(skuIdx)) c.getString(skuIdx) else null,
            name = c.getString(c.getColumnIndexOrThrow("name")),
            price = c.getDouble(c.getColumnIndexOrThrow("price")),
            category = c.getString(c.getColumnIndexOrThrow("category")),
            brand = c.getString(c.getColumnIndexOrThrow("brand")),
            stock = c.getInt(c.getColumnIndexOrThrow("stock")),
            weightPerUnit = if (weightIdx >= 0 && !c.isNull(weightIdx)) c.getDouble(weightIdx) else null,
            unit = if (unitIdx >= 0 && !c.isNull(unitIdx)) c.getString(unitIdx) else null,
            caseQty = if (caseQtyIdx >= 0 && !c.isNull(caseQtyIdx)) c.getInt(caseQtyIdx) else null,
            qty = if (qtyIdx >= 0 && !c.isNull(qtyIdx)) c.getInt(qtyIdx) else 0,
            qbItemId = if (qbItemIdIdx >= 0 && !c.isNull(qbItemIdIdx)) c.getString(qbItemIdIdx) else null,
            qbActive = if (qbActiveIdx >= 0 && !c.isNull(qbActiveIdx)) c.getInt(qbActiveIdx) != 0 else null,
            shortName = if (shortNameIdx >= 0 && !c.isNull(shortNameIdx)) c.getString(shortNameIdx) else null,
            cachedAt = c.getLong(c.getColumnIndexOrThrow("cached_at"))
        )
    }
}

package com.example.test.data.local.dao

import android.content.ContentValues
import android.database.Cursor
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.entities.PendingOrderEntity

class OrderDao(private val db: AppDatabase) {

    fun insert(order: PendingOrderEntity): Long {
        val values = ContentValues().apply {
            put("barcode", order.barcode)
            put("product_name", order.productName)
            put("price", order.price)
            put("quantity", order.quantity)
            put("device_id", order.deviceId)
            put("created_at", order.createdAt)
            put("retry_count", order.retryCount)
            put("customer_id", order.customerId)
            put("customer_name", order.customerName)
            order.unit?.let { put("unit", it) }
        }
        return db.writableDatabase.insert("pending_orders", null, values)
    }

    // Solo órdenes activas para sync — excluye fallidas (retry_count = -1)
    fun getAllPending(): List<PendingOrderEntity> {
        val cursor = db.readableDatabase.query(
            "pending_orders", null,
            "retry_count >= 0", null,
            null, null, "created_at DESC"
        )
        return cursor.use {
            val list = mutableListOf<PendingOrderEntity>()
            while (it.moveToNext()) { list.add(cursorToEntity(it)) }
            list
        }
    }

    // Todas las órdenes locales para mostrar en historial (incluye fallidas)
    fun getAllForHistory(): List<PendingOrderEntity> {
        val cursor = db.readableDatabase.query(
            "pending_orders", null, null, null,
            null, null, "created_at DESC"
        )
        return cursor.use {
            val list = mutableListOf<PendingOrderEntity>()
            while (it.moveToNext()) { list.add(cursorToEntity(it)) }
            list
        }
    }

    fun getById(id: Int): PendingOrderEntity? {
        val cursor = db.readableDatabase.query(
            "pending_orders", null,
            "id = ?", arrayOf(id.toString()),
            null, null, null, "1"
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToEntity(it) else null
        }
    }

    fun update(id: Int, price: Double, quantity: Double) {
        val values = ContentValues().apply {
            put("price", price)
            put("quantity", quantity)
        }
        db.writableDatabase.update("pending_orders", values, "id = ?", arrayOf(id.toString()))
    }

    fun deleteById(id: Int) {
        db.writableDatabase.delete(
            "pending_orders",
            "id = ?", arrayOf(id.toString())
        )
    }

    fun incrementRetry(id: Int) {
        val values = ContentValues().apply {
            put("retry_count", getById(id)?.let { it.retryCount + 1 } ?: 1)
        }
        db.writableDatabase.update(
            "pending_orders", values,
            "id = ?", arrayOf(id.toString())
        )
    }

    fun markFailed(id: Int) {
        val values = ContentValues().apply {
            put("retry_count", -1)
        }
        db.writableDatabase.update("pending_orders", values, "id = ?", arrayOf(id.toString()))
    }

    fun deleteAll() {
        db.writableDatabase.delete("pending_orders", null, null)
    }

    fun count(): Int {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM pending_orders WHERE retry_count >= 0", null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun cursorToEntity(c: Cursor): PendingOrderEntity = PendingOrderEntity(
        id = c.getInt(c.getColumnIndexOrThrow("id")),
        barcode = c.getString(c.getColumnIndexOrThrow("barcode")),
        productName = c.getString(c.getColumnIndexOrThrow("product_name")),
        price = c.getDouble(c.getColumnIndexOrThrow("price")),
        quantity = c.getDouble(c.getColumnIndexOrThrow("quantity")),
        deviceId = if (c.isNull(c.getColumnIndexOrThrow("device_id"))) null
            else c.getInt(c.getColumnIndexOrThrow("device_id")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        retryCount = c.getInt(c.getColumnIndexOrThrow("retry_count")),
        customerId = c.getColumnIndex("customer_id").takeIf { it >= 0 }
            ?.let { if (c.isNull(it)) null else c.getString(it) },
        customerName = c.getColumnIndex("customer_name").takeIf { it >= 0 }
            ?.let { if (c.isNull(it)) null else c.getString(it) },
        unit = c.getColumnIndex("unit").takeIf { it >= 0 }
            ?.let { if (c.isNull(it)) null else c.getString(it) }
    )
}

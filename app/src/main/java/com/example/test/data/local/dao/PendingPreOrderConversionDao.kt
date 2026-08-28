package com.example.test.data.local.dao

import android.content.ContentValues
import android.database.Cursor
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.entities.PendingPreOrderConversionEntity

class PendingPreOrderConversionDao(private val db: AppDatabase) {

    fun insert(entity: PendingPreOrderConversionEntity): Long {
        val values = ContentValues().apply {
            put("pre_order_id", entity.preOrderId)
            put("request_json", entity.requestJson)
            put("created_at", entity.createdAt)
        }
        return db.writableDatabase.insert("pending_preorder_conversions", null, values)
    }

    // Espejo de PendingBatchDao.updateBatchJson() — re-serializa la conversión ya
    // encolada con el payment_method/check_number elegidos después del ticket #1.
    fun updateRequestJson(id: Long, newJson: String) {
        val values = ContentValues().apply { put("request_json", newJson) }
        db.writableDatabase.update("pending_preorder_conversions", values, "id = ?", arrayOf(id.toString()))
    }

    fun getAll(): List<PendingPreOrderConversionEntity> {
        val cursor = db.readableDatabase.query(
            "pending_preorder_conversions", null, null, null,
            null, null, "created_at ASC"
        )
        return cursor.use {
            val list = mutableListOf<PendingPreOrderConversionEntity>()
            while (it.moveToNext()) list.add(cursorToEntity(it))
            list
        }
    }

    fun deleteById(id: Int) {
        db.writableDatabase.delete("pending_preorder_conversions", "id = ?", arrayOf(id.toString()))
    }

    private fun cursorToEntity(c: Cursor) = PendingPreOrderConversionEntity(
        id          = c.getInt(c.getColumnIndexOrThrow("id")),
        preOrderId  = c.getInt(c.getColumnIndexOrThrow("pre_order_id")),
        requestJson = c.getString(c.getColumnIndexOrThrow("request_json")),
        createdAt   = c.getLong(c.getColumnIndexOrThrow("created_at"))
    )
}

package com.example.test.data.local.dao

import android.content.ContentValues
import android.database.Cursor
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.entities.PendingBatchEntity

class PendingBatchDao(private val db: AppDatabase) {

    fun insert(entity: PendingBatchEntity) {
        val values = ContentValues().apply {
            put("batch_json", entity.batchJson)
            put("created_at", entity.createdAt)
        }
        db.writableDatabase.insert("pending_batches", null, values)
    }

    fun getAll(): List<PendingBatchEntity> {
        val cursor = db.readableDatabase.query(
            "pending_batches", null, null, null,
            null, null, "created_at ASC"
        )
        return cursor.use {
            val list = mutableListOf<PendingBatchEntity>()
            while (it.moveToNext()) list.add(cursorToEntity(it))
            list
        }
    }

    fun deleteById(id: Int) {
        db.writableDatabase.delete("pending_batches", "id = ?", arrayOf(id.toString()))
    }

    fun count(): Int {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM pending_batches", null
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun cursorToEntity(c: Cursor) = PendingBatchEntity(
        id        = c.getInt(c.getColumnIndexOrThrow("id")),
        batchJson = c.getString(c.getColumnIndexOrThrow("batch_json")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
    )
}

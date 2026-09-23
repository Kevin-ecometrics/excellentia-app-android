package com.example.test.data.local.dao

import android.content.ContentValues
import android.database.Cursor
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.entities.PendingReceiptEntity

// Backlog cliente (2026-09-22) — mismo patrón que PendingBatchDao, tabla
// propia (pending_receipts) para no mezclar colas de sincronización distintas.
class PendingReceiptDao(private val db: AppDatabase) {

    fun insert(entity: PendingReceiptEntity): Long {
        val values = ContentValues().apply {
            put("request_json", entity.requestJson)
            put("created_at", entity.createdAt)
        }
        return db.writableDatabase.insert("pending_receipts", null, values)
    }

    fun getAll(): List<PendingReceiptEntity> {
        val cursor = db.readableDatabase.query(
            "pending_receipts", null, null, null,
            null, null, "created_at ASC"
        )
        return cursor.use {
            val list = mutableListOf<PendingReceiptEntity>()
            while (it.moveToNext()) list.add(cursorToEntity(it))
            list
        }
    }

    fun deleteById(id: Int) {
        db.writableDatabase.delete("pending_receipts", "id = ?", arrayOf(id.toString()))
    }

    fun count(): Int {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM pending_receipts", null
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun cursorToEntity(c: Cursor) = PendingReceiptEntity(
        id          = c.getInt(c.getColumnIndexOrThrow("id")),
        requestJson = c.getString(c.getColumnIndexOrThrow("request_json")),
        createdAt   = c.getLong(c.getColumnIndexOrThrow("created_at"))
    )
}

package com.example.test.data.local.dao

import android.content.ContentValues
import android.database.Cursor
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.entities.PendingPreOrderEntity

class PendingPreOrderDao(private val db: AppDatabase) {

    fun insert(entity: PendingPreOrderEntity): Long {
        val values = ContentValues().apply {
            put("request_json", entity.requestJson)
            put("created_at", entity.createdAt)
        }
        return db.writableDatabase.insert("pending_preorders", null, values)
    }

    fun getAll(): List<PendingPreOrderEntity> {
        val cursor = db.readableDatabase.query(
            "pending_preorders", null, null, null,
            null, null, "created_at ASC"
        )
        return cursor.use {
            val list = mutableListOf<PendingPreOrderEntity>()
            while (it.moveToNext()) list.add(cursorToEntity(it))
            list
        }
    }

    fun deleteById(id: Int) {
        db.writableDatabase.delete("pending_preorders", "id = ?", arrayOf(id.toString()))
    }

    fun count(): Int {
        val cursor = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM pending_preorders", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun cursorToEntity(c: Cursor) = PendingPreOrderEntity(
        id          = c.getInt(c.getColumnIndexOrThrow("id")),
        requestJson = c.getString(c.getColumnIndexOrThrow("request_json")),
        createdAt   = c.getLong(c.getColumnIndexOrThrow("created_at"))
    )
}

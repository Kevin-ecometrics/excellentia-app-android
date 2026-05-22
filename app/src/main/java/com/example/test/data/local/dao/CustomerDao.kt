package com.example.test.data.local.dao

import android.content.ContentValues
import android.database.Cursor
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.entities.CachedCustomerEntity

class CustomerDao(private val db: AppDatabase) {

    fun insertAll(customers: List<CachedCustomerEntity>) {
        val wdb = db.writableDatabase
        wdb.beginTransaction()
        try {
            wdb.delete("cached_customers", null, null)
            for (c in customers) {
                val values = ContentValues().apply {
                    put("id", c.id)
                    put("display_name", c.displayName)
                    put("address_line1", c.addressLine1)
                    put("city", c.city)
                    put("state_code", c.stateCode)
                    put("postal_code", c.postalCode)
                    put("cached_at", c.cachedAt)
                }
                wdb.insertWithOnConflict("cached_customers", null, values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            }
            wdb.setTransactionSuccessful()
        } finally {
            wdb.endTransaction()
        }
    }

    fun getAll(): List<CachedCustomerEntity> {
        val cursor = db.readableDatabase.query(
            "cached_customers", null, null, null,
            null, null, "display_name ASC"
        )
        return cursor.use {
            val list = mutableListOf<CachedCustomerEntity>()
            while (it.moveToNext()) list.add(cursorToEntity(it))
            list
        }
    }

    fun count(): Int {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cached_customers", null
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun cursorToEntity(c: Cursor) = CachedCustomerEntity(
        id           = c.getString(c.getColumnIndexOrThrow("id")),
        displayName  = c.getString(c.getColumnIndexOrThrow("display_name")),
        addressLine1 = c.getString(c.getColumnIndexOrThrow("address_line1")),
        city         = c.getString(c.getColumnIndexOrThrow("city")),
        stateCode    = c.getString(c.getColumnIndexOrThrow("state_code")),
        postalCode   = c.getString(c.getColumnIndexOrThrow("postal_code")),
        cachedAt     = c.getLong(c.getColumnIndexOrThrow("cached_at"))
    )
}

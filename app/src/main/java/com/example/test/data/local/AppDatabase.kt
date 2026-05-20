package com.example.test.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cached_products (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                barcode TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                price REAL NOT NULL,
                category TEXT,
                brand TEXT,
                stock INTEGER DEFAULT 0,
                weight_per_unit REAL,
                cached_at INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pending_orders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                barcode TEXT NOT NULL,
                product_name TEXT NOT NULL,
                price REAL NOT NULL,
                quantity REAL NOT NULL,
                device_id INTEGER,
                created_at INTEGER NOT NULL,
                retry_count INTEGER DEFAULT 0
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE cached_products ADD COLUMN weight_per_unit REAL")
            } catch (_: Exception) {
                // Column already exists — ignore
            }
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE pending_orders RENAME TO pending_orders_old")
            db.execSQL("""
                CREATE TABLE pending_orders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    barcode TEXT NOT NULL,
                    product_name TEXT NOT NULL,
                    price REAL NOT NULL,
                    quantity REAL NOT NULL,
                    device_id INTEGER,
                    created_at INTEGER NOT NULL,
                    retry_count INTEGER DEFAULT 0
                )
            """)
            db.execSQL("INSERT INTO pending_orders SELECT * FROM pending_orders_old")
            db.execSQL("DROP TABLE pending_orders_old")
        }
    }

    companion object {
        private const val DATABASE_NAME = "excellentia.db"
        private const val DATABASE_VERSION = 3

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppDatabase(context.applicationContext)
                    .also { INSTANCE = it }
            }
        }
    }
}

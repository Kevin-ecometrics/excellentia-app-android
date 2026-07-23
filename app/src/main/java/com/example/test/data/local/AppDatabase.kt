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
                unit TEXT,
                case_qty INTEGER DEFAULT NULL,
                qty INTEGER DEFAULT 0,
                qb_item_id TEXT,
                qb_active INTEGER,
                cached_at INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cached_customers (
                id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                address_line1 TEXT,
                city TEXT,
                state_code TEXT,
                postal_code TEXT,
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
                retry_count INTEGER DEFAULT 0,
                customer_id TEXT,
                customer_name TEXT,
                unit TEXT,
                case_qty INTEGER
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pending_batches (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                batch_json TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Debe ejecutarse primero: recrea pending_orders con el esquema base.
        // Si corriera después de los ALTER TABLE de más abajo (oldVersion < 4 / < 8),
        // la tabla recreada perdería customer_id/customer_name/unit y el
        // "INSERT INTO ... SELECT *" fallaría por columnas desparejadas.
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
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE cached_products ADD COLUMN weight_per_unit REAL")
            } catch (_: Exception) {
                // Column already exists — ignore
            }
        }
        if (oldVersion < 5) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS cached_customers (
                    id TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    address_line1 TEXT,
                    city TEXT,
                    state_code TEXT,
                    postal_code TEXT,
                    cached_at INTEGER NOT NULL
                )
            """)
        }
        if (oldVersion < 6) {
            try { db.execSQL("ALTER TABLE cached_customers ADD COLUMN address_line1 TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE cached_customers ADD COLUMN city TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE cached_customers ADD COLUMN state_code TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE cached_customers ADD COLUMN postal_code TEXT") } catch (_: Exception) {}
        }
        if (oldVersion < 4) {
            try { db.execSQL("ALTER TABLE pending_orders ADD COLUMN customer_id TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE pending_orders ADD COLUMN customer_name TEXT") } catch (_: Exception) {}
        }
        if (oldVersion < 7) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS pending_batches (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    batch_json TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
            """)
        }
        if (oldVersion < 8) {
            try { db.execSQL("ALTER TABLE cached_products ADD COLUMN unit TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE pending_orders ADD COLUMN unit TEXT") } catch (_: Exception) {}
        }
        if (oldVersion < 9) {
            try { db.execSQL("ALTER TABLE cached_products ADD COLUMN qty INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
        if (oldVersion < 10) {
            try { db.execSQL("ALTER TABLE cached_products ADD COLUMN case_qty INTEGER DEFAULT NULL") } catch (_: Exception) {}
        }
        if (oldVersion < 11) {
            try { db.execSQL("ALTER TABLE cached_products ADD COLUMN qb_item_id TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE cached_products ADD COLUMN qb_active INTEGER") } catch (_: Exception) {}
        }
        if (oldVersion < 12) {
            try { db.execSQL("ALTER TABLE pending_orders ADD COLUMN case_qty INTEGER") } catch (_: Exception) {}
        }
    }

    companion object {
        private const val DATABASE_NAME = "excellentia.db"
        private const val DATABASE_VERSION = 12

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

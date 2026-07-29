package com.example.test.data.local.entities

data class CachedProductEntity(
    val id: Int = 0,
    val barcode: String,
    val name: String,
    val price: Double,
    val category: String? = null,
    val brand: String? = null,
    val stock: Int = 0,
    val weightPerUnit: Double? = null,
    val unit: String? = null,
    val caseQty: Int? = null,
    val qty: Int = 0,
    val qbItemId: String? = null,
    val qbActive: Boolean? = null,
    val shortName: String? = null,
    val cachedAt: Long = System.currentTimeMillis()
)

package com.example.test.data.local.entities

data class PendingOrderEntity(
    val id: Int = 0,
    val barcode: String,
    val productName: String,
    val price: Double,
    val quantity: Double,
    val deviceId: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)

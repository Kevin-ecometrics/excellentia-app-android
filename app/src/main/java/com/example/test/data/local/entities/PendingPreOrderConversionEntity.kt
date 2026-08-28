package com.example.test.data.local.entities

data class PendingPreOrderConversionEntity(
    val id: Int = 0,
    val preOrderId: Int,
    val requestJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

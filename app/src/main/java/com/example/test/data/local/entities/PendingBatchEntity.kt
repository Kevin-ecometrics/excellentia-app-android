package com.example.test.data.local.entities

data class PendingBatchEntity(
    val id: Int = 0,
    val batchJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

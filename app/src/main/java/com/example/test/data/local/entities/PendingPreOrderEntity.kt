package com.example.test.data.local.entities

data class PendingPreOrderEntity(
    val id: Int = 0,
    val requestJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

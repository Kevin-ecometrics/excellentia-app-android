package com.example.test.data.local.entities

data class CachedCustomerEntity(
    val id: String,
    val displayName: String,
    val cachedAt: Long = System.currentTimeMillis()
)

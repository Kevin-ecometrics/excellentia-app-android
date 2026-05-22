package com.example.test.data.local.entities

data class CachedCustomerEntity(
    val id: String,
    val displayName: String,
    val addressLine1: String? = null,
    val city: String? = null,
    val stateCode: String? = null,
    val postalCode: String? = null,
    val cachedAt: Long = System.currentTimeMillis()
)

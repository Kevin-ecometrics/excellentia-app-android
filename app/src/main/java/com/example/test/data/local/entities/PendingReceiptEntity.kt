package com.example.test.data.local.entities

// Backlog cliente (2026-09-22) — recepción offline: el almacén tiene pésima
// conexión. Mismo patrón que PendingBatchEntity (venta) — el request completo
// se serializa a JSON y se reintenta después, en vez de modelar cada campo
// como columna propia.
data class PendingReceiptEntity(
    val id: Int = 0,
    val requestJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

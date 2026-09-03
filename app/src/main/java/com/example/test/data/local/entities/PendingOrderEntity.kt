package com.example.test.data.local.entities

data class PendingOrderEntity(
    val id: Int = 0,
    val barcode: String,
    val productName: String,
    val shortName: String? = null,
    val price: Double,
    val quantity: Double,
    val deviceId: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val customerId: String? = null,
    val customerName: String? = null,
    val unit: String? = null,
    val caseQty: Int? = null,
    // Fase 86 — fila de crédito (agregada vía botón "+ Agregar crédito" en
    // CurrentOrderActivity), no un producto vendible. Vive en la misma tabla
    // que el carrito normal para heredar la persistencia SQLite ya existente
    // (sobrevive cierre de la app) sin tabla/DAO paralelos.
    val isCredit: Boolean = false,
    // Fase 115.5 — producto marcado para facturarse a QBO a $0. price/quantity
    // siguen siendo el valor real de catálogo (reportería, "cuánto se
    // regaló") — el $0 se aplica recién al armar la factura en el backend.
    val isCourtesy: Boolean = false
)

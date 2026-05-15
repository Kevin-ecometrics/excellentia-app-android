package com.example.test.data

import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Product(
    val barcode: String,
    val name: String,
    val price: Double,
    val weightPerUnit: Double? = null
)

enum class SyncStatus { PENDING, SENT }

data class ScanEntry(
    val barcode: String,
    val productName: String,
    val price: Double,
    val quantity: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val status: SyncStatus = SyncStatus.PENDING
) {
    val formattedTime: String
        get() = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestamp))

    val formattedDate: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
}

// ── API Response Models ──

data class ApiResponse<T>(
    val data: T? = null,
    val error: String? = null,
    val meta: PaginationMeta? = null
)

data class PaginationMeta(
    val page: Int,
    val limit: Int,
    val total: Int
)

data class ProductDto(
    val id: Int,
    val barcode: String?,
    val name: String,
    val price: Double,
    @SerializedName("qb_item_id") val qbItemId: String? = null,
    val category: String? = null,
    val brand: String? = null,
    val stock: Int = 0,
    @SerializedName("weight_per_unit") val weightPerUnit: Double? = null
) {
    fun toProduct(): Product = Product(
        barcode = barcode ?: "unknown",
        name = name,
        price = price,
        weightPerUnit = weightPerUnit
    )
}

data class CreateOrderRequest(
    val barcode: String,
    @SerializedName("product_name") val productName: String,
    val price: Double,
    val quantity: Double,
    val total: Double,
    @SerializedName("device_id") val deviceId: Int? = null
)

data class OrderResponse(
    val id: Int,
    val barcode: String,
    val status: String
)

data class OrderDto(
    val id: Int,
    val barcode: String,
    @SerializedName("product_name") val productName: String,
    val price: Double,
    val quantity: Double,
    val total: Double,
    val status: String,
    @SerializedName("batch_id") val batchId: String? = null,
    @SerializedName("qb_invoice_id") val qbInvoiceId: String? = null,
    @SerializedName("device_id") val deviceId: Int? = null,
    @SerializedName("user_id") val userId: Int? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("customer_id") val customerId: String? = null,
    @SerializedName("customer_name") val customerName: String? = null
)

data class DeviceRegisterRequest(
    val name: String? = null,
    val model: String? = null,
    @SerializedName("serial_number") val serialNumber: String
)

data class DeviceResponse(
    val id: Int,
    @SerializedName("serial_number") val serialNumber: String,
    val message: String? = null
)

data class ScanRequest(
    val barcode: String,
    @SerializedName("device_id") val deviceId: Int? = null
)

data class ScanResponse(
    val id: Int,
    val barcode: String
)

// ── Refresh Token Models ──

data class RefreshRequest(
    @SerializedName("refreshToken") val refreshToken: String
)

data class RefreshResponse(
    val token: String,
    val refreshToken: String
)

// ── Batch Models ──

data class BatchItem(
    val barcode: String,
    @SerializedName("product_name") val productName: String,
    val price: Double,
    val quantity: Double,
    val total: Double
)

data class BatchRequest(
    val items: List<BatchItem>,
    @SerializedName("customer_id") val customerId: String? = null,
    @SerializedName("customer_name") val customerName: String? = null
)

// ── QuickBooks Customer Models ──

data class QbCustomersResponse(
    @SerializedName("QueryResponse") val queryResponse: QbQueryResponse?
)

data class QbQueryResponse(
    @SerializedName("Customer") val customers: List<QbCustomer>?
)

data class QbCustomer(
    @SerializedName("Id") val id: String,
    @SerializedName("DisplayName") val displayName: String,
    @SerializedName("Active") val active: Boolean = true
)


data class BatchResponse(
    @SerializedName("batchId") val batchId: String,
    @SerializedName("invoiceId") val invoiceId: String? = null,
    val orders: List<OrderResponse>
)

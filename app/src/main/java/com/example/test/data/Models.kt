package com.example.test.data

import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Product(
    val barcode: String,
    val name: String,
    val price: Double,
    val weightPerUnit: Double? = null,
    val stock: Int = 0,
    val unit: String? = null,
    val qty: Int = 0,
    val caseQty: Int? = null
)

enum class SyncStatus { PENDING, SENT, FAILED }

data class ScanEntry(
    val barcode: String,
    val productName: String,
    val price: Double,
    val quantity: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val status: SyncStatus = SyncStatus.PENDING,
    val unit: String? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestamp))

    val formattedDate: String
        get() = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(Date(timestamp))
}

// ── API Response Models ──

data class ApiResponse<T>(
    val data: T? = null,
    val error: String? = null,
    val meta: PaginationMeta? = null,
    val signature: String? = null
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
    @SerializedName("min_price") val minPrice: Double? = null,
    @SerializedName("qb_item_id") val qbItemId: String? = null,
    val category: String? = null,
    val brand: String? = null,
    val stock: Int = 0,
    @SerializedName("weight_per_unit") val weightPerUnit: Double? = null,
    val unit: String? = null,
    @SerializedName("case_qty") val caseQty: Int? = null,
    val qty: Int = 0
) {
    fun toProduct(): Product = Product(
        barcode = barcode ?: "unknown",
        name = name,
        price = price,
        weightPerUnit = weightPerUnit,
        stock = stock,
        unit = unit,
        qty = qty,
        caseQty = caseQty
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
    @SerializedName("customer_name") val customerName: String? = null,
    val unit: String? = null
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
    val total: Double,
    val unit: String? = null
)

// ── Ticket grouping (consolida líneas repetidas del mismo producto) ──

data class GroupedTicketItem(
    val barcode: String,
    val productName: String,
    val quantity: Double,
    val total: Double,
    val unit: String? = null
)

@JvmName("groupedOrdersForTicket")
fun List<OrderDto>.groupedForTicket(): List<GroupedTicketItem> {
    val groups = LinkedHashMap<String, GroupedTicketItem>()
    for (o in this) {
        val key = o.barcode.ifBlank { o.productName }
        val existing = groups[key]
        groups[key] = if (existing == null) {
            GroupedTicketItem(o.barcode, o.productName, o.quantity, o.total, o.unit)
        } else {
            existing.copy(quantity = existing.quantity + o.quantity, total = existing.total + o.total)
        }
    }
    return groups.values.toList()
}

@JvmName("groupedBatchItemsForTicket")
fun List<BatchItem>.groupedForTicket(): List<GroupedTicketItem> {
    val groups = LinkedHashMap<String, GroupedTicketItem>()
    for (i in this) {
        val key = i.barcode.ifBlank { i.productName }
        val existing = groups[key]
        groups[key] = if (existing == null) {
            GroupedTicketItem(i.barcode, i.productName, i.quantity, i.total, i.unit)
        } else {
            existing.copy(quantity = existing.quantity + i.quantity, total = existing.total + i.total)
        }
    }
    return groups.values.toList()
}

data class BatchRequest(
    val items: List<BatchItem>,
    @SerializedName("customer_id") val customerId: String? = null,
    @SerializedName("customer_name") val customerName: String? = null,
    @SerializedName("signature") val signature: String? = null,
    @SerializedName("damage_items") val damageItems: List<DamageItem>? = null,
    @SerializedName("payment_method") val paymentMethod: String? = null
)

data class DamageItem(
    val barcode: String,
    @SerializedName("product_name") val productName: String,
    val qty: Int
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
    @SerializedName("Active") val active: Boolean = true,
    @SerializedName("AddressLine1") val addressLine1: String? = null,
    @SerializedName("City") val city: String? = null,
    @SerializedName("StateCode") val stateCode: String? = null,
    @SerializedName("PostalCode") val postalCode: String? = null
) {
    val fullAddress: String?
        get() {
            val line1 = addressLine1?.takeIf { it.isNotBlank() }
            val cityState = buildString {
                if (!city.isNullOrBlank()) append(city)
                if (!stateCode.isNullOrBlank()) { if (isNotEmpty()) append(", "); append(stateCode) }
                if (!postalCode.isNullOrBlank()) { if (isNotEmpty()) append(" "); append(postalCode) }
            }.takeIf { it.isNotBlank() }
            return listOfNotNull(line1, cityState).joinToString(", ").takeIf { it.isNotBlank() }
        }
}


data class BatchResponse(
    @SerializedName("batchId") val batchId: String,
    @SerializedName("invoiceId") val invoiceId: String? = null,
    @SerializedName("invoiceNumber") val invoiceNumber: Int? = null,
    val orders: List<OrderResponse>
)

// ── Auth Models ──

data class ChangePasswordRequest(
    @SerializedName("currentPassword") val currentPassword: String,
    @SerializedName("newPassword") val newPassword: String
)

// ── Stats Models ──

data class StatsKpis(
    val ordersToday: Int = 0,
    val revenueToday: Double = 0.0,
    val revenueTotal: Double = 0.0,
    val pending: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0
)

data class StatsResponse(
    val kpis: StatsKpis
)

// ── Company Settings Models ──

data class CompanySettingsResponse(
    val data: CompanySettingsData?
)

data class CompanySettingsData(
    @SerializedName("company_name") val companyName: String = "EXCELLENTIA",
    val subtitle: String = "Ticket de Venta",
    val address: String? = null,
    val phone: String? = null,
    val city: String? = null,
    val disclaimer: String? = null
)

// ── Price History Models ──

data class ProductHistoryInfo(
    val name: String,
    val barcode: String?,
    val price: Double,
    @SerializedName("min_price") val minPrice: Double? = null
)

data class PriceHistoryItem(
    val price: Double,
    val quantity: Double,
    val total: Double?,
    @SerializedName("batch_id") val batchId: String? = null,
    @SerializedName("invoice_id") val invoiceId: String? = null,
    val date: String? = null
)

data class PriceHistoryResponse(
    val product: ProductHistoryInfo?,
    val history: List<PriceHistoryItem>
)

// ── Pre-Order Models ──

data class PreOrderItem(
    val barcode: String,
    @SerializedName("product_name") val productName: String,
    val price: Double,
    val quantity: Double,
    val total: Double,
    val unit: String? = null
)

data class PreOrderRequest(
    @SerializedName("customer_id") val customerId: String,
    @SerializedName("customer_name") val customerName: String,
    @SerializedName("salesperson_name") val salespersonName: String? = null,
    @SerializedName("scheduled_date") val scheduledDate: String? = null,
    val notes: String? = null,
    val items: List<PreOrderItem>
)

data class PreOrderDto(
    val id: Int,
    @SerializedName("customer_id") val customerId: String,
    @SerializedName("customer_name") val customerName: String,
    @SerializedName("salesperson_name") val salespersonName: String? = null,
    @SerializedName("scheduled_date") val scheduledDate: String? = null,
    val notes: String? = null,
    val status: String,
    @SerializedName("item_count") val itemCount: Int = 0,
    val total: Double = 0.0,
    @SerializedName("created_at") val createdAt: String? = null,
    val items: List<PreOrderItem> = emptyList()
)

data class UserBrief(
    val id: Int,
    val name: String?
)

data class PreOrderResponse(
    val id: Int,
    val status: String
)

data class ConvertPreOrderRequest(
    val signature: String? = null,
    @SerializedName("payment_method") val paymentMethod: String? = null,
    @SerializedName("damage_items") val damageItems: List<DamageItem>? = null
)

data class ConvertPreOrderResponse(
    @SerializedName("batchId") val batchId: String,
    @SerializedName("invoiceId") val invoiceId: String? = null,
    @SerializedName("invoiceNumber") val invoiceNumber: Int? = null,
    @SerializedName("preOrderId") val preOrderId: String
)

// ── Customer Batch Summary ──

data class CustomerBatchSummary(
    @SerializedName("batch_id") val batchId: String,
    @SerializedName("customer_id") val customerId: String,
    @SerializedName("customer_name") val customerName: String,
    @SerializedName("created_at") val createdAt: String? = null,
    val total: Double,
    @SerializedName("qb_invoice_id") val qbInvoiceId: String? = null,
    @SerializedName("item_count") val itemCount: Int = 0,
    val status: String
)

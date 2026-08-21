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
    val caseQty: Int? = null,
    val qbItemId: String? = null,
    val qbActive: Boolean? = null,
    val shortName: String? = null
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
    val sku: String? = null,
    val name: String,
    @SerializedName("short_name") val shortName: String? = null,
    val price: Double,
    @SerializedName("min_price") val minPrice: Double? = null,
    @SerializedName("qb_item_id") val qbItemId: String? = null,
    @SerializedName("qb_active") val qbActive: Boolean? = null,
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
        caseQty = caseQty,
        qbItemId = qbItemId,
        qbActive = qbActive,
        shortName = shortName
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
    val unit: String? = null,
    @SerializedName("case_qty") val caseQty: Int? = null,
    @SerializedName("payment_method") val paymentMethod: String? = null,
    @SerializedName("check_number") val checkNumber: String? = null,
    @SerializedName("credit_applied") val creditApplied: Double? = null,
    @SerializedName("damage_credits") val damageCredits: Double? = null,
    @SerializedName("short_name") val shortName: String? = null
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
    val unit: String? = null,
    @SerializedName("case_qty") val caseQty: Int? = null,
    @Transient val shortName: String? = null
)

// ── Ticket grouping (consolida líneas repetidas del mismo producto) ──

data class GroupedTicketItem(
    val barcode: String,
    val productName: String,
    val quantity: Double,
    val total: Double,
    val unit: String? = null,
    // Unidades por caja (ej. 8 yogurts por Case) — atributo del producto, no de la
    // venta, así que no se suma al agrupar: todas las líneas del mismo barcode
    // comparten el mismo valor.
    val caseQty: Int? = null,
    // Cuántas filas (escaneos/pesadas individuales) se combinaron en esta línea —
    // ej. 2 chicharrones pesados por separado y agrupados en una sola línea de
    // 2.00 lb. Para LBS es la cantidad de unidades físicas que se pesaron, no el
    // peso; para Case/Unit coincide con `quantity` (ver `groupedForTicket`).
    val count: Int = 1
)

@JvmName("groupedOrdersForTicket")
fun List<OrderDto>.groupedForTicket(): List<GroupedTicketItem> {
    val groups = LinkedHashMap<String, GroupedTicketItem>()
    for (o in this) {
        val key = o.barcode.ifBlank { o.productName }
        val existing = groups[key]
        groups[key] = if (existing == null) {
            val displayName = o.shortName?.takeIf { it.isNotBlank() } ?: o.productName
            GroupedTicketItem(o.barcode, displayName, o.quantity, o.total, o.unit, o.caseQty)
        } else {
            existing.copy(quantity = existing.quantity + o.quantity, total = existing.total + o.total, count = existing.count + 1)
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
            val displayName = i.shortName?.takeIf { it.isNotBlank() } ?: i.productName
            GroupedTicketItem(i.barcode, displayName, i.quantity, i.total, i.unit, i.caseQty)
        } else {
            existing.copy(quantity = existing.quantity + i.quantity, total = existing.total + i.total, count = existing.count + 1)
        }
    }
    return groups.values.toList()
}

// ── Case/Unit — tipos fusionados (antes dos tipos separados) ───────────────
// "Case" y "Unit" se fusionaron en un solo tipo, "Case/Unit": products.price es
// el precio total del paquete/caja, products.qty las unidades que trae. Se
// aceptan igual los valores viejos "Case"/"Unit" (datos históricos en
// orders/pre_order_items que no se migraron, y catálogo hasta que corra la
// migración del backend) — todos se tratan como el mismo tipo en toda la app.
fun isCaseUnitType(unit: String?): Boolean =
    unit.equals("Case/Unit", true) || unit.equals("Case", true) || unit.equals("Unit", true)

// ── Créditos/daños: cantidad como peso real (Lbs) vs conteo de piezas ──────
// Espeja isLbsUnit()/formatDamageQty() del backend (creditCalculator.ts) —
// mismo criterio en los dos para que el input en pantalla, el ticket impreso
// y la factura de QBO coincidan. Solo Lbs necesita decimales: Case/Unit y
// Bucket siguen siendo conteos enteros de piezas.
fun isLbsUnit(unit: String?): Boolean = unit.isNullOrBlank() || unit.equals("Lbs", true)

fun formatDamageQty(qty: Double, unit: String?): String =
    if (isLbsUnit(unit)) String.format(Locale.US, "%.2f lb", qty)
    else String.format(Locale.US, "%d unit(s)", qty.toInt())

// ── Ticket: agrupación por categoría de unidad (LBS / CASE/UNIT / BUCKET) ──
// Usado por PrintService.buildCpcl() y TicketDetailActivity.buildReceipt() — misma
// lógica en los dos para que el ticket impreso y la vista en pantalla coincidan.

private val TICKET_CATEGORY_ORDER = listOf("LBS", "CASE/UNIT", "BUCKET")

fun ticketCategoryFor(unit: String?): String = when {
    unit.isNullOrBlank() || unit.equals("Lbs", true) -> "LBS"
    isCaseUnitType(unit) -> "CASE/UNIT"
    else -> unit.uppercase(Locale.US)
}

fun isWeightTicketCategory(category: String): Boolean = category == "LBS"

// Agrupa preservando el orden LBS → CASE → UNIT → BUCKET → otras (alfabético);
// se omite un encabezado de categoría cuando el ticket es de un solo tipo.
fun List<GroupedTicketItem>.byTicketCategory(): List<Pair<String, List<GroupedTicketItem>>> {
    val groups = groupBy { ticketCategoryFor(it.unit) }
    val orderedKeys = TICKET_CATEGORY_ORDER.filter { groups.containsKey(it) } +
        groups.keys.filter { it !in TICKET_CATEGORY_ORDER }.sorted()
    return orderedKeys.map { it to groups.getValue(it) }
}

// ── Ticket: créditos por daño ───────────────────────────────────────────────
// Separado de la agrupación por categoría (arriba) — es una feature distinta.
// `authoritative` es el creditsTotal que devuelve el backend (BatchResponse,
// o batch_damage.amount vía getBatchDamage) — siempre se prioriza sobre la
// suma local, que solo sirve de aproximación antes de tener esa respuesta
// (preview pre-finalizar, o ticket impreso en modo offline).
fun creditsTotalOf(damageItems: List<DamageItem>, authoritative: Double?): Double =
    authoritative ?: damageItems.sumOf { it.qty * it.unitPrice }

data class BatchRequest(
    val items: List<BatchItem>,
    @SerializedName("customer_id") val customerId: String? = null,
    @SerializedName("customer_name") val customerName: String? = null,
    @SerializedName("signature") val signature: String? = null,
    @SerializedName("damage_items") val damageItems: List<DamageItem>? = null,
    @SerializedName("payment_method") val paymentMethod: String? = null,
    @SerializedName("check_number") val checkNumber: String? = null,
    @SerializedName("apply_credit") val applyCredit: Double? = null
)

// Fase 82 — adjunta payment_method/check_number a un batch que ya se mandó
// sin conocerlos todavía (el batch se manda antes del ticket #1, para tener
// el número de factura real ahí; el método de pago se elige después).
data class UpdatePaymentRequest(
    @SerializedName("payment_method") val paymentMethod: String?,
    @SerializedName("check_number") val checkNumber: String?
)

data class DamageItem(
    val barcode: String,
    @SerializedName("product_name") val productName: String,
    // Double, no Int: para productos Lbs esta cantidad es el peso real dañado
    // (ej. 2.35), no un conteo de piezas. Case/Unit y Bucket siguen siendo
    // conteos enteros en la práctica, pero viajan igual como Double.
    val qty: Double,
    // Tipo de venta del producto al momento del daño (Lbs/Case-Unit/Bucket) —
    // determina si `qty` se interpreta como peso o como conteo, tanto para el
    // input en pantalla como para el texto del ticket impreso.
    val unit: String? = null,
    // Valor por unidad usado para estimar el crédito localmente (preview antes
    // de finalizar, y ticket impreso en modo offline). El backend recalcula su
    // propia cifra autoritativa desde el catálogo — este campo nunca se usa
    // para lo financiero/QBO, solo para lo que se muestra en pantalla antes de
    // tener la respuesta del servidor.
    @SerializedName("unit_price") val unitPrice: Double = 0.0
)

// ── Créditos standalone (sin venta asociada) ──

data class CreditItemRequest(
    val barcode: String,
    @SerializedName("product_name") val productName: String,
    // Double por la misma razón que DamageItem.qty — el backend ya acepta
    // decimales (Number(i.qty) en routes/credits.ts) y re-resuelve el unit
    // fresco desde products, no hace falta mandarlo acá.
    val qty: Double
)

data class IssueCreditRequest(
    @SerializedName("customer_id") val customerId: String,
    @SerializedName("customer_name") val customerName: String?,
    val items: List<CreditItemRequest>
)

data class IssueCreditResponse(
    @SerializedName("batchId") val batchId: String,
    @SerializedName("creditsTotal") val creditsTotal: Double
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
    val orders: List<OrderResponse>,
    @SerializedName("creditsTotal") val creditsTotal: Double? = null,
    @SerializedName("creditApplied") val creditApplied: Double? = null,
    // Nunca lo manda el servidor (queda null en respuestas reales) — lo usa
    // OrderRepository.saveOfflineBatch() para devolver el id de la fila local
    // en pending_batches, así se le puede "pegar" el payment_method después
    // sin depender de un batchId real del servidor (Fase 82).
    val localPendingId: Long? = null
)

data class RetryBatchResponse(
    @SerializedName("batchId") val batchId: String,
    val status: String,
    @SerializedName("invoiceId") val invoiceId: String? = null,
    @SerializedName("invoiceNumber") val invoiceNumber: Int? = null
)

data class ApiErrorBody(val error: String? = null)

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

// quantity/unit/caseQty ahora se capturan al crear la pre-orden (mismo stepper de
// ProductDetailActivity, reusado desde CreatePreOrderActivity) — pero price/total son
// solo un preview en ese momento: el precio se vuelve a consultar del catálogo recién
// al convertir (ver PreOrderDetailActivity.finalizeItem()/quickFinalizeItem()), porque
// una pre-orden puede quedar semanas en DRAFT antes de la entrega. Pre-órdenes viejas
// (o creadas vía "Reusar pre-orden") pueden seguir llegando con todos estos campos en
// null — sigue siendo válido y usa el fallback de siempre (stepper completo).
data class PreOrderItem(
    val barcode: String,
    @SerializedName("product_name") val productName: String,
    val price: Double? = null,
    val quantity: Double? = null,
    val total: Double? = null,
    val unit: String? = null,
    @SerializedName("case_qty") val caseQty: Int? = null,
    @SerializedName("short_name") val shortName: String? = null
)

data class PreOrderRequest(
    @SerializedName("customer_id") val customerId: String,
    @SerializedName("customer_name") val customerName: String,
    @SerializedName("salesperson_name") val salespersonName: String? = null,
    @SerializedName("scheduled_date") val scheduledDate: String? = null,
    val notes: String? = null,
    val items: List<PreOrderItem>,
    // id real del vendedor elegido en el picker (no solo su nombre) — el backend lo
    // usa como assigned_user_id: junto con el creador, es el único no-admin que
    // puede ver esta pre-orden (ver CreatePreOrderActivity.showSalespersonPicker()).
    @SerializedName("assigned_user_id") val assignedUserId: Int? = null
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
    @SerializedName("damage_items") val damageItems: List<DamageItem>? = null,
    @SerializedName("check_number") val checkNumber: String? = null,
    @SerializedName("apply_credit") val applyCredit: Double? = null,
    // Detalle finalizado (precio/peso/case) capturado el día de la conversión —
    // la pre-orden se creó sin esto, ver PreOrderItem.
    val items: List<PreOrderItem>
)

data class ConvertPreOrderResponse(
    @SerializedName("batchId") val batchId: String,
    @SerializedName("invoiceId") val invoiceId: String? = null,
    @SerializedName("invoiceNumber") val invoiceNumber: Int? = null,
    @SerializedName("preOrderId") val preOrderId: String,
    @SerializedName("creditsTotal") val creditsTotal: Double? = null,
    @SerializedName("creditApplied") val creditApplied: Double? = null
)

data class CreditBalance(
    @SerializedName("customer_id") val customerId: String,
    val balance: Double,
    @SerializedName("earned_total") val earnedTotal: Double,
    @SerializedName("used_total") val usedTotal: Double
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

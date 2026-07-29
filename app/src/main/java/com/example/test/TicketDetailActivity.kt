package com.example.test

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.Base64
import android.widget.ImageView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import androidx.activity.enableEdgeToEdge
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.BatchItem
import com.example.test.data.DamageItem
import com.example.test.data.OrderDto
import com.example.test.data.byTicketCategory
import com.example.test.data.creditsTotalOf
import com.example.test.data.groupedForTicket
import com.example.test.data.isWeightTicketCategory
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.example.test.data.print.PrintService
import com.example.test.data.repository.OrderRepository
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TicketDetailActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase, "en"))
    }

    private lateinit var ticketContent: LinearLayout
    private val dp get() = resources.displayMetrics.density
    private var damageItemsForReprint: List<DamageItem> = emptyList()
    private var signatureForReprint: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_ticket_detail)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        ticketContent = findViewById(R.id.ticketContent)

        val ordersJson = intent.getStringExtra("orders_json") ?: "[]"
        val orders: List<OrderDto> = Gson().fromJson(ordersJson, Array<OrderDto>::class.java).toList()
        val batchId         = intent.getStringExtra("batch_id") ?: ""
        val invoiceId       = intent.getStringExtra("invoice_id") ?: ""
        val invoiceNumber   = intent.getIntExtra("invoice_number", 0).takeIf { it > 0 }
        val customerName    = intent.getStringExtra("customer_name") ?: orders.firstOrNull()?.customerName
        var customerAddress = intent.getStringExtra("customer_address")
        val paymentMethod   = orders.firstOrNull()?.paymentMethod
        val checkNumber     = orders.firstOrNull()?.checkNumber
        val creditApplied   = orders.firstOrNull()?.creditApplied
        val rawDate         = orders.firstOrNull()?.createdAt
        val grandTotal      = orders.sumOf { it.total }
        val totalQty        = orders.sumOf { it.quantity }
        var orderStatus = when {
            orders.all { it.status == "SENT" }    -> "SENT"
            orders.any { it.status == "PENDING" } -> "PENDING"
            orders.any { it.status == "FAILED" }  -> "FAILED"
            else                                  -> null
        }

        signatureForReprint = intent.getStringExtra("signature")

        val prefs = SecurePreferences(this)
        val companyName = prefs.getCompanyName()
        val orderRepository = OrderRepository(AppDatabase.getInstance(this), prefs)

        // Cargar damage items desde el intent (flujo inmediato)
        val intentDamage = intent.getStringExtra("damage_items_json")
        val initialDamage: List<DamageItem> = if (!intentDamage.isNullOrBlank()) {
            try { Gson().fromJson(intentDamage, Array<DamageItem>::class.java).toList() }
            catch (_: Exception) { emptyList() }
        } else emptyList()

        damageItemsForReprint = initialDamage

        buildReceipt(
            companyName       = companyName,
            subtitle          = prefs.getCompanySubtitle(),
            city              = prefs.getCompanyCity(),
            address           = prefs.getCompanyAddress(),
            phone             = prefs.getCompanyPhone(),
            date              = formatDate(rawDate),
            batchId           = batchId,
            invoiceId         = invoiceId,
            invoiceNumber     = invoiceNumber,
            customerName      = customerName,
            customerAddress   = customerAddress,
            paymentMethod     = paymentMethod,
            checkNumber       = checkNumber,
            creditApplied     = creditApplied,
            orders            = orders,
            grandTotal        = grandTotal,
            totalQty          = totalQty,
            companyNameFooter = companyName,
            signature         = signatureForReprint,
            damageItems       = initialDamage,
            status            = orderStatus
        )

        // Si hay batchId, cargar firma + damage desde API y actualizar recibo

        if (batchId.isNotBlank()) {
            lifecycleScope.launch {
                try {
                    if (!RetrofitClient.isInitialized()) {
                        RetrofitClient.initialize(prefs.getBackendUrl(), prefs, this@TicketDetailActivity)
                    }
                    val resp = RetrofitClient.getApi().getBatchDamage(batchId)
                    var damageChanged = false
                    var signatureChanged = false
                    if (resp.isSuccessful) {
                        val body = resp.body()
                        val apiDamage = body?.data ?: emptyList()
                        val apiSignature = body?.signature

                        damageChanged = apiDamage.isNotEmpty() && apiDamage != damageItemsForReprint
                        signatureChanged = !apiSignature.isNullOrBlank() && apiSignature != signatureForReprint

                        if (damageChanged)    damageItemsForReprint = apiDamage
                        if (signatureChanged) signatureForReprint  = apiSignature
                    }

                    // La dirección del cliente no se persiste en `orders` (solo
                    // customer_id) — si no vino en el intent (reprint desde
                    // Historial/ClientHistory, que no la pasan), se resuelve al
                    // vuelo contra el cliente de QB para que el reprint sí la
                    // incluya.
                    var addressChanged = false
                    val customerId = orders.firstOrNull()?.customerId
                    if (customerAddress.isNullOrBlank() && !customerId.isNullOrBlank()) {
                        val custResp = RetrofitClient.getApi().getCustomer(customerId)
                        val fetchedAddress = custResp.takeIf { it.isSuccessful }?.body()?.fullAddress
                        if (!fetchedAddress.isNullOrBlank()) {
                            customerAddress = fetchedAddress
                            addressChanged = true
                        }
                    }

                    if (damageChanged || signatureChanged || addressChanged) {
                        ticketContent.removeAllViews()
                        buildReceipt(
                            companyName       = companyName,
                            subtitle          = prefs.getCompanySubtitle(),
                            city              = prefs.getCompanyCity(),
                            address           = prefs.getCompanyAddress(),
                            phone             = prefs.getCompanyPhone(),
                            date              = formatDate(rawDate),
                            batchId           = batchId,
                            invoiceId         = invoiceId,
                            invoiceNumber     = invoiceNumber,
                            customerName      = customerName,
                            customerAddress   = customerAddress,
                            paymentMethod     = paymentMethod,
                            checkNumber       = checkNumber,
                            creditApplied     = creditApplied,
                            orders            = orders,
                            grandTotal        = grandTotal,
                            totalQty          = totalQty,
                            companyNameFooter = companyName,
                            signature         = signatureForReprint,
                            damageItems       = damageItemsForReprint,
                            status            = orderStatus
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("TicketDetail", "Error cargando batch desde API: ${e.message}", e)
                }
            }
        }

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        // Botón reimprimir
        val btnReprint = findViewById<MaterialButton>(R.id.btnReprint)
        val printerAddress = prefs.getPrinterAddress()
        if (!printerAddress.isNullOrBlank()) {
            btnReprint.visibility = android.view.View.VISIBLE
            btnReprint.setOnClickListener {
                btnReprint.isEnabled = false
                btnReprint.text = getString(R.string.btn_printing)
                val items = orders.map { o ->
                    BatchItem(barcode = o.barcode, productName = o.productName,
                              price = o.price, quantity = o.quantity, total = o.total,
                              unit = o.unit, caseQty = o.caseQty, shortName = o.shortName)
                }
                lifecycleScope.launch {
                    val result = PrintService.printTicket(
                        context         = this@TicketDetailActivity,
                        deviceAddress   = printerAddress,
                        items           = items,
                        customerName    = customerName,
                        batchId         = batchId,
                        invoiceId       = invoiceId,
                        invoiceNumber   = invoiceNumber,
                        customerAddress = customerAddress,
                        damageItems     = damageItemsForReprint,
                        paymentMethod   = paymentMethod,
                        checkNumber     = checkNumber,
                        signature       = signatureForReprint,
                        creditApplied   = creditApplied
                    )
                    result.onSuccess {
                        Snackbar.make(findViewById(android.R.id.content),
                            getString(R.string.msg_ticket_sent), Snackbar.LENGTH_SHORT).show()
                    }.onFailure { e ->
                        Snackbar.make(findViewById(android.R.id.content),
                            getString(R.string.error_print_generic, e.localizedMessage ?: ""), Snackbar.LENGTH_LONG).show()
                    }
                    btnReprint.isEnabled = true
                    btnReprint.text = getString(R.string.btn_reprint)
                }
            }
        }

        // Botón reintentar envío a QuickBooks — solo para batches remotos que
        // no terminaron SENT (PENDING o FAILED).
        val btnRetryQbo = findViewById<MaterialButton>(R.id.btnRetryQbo)
        if (batchId.isNotBlank() && orderStatus != null && orderStatus != "SENT") {
            btnRetryQbo.visibility = android.view.View.VISIBLE
            btnRetryQbo.setOnClickListener {
                btnRetryQbo.isEnabled = false
                btnRetryQbo.text = getString(R.string.btn_retrying_qbo)
                lifecycleScope.launch {
                    val result = orderRepository.retryBatchSync(batchId)
                    result.onSuccess { response ->
                        Snackbar.make(
                            findViewById(android.R.id.content),
                            getString(R.string.msg_qbo_retry_success, (response.invoiceNumber ?: response.invoiceId ?: "").toString()),
                            Snackbar.LENGTH_LONG
                        ).show()
                        orderStatus = response.status
                        btnRetryQbo.visibility = if (response.status == "SENT") android.view.View.GONE else android.view.View.VISIBLE
                        ticketContent.removeAllViews()
                        buildReceipt(
                            companyName       = companyName,
                            subtitle          = prefs.getCompanySubtitle(),
                            city              = prefs.getCompanyCity(),
                            address           = prefs.getCompanyAddress(),
                            phone             = prefs.getCompanyPhone(),
                            date              = formatDate(rawDate),
                            batchId           = batchId,
                            invoiceId         = response.invoiceId ?: invoiceId,
                            invoiceNumber     = response.invoiceNumber ?: invoiceNumber,
                            customerName      = customerName,
                            customerAddress   = customerAddress,
                            paymentMethod     = paymentMethod,
                            checkNumber       = checkNumber,
                            creditApplied     = creditApplied,
                            orders            = orders,
                            grandTotal        = grandTotal,
                            totalQty          = totalQty,
                            companyNameFooter = companyName,
                            signature         = signatureForReprint,
                            damageItems       = damageItemsForReprint,
                            status            = orderStatus
                        )
                    }
                    result.onFailure { e ->
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@TicketDetailActivity)
                            .setTitle(getString(R.string.title_qbo_retry_error))
                            .setMessage(getString(R.string.error_qbo_retry_generic, e.localizedMessage ?: getString(R.string.error_unknown)))
                            .setPositiveButton(getString(R.string.btn_understood), null)
                            .show()
                    }
                    btnRetryQbo.isEnabled = true
                    btnRetryQbo.text = getString(R.string.btn_retry_qbo)
                }
            }
        }
    }

    // ── Receipt builder ───────────────────────────────────────────────────────

    private fun buildReceipt(
        companyName: String, subtitle: String,
        city: String?, address: String?, phone: String?,
        date: String, batchId: String, invoiceId: String,
        invoiceNumber: Int? = null,
        customerName: String?, customerAddress: String?,
        paymentMethod: String? = null, checkNumber: String? = null, creditApplied: Double? = null,
        orders: List<OrderDto>,
        grandTotal: Double, totalQty: Double,
        companyNameFooter: String,
        signature: String?,
        damageItems: List<DamageItem> = emptyList(),
        status: String?
    ) {
        // ── Cabecera empresa ────────────────────────────
        addLine(companyName, bold = true, sizeSp = 13f)
        addLine(subtitle, sizeSp = 12f)
        if (!address.isNullOrBlank()) addLine(address, sizeSp = 12f)
        if (!city.isNullOrBlank())    addLine(city,    sizeSp = 12f)
        if (!phone.isNullOrBlank())   addLine(phone,   sizeSp = 12f)

        // ── Info del pedido ─────────────────────────────
        addSep(heavy = true)
        addLine(date, sizeSp = 12f)
        val displayInvoice = invoiceNumber?.toString() ?: invoiceId.takeIf { it.isNotBlank() }
        if (displayInvoice != null) addLine("Invoice #$displayInvoice", sizeSp = 12f)

        // ── Cliente ─────────────────────────────────────
        if (!customerName.isNullOrBlank()) {
            addSep(heavy = false)
            addLine("Customer: $customerName", sizeSp = 12f)
            if (!customerAddress.isNullOrBlank()) {
                val ci = customerAddress.indexOf(", ")
                if (ci > 0 && customerAddress.length > 28) {
                    addLine(customerAddress.substring(0, ci), sizeSp = 12f, indent = true)
                    addLine(customerAddress.substring(ci + 2), sizeSp = 12f, indent = true)
                } else {
                    addLine(customerAddress, sizeSp = 12f, indent = true)
                }
            }
        }

        // ── Payment ─────────────────────────────────────
        if (!paymentMethod.isNullOrBlank()) {
            addLine("Payment: $paymentMethod", sizeSp = 12f)
            if ("Check".equals(paymentMethod, ignoreCase = true) && !checkNumber.isNullOrBlank()) {
                addLine("Check #: $checkNumber", sizeSp = 12f, indent = true)
            }
        }

        // ── Ítems (agrupados por producto, y por categoría LBS/CASE-UNIT/BUCKET) ──
        // "N - Nombre del producto" — N = cantidad de veces que se seleccionó/
        // escaneó este producto (cajas/buckets elegidos, o pesadas individuales
        // agrupadas para Lbs) — pedido explícito del usuario (Fase 97, no es la
        // misma cantidad que Qty/W, que ya viene multiplicada/expandida). Seguido
        // de "Qty/Weight   Rate   Total" en 3 columnas.
        addSep(heavy = true)
        addLine("Desc", bold = true, sizeSp = 11f)
        addThreeCol("Qty/W", "Rate", "Total", bold = true, sizeSp = 11f)
        val groupedByCategory = orders.groupedForTicket().byTicketCategory()
        for ((category, group) in groupedByCategory) {
            // Header de categoría (Fase 98, siempre visible; Fase 100, enmarcado
            // con separador arriba/abajo) — antes el nombre de categoría (ej. "LBS")
            // quedaba pegado al resto del texto y se perdía visualmente; ahora
            // queda enmarcado como su propia sección.
            addSep(heavy = false)
            addLine(category, bold = true, sizeSp = 11f)
            addSep(heavy = false)
            for (g in group) {
                // Qty/W = cantidad total real seleccionada (Fase 96, pedido del
                // usuario): Case/Unit multiplica por unidades por caja (caseQty) —
                // 3 cajas de 12 = 36; Lbs/Bucket ya traen el total real en
                // `quantity` (peso sumado / conteo de buckets), sin multiplicar. El
                // rate se recalcula sobre esta cantidad (no sobre el número de
                // cajas/pesadas) para que rate × qty siga dando el total de la línea.
                val displayQty = if (category == "CASE/UNIT") {
                    g.quantity * (g.caseQty?.takeIf { it > 0 } ?: 1)
                } else {
                    g.quantity
                }
                val avgPrice = if (displayQty != 0.0) g.total / displayQty else 0.0
                // Cantidad seleccionada (prefijo del nombre, no confundir con
                // displayQty de arriba): Lbs = cuántas pesadas individuales se
                // agruparon en esta línea; Case/Unit y Bucket = cuántas unidades se
                // eligieron (antes de multiplicar por unidades por caja).
                val pickCount = if (isWeightTicketCategory(category)) g.count else g.quantity.toInt()
                addLine("$pickCount - ${g.productName}", sizeSp = 12f)
                // Fase 100/101 — indicador corto de unidad en Qty/W.
                val qtyStr = if (isWeightTicketCategory(category))
                    String.format(Locale.US, "%.2f lb", displayQty)
                else
                    String.format(Locale.US, "%d %s", displayQty.toInt(), shortQtyUnit(category))
                addThreeCol(
                    left  = qtyStr,
                    mid   = String.format(Locale.US, "\$%.2f", avgPrice),
                    right = String.format(Locale.US, "\$%.2f", g.total),
                    sizeSp = 12f
                )
                addBlank(4)
            }
        }

        // ── Total ───────────────────────────────────────
        val credits = creditsTotalOf(damageItems, authoritative = null)
        val creditAppliedVal = creditApplied ?: 0.0
        addSep(heavy = true)
        if (credits > 0) {
            addTwoCol(left = "Subtotal:", right = String.format(Locale.US, "\$%.2f", grandTotal), sizeSp = 12f)
            addTwoCol(left = "Credits:", right = String.format(Locale.US, "-\$%.2f", credits), sizeSp = 12f)
        }
        val displayTotal = if (creditAppliedVal > 0) {
            addTwoCol(left = "Credit Applied:", right = String.format(Locale.US, "-\$%.2f", creditAppliedVal), sizeSp = 12f)
            grandTotal - credits - creditAppliedVal
        } else {
            grandTotal - credits
        }
        addTwoCol(
            left   = "TOTAL:",
            right  = String.format(Locale.US, "\$%.2f", displayTotal),
            bold   = true,
            sizeSp = 13f
        )
        // Con una sola categoría se puede sumar cantidad + unidad ("22.80 lb total").
        // Mezclando lb + case + unit no hay una suma que tenga sentido — se muestra
        // cantidad de productos en su lugar.
        val qtyLine = if (groupedByCategory.size <= 1) {
            val category = groupedByCategory.firstOrNull()?.first ?: "LBS"
            val overallUnit = orders.firstOrNull()?.let { unitLabel(it.unit) } ?: "lb"
            if (isWeightTicketCategory(category))
                String.format(Locale.US, "%.2f %s total", totalQty, overallUnit)
            else
                String.format(Locale.US, "%d %s total", totalQty.toInt(), overallUnit)
        } else {
            "${orders.groupedForTicket().size} items total"
        }
        addLine(qtyLine, sizeSp = 12f)
        addLine(companyNameFooter, sizeSp = 12f)

        // ── Negative Sale Summary ────────────────────────
        val hasDamage = damageItems.any { it.qty > 0 }
        if (hasDamage) {
            addSep(heavy = false)
            addLine("Negative Sale Summary:", bold = true, sizeSp = 12f)
            for (dmg in damageItems.filter { it.qty > 0 }) {
                addLine(
                    "${dmg.productName}: ${dmg.qty} unit(s) · ${String.format(Locale.US, "-\$%.2f", dmg.qty * dmg.unitPrice)}",
                    sizeSp = 12f, indent = true
                )
            }
        }

        // ── Términos (QR) ─────────────────────────────────
        // El texto legal completo se reemplazó por un QR que apunta a la
        // página web con el disclaimer — mismo criterio que el ticket impreso.
        addSep(heavy = false)
        addLine("Terms & Conditions: Scan to view", sizeSp = 10f)
        ticketContent.addView(ImageView(this).apply {
            setImageResource(R.drawable.disclaimer_qr)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            layoutParams = LinearLayout.LayoutParams((110 * dp).toInt(), (110 * dp).toInt()).apply {
                topMargin    = (6 * dp).toInt()
                bottomMargin = (2 * dp).toInt()
            }
        })
        ticketContent.addView(TextView(this).apply {
            text = "https://excellentiafoods.com/terms-and-conditions/"
            textSize = 9f
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                bottomMargin = (6 * dp).toInt()
            }
        })

        // ── Firma ───────────────────────────────────────
        addSep(heavy = false)
        addLine("Customer Signature:", sizeSp = 12f)
        if (!signature.isNullOrBlank()) {
            try {
                val bytes = Base64.decode(signature, Base64.DEFAULT)
                val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ticketContent.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_START
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        topMargin    = (8 * dp).toInt()
                        bottomMargin = (8 * dp).toInt()
                    }
                })
            } catch (_: Exception) {
                addBlank(48)
            }
        } else {
            addBlank(48)
        }

        // ── Estado ──────────────────────────────────────
        if (status != null) {
            addSep(heavy = false)
            val statusColor = when (status) {
                "SENT"    -> Color.parseColor("#2E7D32")
                "PENDING" -> Color.parseColor("#E65100")
                else      -> Color.parseColor("#B71C1C")
            }
            addLine(status, bold = true, sizeSp = 12f, color = statusColor)
        }
    }

    private fun unitLabel(unit: String?): String = when {
        unit.isNullOrBlank() || unit == "Lbs" -> "lb"
        com.example.test.data.isCaseUnitType(unit) -> "Case/Unit"
        else -> unit
    }

    // Indicador corto de unidad para la columna Qty/W (Fase 101) — a diferencia
    // de unitLabel() (nombre completo, usado en el header de categoría), acá va
    // abreviado porque comparte fila con rate/total. Basado en
    // `ticketCategoryFor()` (ya normalizado a mayúsculas), no en el `unit` crudo.
    private fun shortQtyUnit(category: String): String = when (category) {
        "CASE/UNIT" -> "cs/unt"
        "BUCKET" -> "bkt"
        else -> category.take(3).lowercase(Locale.US)
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private fun addLine(
        text: String,
        bold: Boolean = false,
        sizeSp: Float = 12f,
        indent: Boolean = false,
        color: Int = Color.BLACK
    ) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                       else Typeface.MONOSPACE
            val leftPad = if (indent) (12 * dp).toInt() else 0
            setPadding(leftPad, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = (2 * dp).toInt()
            }
        }
        ticketContent.addView(tv)
    }

    // Fila de 3 columnas para el detalle de ítem (qty/weight, rate, total) —
    // mid/right con ancho fijo alineadas a la derecha, left toma el resto.
    private fun addThreeCol(left: String, mid: String, right: String, bold: Boolean = false, sizeSp: Float = 12f, midWidth: Int = 8, rightWidth: Int = 9) {
        val tf = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = tf
            this.textSize = sizeSp * resources.displayMetrics.scaledDensity
        }
        val midW = paint.measureText("M".repeat(midWidth)).toInt()
        val rightW = paint.measureText("M".repeat(rightWidth)).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = (2 * dp).toInt()
            }
        }
        row.addView(TextView(this).apply {
            text = left; textSize = sizeSp; setTextColor(Color.BLACK); typeface = tf
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = mid; textSize = sizeSp; setTextColor(Color.BLACK); typeface = tf
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(midW, WRAP_CONTENT).apply {
                marginStart = (12 * dp).toInt()
            }
        })
        row.addView(TextView(this).apply {
            text = right; textSize = sizeSp; setTextColor(Color.BLACK); typeface = tf
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(rightW, WRAP_CONTENT).apply {
                marginStart = (12 * dp).toInt()
            }
        })
        ticketContent.addView(row)
    }

    private fun addTwoCol(left: String, right: String, bold: Boolean = false, sizeSp: Float = 12f) {
        val tf = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = (2 * dp).toInt()
            }
        }
        row.addView(TextView(this).apply {
            text = left; textSize = sizeSp; setTextColor(Color.BLACK); typeface = tf
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = right; textSize = sizeSp; setTextColor(Color.BLACK); typeface = tf
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        })
        ticketContent.addView(row)
    }

    private fun addSep(heavy: Boolean = true) {
        ticketContent.addView(TextView(this).apply {
            text = if (heavy) "================================" else "--------------------------------"
            textSize = 12f
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin    = (4 * dp).toInt()
                bottomMargin = (4 * dp).toInt()
            }
        })
    }

    private fun addBlank(heightDp: Int = 8) {
        ticketContent.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, (heightDp * dp).toInt())
        })
    }

    // ── Date formatter ────────────────────────────────────────────────────────

    private fun formatDate(rawDate: String?): String {
        if (rawDate.isNullOrBlank()) {
            return SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.US).format(Date())
        }
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.US).format(parser.parse(rawDate)!!)
        } catch (_: Exception) { rawDate }
    }
}

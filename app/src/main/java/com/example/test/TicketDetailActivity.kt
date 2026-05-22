package com.example.test

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.BatchItem
import com.example.test.data.DamageItem
import com.example.test.data.OrderDto
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.example.test.data.print.PrintService
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TicketDetailActivity : AppCompatActivity() {

    private lateinit var ticketContent: LinearLayout
    private val dp get() = resources.displayMetrics.density
    private var damageItemsForReprint: List<DamageItem> = emptyList()

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
        val customerName    = intent.getStringExtra("customer_name") ?: orders.firstOrNull()?.customerName
        val customerAddress = intent.getStringExtra("customer_address")
        val signature       = intent.getStringExtra("signature") ?: orders.firstOrNull()?.signature
        val rawDate         = orders.firstOrNull()?.createdAt
        val grandTotal      = orders.sumOf { it.total }
        val totalQty        = orders.sumOf { it.quantity }

        val prefs = SecurePreferences(this)
        val companyName = prefs.getCompanyName()

        // Cargar damage items desde el intent (flujo inmediato) o desde la API (historial)
        val intentDamage = intent.getStringExtra("damage_items_json")
        val initialDamage: List<DamageItem> = if (!intentDamage.isNullOrBlank()) {
            try { Gson().fromJson(intentDamage, Array<DamageItem>::class.java).toList() }
            catch (_: Exception) { emptyList() }
        } else emptyList()

        buildReceipt(
            companyName       = companyName,
            subtitle          = prefs.getCompanySubtitle(),
            city              = prefs.getCompanyCity(),
            address           = prefs.getCompanyAddress(),
            phone             = prefs.getCompanyPhone(),
            date              = formatDate(rawDate),
            batchId           = batchId,
            invoiceId         = invoiceId,
            customerName      = customerName,
            customerAddress   = customerAddress,
            orders            = orders,
            grandTotal        = grandTotal,
            totalQty          = totalQty,
            companyNameFooter = companyName,
            signature         = signature,
            damageItems       = initialDamage,
            status            = when {
                orders.all { it.status == "SENT" }     -> "ENVIADO"
                orders.any { it.status == "PENDING" }  -> "PENDIENTE"
                orders.any { it.status == "FAILED" }   -> "FALLIDO"
                else                                   -> null
            }
        )

        // Si viene del historial y hay batchId, cargar damage desde API y actualizar recibo
        damageItemsForReprint = initialDamage
        if (batchId.isNotBlank() && initialDamage.isEmpty()) {
            lifecycleScope.launch {
                try {
                    // Asegurar que RetrofitClient esté inicializado
                    if (!RetrofitClient.isInitialized()) {
                        val baseUrl = prefs.getBackendUrl()
                        RetrofitClient.initialize(baseUrl, prefs, this@TicketDetailActivity)
                    }
                    val resp = RetrofitClient.getApi().getBatchDamage(batchId)
                    android.util.Log.d("DamageDebug", "getBatchDamage resp=${resp.code()} body=${resp.body()?.data?.size} items")
                    if (resp.isSuccessful) {
                        val apiDamage = resp.body()?.data ?: emptyList()
                        if (apiDamage.isNotEmpty()) {
                            damageItemsForReprint = apiDamage
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
                                customerName      = customerName,
                                customerAddress   = customerAddress,
                                orders            = orders,
                                grandTotal        = grandTotal,
                                totalQty          = totalQty,
                                companyNameFooter = companyName,
                                signature         = signature,
                                damageItems       = apiDamage,
                                status            = when {
                                    orders.all { it.status == "SENT" }    -> "ENVIADO"
                                    orders.any { it.status == "PENDING" } -> "PENDIENTE"
                                    orders.any { it.status == "FAILED" }  -> "FALLIDO"
                                    else                                   -> null
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DamageDebug", "Error cargando damage items: ${e.message}", e)
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
                btnReprint.text = "Imprimiendo…"
                val items = orders.map { o ->
                    BatchItem(barcode = o.barcode, productName = o.productName,
                              price = o.price, quantity = o.quantity, total = o.total)
                }
                lifecycleScope.launch {
                    val result = PrintService.printTicket(
                        context         = this@TicketDetailActivity,
                        deviceAddress   = printerAddress,
                        items           = items,
                        customerName    = customerName,
                        batchId         = batchId,
                        invoiceId       = invoiceId,
                        customerAddress = customerAddress,
                        damageItems     = damageItemsForReprint,
                        signature       = signature
                    )
                    result.onSuccess {
                        Snackbar.make(findViewById(android.R.id.content),
                            "Ticket enviado a la impresora", Snackbar.LENGTH_SHORT).show()
                    }.onFailure { e ->
                        Snackbar.make(findViewById(android.R.id.content),
                            "Error al imprimir: ${e.localizedMessage}", Snackbar.LENGTH_LONG).show()
                    }
                    btnReprint.isEnabled = true
                    btnReprint.text = "Reimprimir ticket"
                }
            }
        }
    }

    // ── Receipt builder ───────────────────────────────────────────────────────

    private fun buildReceipt(
        companyName: String, subtitle: String,
        city: String?, address: String?, phone: String?,
        date: String, batchId: String, invoiceId: String,
        customerName: String?, customerAddress: String?,
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
        if (!city.isNullOrBlank())    addLine(city,    sizeSp = 12f)
        if (!address.isNullOrBlank()) addLine(address, sizeSp = 12f)
        if (!phone.isNullOrBlank())   addLine(phone,   sizeSp = 12f)

        // ── Info del pedido ─────────────────────────────
        addSep(heavy = true)
        addLine(date, sizeSp = 12f)
        if (batchId.isNotBlank())    addLine("Pedido  #${batchId.takeLast(8)}", sizeSp = 12f)
        if (invoiceId.isNotBlank())  addLine("Factura #$invoiceId", sizeSp = 12f)

        // ── Cliente ─────────────────────────────────────
        if (!customerName.isNullOrBlank()) {
            addSep(heavy = false)
            addLine("Cliente: $customerName", sizeSp = 12f)
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

        // ── Ítems ───────────────────────────────────────
        addSep(heavy = true)
        for (order in orders) {
            addLine(order.productName, sizeSp = 12f)
            addTwoCol(
                left  = String.format(Locale.US, "%.2f lb x \$%.2f/lb", order.quantity, order.price),
                right = String.format(Locale.US, "\$%.2f", order.total),
                sizeSp = 12f
            )
            addBlank(4)
        }

        // ── Total ───────────────────────────────────────
        addSep(heavy = true)
        addTwoCol(
            left   = "TOTAL:",
            right  = String.format(Locale.US, "\$%.2f", grandTotal),
            bold   = true,
            sizeSp = 13f
        )
        addLine(String.format(Locale.US, "%.2f lb en total", totalQty), sizeSp = 12f)
        addLine(companyNameFooter, sizeSp = 12f)

        // ── Negative Sale Summary ────────────────────────
        val hasDamage = damageItems.any { it.qty > 0 }
        if (hasDamage) {
            addSep(heavy = false)
            addLine("Negative Sale Summary:", bold = true, sizeSp = 12f)
            for (dmg in damageItems.filter { it.qty > 0 }) {
                addLine("${dmg.productName}: ${dmg.qty} unit(s)", sizeSp = 12f, indent = true)
            }
        }

        // ── Términos ────────────────────────────────────
        addSep(heavy = false)
        addLine(
            "I hereby acknowledge that all above referenced goods have been received " +
            "and are in good condition. I also understand that this sale is expressly " +
            "conditioned upon my assent to all terms on the reverse of this page and " +
            "I accept all the terms of this sale.",
            sizeSp = 10f
        )

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
                "ENVIADO"   -> Color.parseColor("#2E7D32")
                "PENDIENTE" -> Color.parseColor("#E65100")
                else        -> Color.parseColor("#B71C1C")
            }
            addLine(status, bold = true, sizeSp = 12f, color = statusColor)
        }
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
            return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date())
        }
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(parser.parse(rawDate)!!)
        } catch (_: Exception) { rawDate }
    }
}

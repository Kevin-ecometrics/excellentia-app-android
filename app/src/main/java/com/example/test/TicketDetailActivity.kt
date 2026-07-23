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
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.BatchItem
import com.example.test.data.DamageItem
import com.example.test.data.OrderDto
import com.example.test.data.groupedForTicket
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
        val customerAddress = intent.getStringExtra("customer_address")
        val rawDate         = orders.firstOrNull()?.createdAt
        val grandTotal      = orders.sumOf { it.total }
        val totalQty        = orders.sumOf { it.quantity }
        val orderStatus = when {
            orders.all { it.status == "SENT" }    -> "SENT"
            orders.any { it.status == "PENDING" } -> "PENDING"
            orders.any { it.status == "FAILED" }  -> "FAILED"
            else                                  -> null
        }

        signatureForReprint = intent.getStringExtra("signature")

        val prefs = SecurePreferences(this)
        val companyName = prefs.getCompanyName()

        // Cargar damage items desde el intent (flujo inmediato)
        val intentDamage = intent.getStringExtra("damage_items_json")
        val initialDamage: List<DamageItem> = if (!intentDamage.isNullOrBlank()) {
            try { Gson().fromJson(intentDamage, Array<DamageItem>::class.java).toList() }
            catch (_: Exception) { emptyList() }
        } else emptyList()

        damageItemsForReprint = initialDamage

        val disclaimerText = prefs.getDisclaimer()

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
            orders            = orders,
            grandTotal        = grandTotal,
            totalQty          = totalQty,
            companyNameFooter = companyName,
            signature         = signatureForReprint,
            damageItems       = initialDamage,
            status            = orderStatus,
            disclaimer        = disclaimerText
        )

        // Si hay batchId, cargar firma + damage desde API y actualizar recibo
        if (batchId.isNotBlank()) {
            lifecycleScope.launch {
                try {
                    if (!RetrofitClient.isInitialized()) {
                        RetrofitClient.initialize(prefs.getBackendUrl(), prefs, this@TicketDetailActivity)
                    }
                    val resp = RetrofitClient.getApi().getBatchDamage(batchId)
                    if (resp.isSuccessful) {
                        val body = resp.body()
                        val apiDamage = body?.data ?: emptyList()
                        val apiSignature = body?.signature

                        val damageChanged = apiDamage.isNotEmpty() && apiDamage != damageItemsForReprint
                        val signatureChanged = !apiSignature.isNullOrBlank() && apiSignature != signatureForReprint

                        if (damageChanged || signatureChanged) {
                            if (damageChanged)   damageItemsForReprint = apiDamage
                            if (signatureChanged) signatureForReprint  = apiSignature
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
                                orders            = orders,
                                grandTotal        = grandTotal,
                                totalQty          = totalQty,
                                companyNameFooter = companyName,
                                signature         = signatureForReprint,
                                damageItems       = damageItemsForReprint,
                                status            = orderStatus,
                                disclaimer        = disclaimerText
                            )
                        }
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
                              unit = o.unit)
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
                        signature       = signatureForReprint
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
    }

    // ── Receipt builder ───────────────────────────────────────────────────────

    private fun buildReceipt(
        companyName: String, subtitle: String,
        city: String?, address: String?, phone: String?,
        date: String, batchId: String, invoiceId: String,
        invoiceNumber: Int? = null,
        customerName: String?, customerAddress: String?,
        orders: List<OrderDto>,
        grandTotal: Double, totalQty: Double,
        companyNameFooter: String,
        signature: String?,
        damageItems: List<DamageItem> = emptyList(),
        status: String?,
        disclaimer: String? = null
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

        // ── Ítems (agrupados por producto) ───────────────
        addSep(heavy = true)
        for (g in orders.groupedForTicket()) {
            val avgPrice = if (g.quantity != 0.0) g.total / g.quantity else 0.0
            val unitLabel = unitLabel(g.unit)
            addLine(g.productName, sizeSp = 12f)
            addTwoCol(
                left  = String.format(Locale.US, "%.2f %s x \$%.2f/%s", g.quantity, unitLabel, avgPrice, unitLabel),
                right = String.format(Locale.US, "\$%.2f", g.total),
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
        val overallUnit = orders.firstOrNull()?.let { unitLabel(it.unit) } ?: "lb"
        addLine(String.format(Locale.US, "%.2f %s total", totalQty, overallUnit), sizeSp = 12f)
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
            disclaimer ?: "Terms and Conditions:\n\n" +
            "(1) Seller retains title to the goods until buyer performs the entire contract and goods have been paid for in full. Seller retains a security interest in the goods, including all additions and replacements, to secure performance of all buyer's obligations under this contract.\n\n" +
            "(2) The buyer is responsible for any loss or damage to goods once they are in buyer's possession.\n\n" +
            "(3) Any claim of immediately apparent defect against delivered goods must be made upon receipt. In the case of hidden defects, buyer shall have no more than 3 days to present seller with a claim of defect.\n\n" +
            "(4) The goods sold in this invoice will only be used for resale.\n\n" +
            "(5) In any action which may be brought to enforce payment under this contract, seller shall be entitled to recover from buyer all the attorney fees seller incurs, in addition to seller's actual, incidental, and consequential damages.\n\n" +
            "(6) Buyer agrees to pay a fee of $30.00 for each check drawn on insufficient funds (NSF Check).\n\n" +
            "(7) Buyer agrees that jurisdiction and venue for any dispute under this contract are proper in San Diego, CA.",
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
                "SENT"    -> Color.parseColor("#2E7D32")
                "PENDING" -> Color.parseColor("#E65100")
                else      -> Color.parseColor("#B71C1C")
            }
            addLine(status, bold = true, sizeSp = 12f, color = statusColor)
        }
    }

    private fun unitLabel(unit: String?): String =
        if (unit.isNullOrBlank() || unit == "Lbs") "lb" else unit

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

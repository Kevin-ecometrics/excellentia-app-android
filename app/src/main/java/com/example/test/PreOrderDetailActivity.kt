package com.example.test

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.BatchItem
import com.example.test.data.ConvertPreOrderRequest
import com.example.test.data.DamageItem
import com.example.test.data.OrderDto
import com.example.test.data.PreOrderDto
import com.example.test.data.PreOrderItem
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.example.test.data.print.PrintService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PreOrderDetailActivity : AppCompatActivity() {

    private lateinit var tvDetailCustomer: TextView
    private lateinit var tvDetailStatus: TextView
    private lateinit var tvDetailDate: TextView
    private lateinit var tvDetailNotes: TextView
    private lateinit var layoutDetailItems: LinearLayout
    private lateinit var tvDetailTotal: TextView
    private lateinit var btnConvert: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnReuse: MaterialButton
    private lateinit var btnViewHistory: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutLoading: View
    private lateinit var tvLoadingTitle: TextView
    private lateinit var tvLoadingSubtitle: TextView
    private lateinit var securePrefs: SecurePreferences

    private var preOrderId = 0
    private var currentPreOrder: PreOrderDto? = null

    private var pendingSignature: String? = null
    private var pendingDamageItems: List<DamageItem> = emptyList()
    private var pendingPaymentMethod: String? = null

    private val signatureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingSignature = result.data?.getStringExtra("signature")
            askDamagedItems()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_pre_order_detail)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        preOrderId = intent.getIntExtra("pre_order_id", 0)

        tvDetailCustomer  = findViewById(R.id.tvDetailCustomer)
        tvDetailStatus    = findViewById(R.id.tvDetailStatus)
        tvDetailDate      = findViewById(R.id.tvDetailDate)
        tvDetailNotes     = findViewById(R.id.tvDetailNotes)
        layoutDetailItems = findViewById(R.id.layoutDetailItems)
        tvDetailTotal     = findViewById(R.id.tvDetailTotal)
        btnConvert        = findViewById(R.id.btnConvert)
        btnCancel         = findViewById(R.id.btnCancel)
        btnReuse          = findViewById(R.id.btnReuse)
        btnViewHistory    = findViewById(R.id.btnViewHistory)
        progressBar       = findViewById(R.id.progressBar)
        layoutLoading     = findViewById(R.id.layoutLoading)
        tvLoadingTitle    = findViewById(R.id.tvLoadingTitle)
        tvLoadingSubtitle = findViewById(R.id.tvLoadingSubtitle)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        btnConvert.setOnClickListener     { startConversionFlow() }
        btnCancel.setOnClickListener      { confirmCancel() }
        btnReuse.setOnClickListener       { reusePreOrder() }
        btnViewHistory.setOnClickListener { finish() }

        loadPreOrder()
    }

    private fun loadPreOrder() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getPreOrder(preOrderId)
                if (resp.isSuccessful) {
                    val po = resp.body()?.data
                    if (po != null) { currentPreOrder = po; renderPreOrder(po) }
                } else {
                    showError("Error ${resp.code()}")
                }
            } catch (e: Exception) {
                showError(e.localizedMessage ?: "Error de conexión")
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun renderPreOrder(po: PreOrderDto) {
        tvDetailCustomer.text = po.customerName

        val statusLabel = when (po.status) {
            "DRAFT"     -> "BORRADOR"
            "CONFIRMED" -> "CONFIRMADA"
            "CONVERTED" -> "CONVERTIDA"
            "CANCELLED" -> "CANCELADA"
            else        -> po.status
        }
        val statusColor = when (po.status) {
            "CONVERTED" -> R.color.success
            "CONFIRMED" -> R.color.primary
            "CANCELLED" -> R.color.red
            else        -> R.color.warning
        }
        tvDetailStatus.text = statusLabel
        tvDetailStatus.setTextColor(ContextCompat.getColor(this, statusColor))

        val dateStr = buildString {
            po.scheduledDate?.let { raw ->
                val formatted = formatDate(raw, "dd MMM yyyy")
                append("Entrega programada: $formatted")
            }
            po.createdAt?.let { raw ->
                val formatted = formatDate(raw, "dd MMM yyyy  HH:mm")
                if (isNotEmpty()) append("\n")
                append("Creada: $formatted")
            }
        }
        tvDetailDate.text = dateStr

        if (!po.notes.isNullOrBlank()) {
            tvDetailNotes.text = "Notas: ${po.notes}"
            tvDetailNotes.visibility = View.VISIBLE
        } else {
            tvDetailNotes.visibility = View.GONE
        }

        layoutDetailItems.removeAllViews()
        var runningTotal = 0.0
        for (item in po.items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dp }
                gravity = Gravity.CENTER_VERTICAL
            }
            val tvName = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "${item.productName}\n${String.format(Locale.US, "%.2f lb × $%.2f/lb", item.quantity, item.price)}"
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
            }
            val tvItemTotal = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                text = String.format(Locale.US, "$%.2f", item.total)
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
            }
            row.addView(tvName)
            row.addView(tvItemTotal)
            layoutDetailItems.addView(row)
            runningTotal += item.total
        }
        tvDetailTotal.text = String.format(Locale.US, "$%.2f", runningTotal)

        when (po.status) {
            "DRAFT", "CONFIRMED" -> {
                btnConvert.visibility     = View.VISIBLE
                btnCancel.visibility      = View.VISIBLE
                btnReuse.visibility       = View.GONE
                btnViewHistory.visibility = View.GONE
            }
            "CONVERTED" -> {
                btnConvert.visibility     = View.GONE
                btnCancel.visibility      = View.GONE
                btnReuse.visibility       = View.VISIBLE
                btnViewHistory.visibility = View.VISIBLE
            }
            else -> { // CANCELLED
                btnConvert.visibility     = View.GONE
                btnCancel.visibility      = View.GONE
                btnReuse.visibility       = View.GONE
                btnViewHistory.visibility = View.GONE
            }
        }
    }

    // ── Conversion flow (identical to CurrentOrderActivity) ──────────────────

    private fun startConversionFlow() {
        val po = currentPreOrder ?: return
        signatureLauncher.launch(Intent(this, SignatureActivity::class.java).apply {
            putExtra("customer_name", po.customerName)
        })
    }

    private fun askDamagedItems() {
        val po = currentPreOrder ?: return
        val items = po.items
        if (items.isEmpty()) { askPaymentMethod(); return }

        val density = resources.displayMetrics.density
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
        }
        scroll.addView(container)

        val inputs = mutableListOf<Pair<PreOrderItem, EditText>>()
        for (item in items) {
            val tvName = TextView(this).apply {
                text = item.productName
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (14 * density).toInt() }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * density).toInt() }
            }
            val tvDetail = TextView(this).apply {
                text = String.format(Locale.US, "%.2f lb · $%.2f/lb", item.quantity, item.price)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val etQty = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText("0")
                textSize = 15f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((64 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                selectAll()
            }
            row.addView(tvDetail)
            row.addView(etQty)
            container.addView(tvName)
            container.addView(row)
            inputs.add(Pair(item, etQty))
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("¿Artículos dañados o vencidos?")
            .setMessage("Indica las unidades dañadas por producto (0 = ninguna).")
            .setView(scroll)
            .setPositiveButton("Continuar") { _, _ ->
                pendingDamageItems = inputs.mapNotNull { (item, et) ->
                    val qty = et.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    if (qty > 0) DamageItem(barcode = item.barcode, productName = item.productName, qty = qty)
                    else null
                }
                askPaymentMethod()
            }
            .setNegativeButton("Ninguno") { _, _ ->
                pendingDamageItems = emptyList()
                askPaymentMethod()
            }
            .show()
    }

    private fun askPaymentMethod() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Método de pago")
            .setMessage("¿Cómo paga el cliente?")
            .setPositiveButton("Cash")   { _, _ -> pendingPaymentMethod = "Cash";  checkPrinterThenConvert() }
            .setNeutralButton("Check")   { _, _ -> pendingPaymentMethod = "Check"; checkPrinterThenConvert() }
            .setNegativeButton("Omitir") { _, _ -> pendingPaymentMethod = null;    checkPrinterThenConvert() }
            .show()
    }

    private fun checkPrinterThenConvert() {
        val printerAddress = securePrefs.getPrinterAddress()
        val printerName    = securePrefs.getPrinterName() ?: "Impresora"

        if (printerAddress.isNullOrBlank()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Sin impresora configurada")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage("No hay ninguna impresora asignada en Ajustes. No se generará ticket físico.\n\n¿Deseas continuar sin imprimir o ir a Ajustes para configurarla?")
                .setPositiveButton("Continuar sin imprimir") { _, _ -> doConvert(skipPrint = true) }
                .setNeutralButton("Ir a Ajustes") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Confirmar impresión")
                .setMessage("Se imprimirá el ticket en:\n\n$printerName\n\nAsegúrate de que la impresora esté encendida y cerca antes de continuar.")
                .setPositiveButton("Finalizar e imprimir")    { _, _ -> doConvert(skipPrint = false) }
                .setNeutralButton("Finalizar sin imprimir")   { _, _ -> doConvert(skipPrint = true) }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun doConvert(skipPrint: Boolean) {
        val po = currentPreOrder ?: return

        layoutLoading.visibility = View.VISIBLE
        tvLoadingTitle.text    = "Convirtiendo pre-orden..."
        tvLoadingSubtitle.text = "Cliente: ${po.customerName}"
        btnConvert.isEnabled   = false
        btnCancel.isEnabled    = false

        val sigForPrinting     = pendingSignature
        val damageForPrinting  = pendingDamageItems
        val paymentForPrinting = pendingPaymentMethod

        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().convertPreOrder(
                    id = preOrderId,
                    request = ConvertPreOrderRequest(
                        signature     = sigForPrinting,
                        paymentMethod = paymentForPrinting,
                        damageItems   = damageForPrinting.takeIf { it.isNotEmpty() }
                    )
                )

                if (resp.isSuccessful) {
                    val body = resp.body()!!

                    tvLoadingTitle.text    = "Generando factura..."
                    tvLoadingSubtitle.text = "Factura QB: ${body.invoiceId ?: "—"}"

                    pendingSignature     = null
                    pendingDamageItems   = emptyList()
                    pendingPaymentMethod = null

                    val batchItems = po.items.map { item ->
                        BatchItem(
                            barcode     = item.barcode,
                            productName = item.productName,
                            price       = item.price,
                            quantity    = item.quantity,
                            total       = item.total
                        )
                    }

                    val printerAddress = securePrefs.getPrinterAddress()
                    if (!skipPrint && !printerAddress.isNullOrBlank()) {
                        tvLoadingTitle.text    = "Imprimiendo ticket..."
                        tvLoadingSubtitle.text = "Conectando con impresora"
                        val printResult = PrintService.printTicket(
                            context         = this@PreOrderDetailActivity,
                            deviceAddress   = printerAddress,
                            items           = batchItems,
                            customerName    = po.customerName,
                            batchId         = body.batchId,
                            invoiceId       = body.invoiceId,
                            customerAddress = null,
                            damageItems     = damageForPrinting,
                            paymentMethod   = paymentForPrinting,
                            signature       = sigForPrinting
                        )
                        printResult.onFailure { e ->
                            Snackbar.make(
                                findViewById(android.R.id.content),
                                "Pedido enviado · Error al imprimir: ${e.localizedMessage ?: "sin conexión"}",
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }

                    layoutLoading.visibility = View.GONE
                    val grandTotal = po.items.sumOf { it.total }
                    startActivity(
                        Intent(this@PreOrderDetailActivity, OrderSuccessActivity::class.java).apply {
                            putExtra("batch_id",          body.batchId)
                            putExtra("invoice_id",        body.invoiceId ?: "")
                            putExtra("customer_name",     po.customerName)
                            putExtra("customer_address",  "")
                            putExtra("signature",         sigForPrinting)
                            putExtra("damage_items_json", Gson().toJson(damageForPrinting))
                            putExtra("total",             grandTotal)
                            putExtra("item_count",        po.items.size)
                            putExtra("orders_json",       Gson().toJson(
                                batchItems.map { bi ->
                                    OrderDto(
                                        id           = 0,
                                        barcode      = bi.barcode,
                                        productName  = bi.productName,
                                        price        = bi.price,
                                        quantity     = bi.quantity,
                                        total        = bi.total,
                                        status       = "SENT",
                                        customerId   = po.customerId,
                                        customerName = po.customerName,
                                        signature    = sigForPrinting
                                    )
                                }
                            ))
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    )
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    layoutLoading.visibility = View.GONE
                    btnConvert.isEnabled = true
                    btnCancel.isEnabled  = true
                    showError("Error al convertir: ${resp.code()}")
                }
            } catch (e: Exception) {
                layoutLoading.visibility = View.GONE
                btnConvert.isEnabled = true
                btnCancel.isEnabled  = true
                showError(e.localizedMessage ?: "Error de conexión")
            }
        }
    }

    private fun confirmCancel() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Cancelar pre-orden")
            .setMessage("¿Deseas cancelar esta pre-orden? Esta acción no se puede deshacer.")
            .setPositiveButton("Sí, cancelar") { _, _ -> cancelPreOrder() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun cancelPreOrder() {
        lifecycleScope.launch {
            try {
                RetrofitClient.getApi().deletePreOrder(preOrderId)
                setResult(Activity.RESULT_OK)
                finish()
            } catch (e: Exception) {
                showError(e.localizedMessage ?: "Error al cancelar")
            }
        }
    }

    private fun reusePreOrder() {
        val po = currentPreOrder ?: return
        if (po.items.isEmpty()) {
            showError("Esta pre-orden no tiene ítems para reusar")
            return
        }
        btnReuse.isEnabled = false
        btnReuse.text = "Creando..."

        lifecycleScope.launch {
            try {
                val request = com.example.test.data.PreOrderRequest(
                    customerId    = po.customerId,
                    customerName  = po.customerName,
                    scheduledDate = null,
                    notes         = po.notes,
                    items         = po.items
                )
                val resp = RetrofitClient.getApi().createPreOrder(request)
                if (resp.isSuccessful) {
                    val newId = resp.body()?.id ?: 0
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Nueva pre-orden creada",
                        Snackbar.LENGTH_SHORT
                    ).show()
                    // Abrir el detalle de la nueva pre-orden
                    startActivity(Intent(this@PreOrderDetailActivity, PreOrderDetailActivity::class.java).apply {
                        putExtra("pre_order_id", newId)
                    })
                    setResult(Activity.RESULT_OK)
                } else {
                    showError("Error al crear: ${resp.code()}")
                }
            } catch (e: Exception) {
                showError(e.localizedMessage ?: "Error de conexión")
            } finally {
                btnReuse.isEnabled = true
                btnReuse.text = "Reusar pre-orden"
            }
        }
    }

    private fun showError(msg: String) {
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show()
    }

    private fun formatDate(raw: String, pattern: String): String {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd"
        )
        val display = SimpleDateFormat(pattern, Locale.US)
        for (fmt in formats) {
            try {
                val parser = SimpleDateFormat(fmt, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                return display.format(parser.parse(raw)!!)
            } catch (_: Exception) {}
        }
        return raw
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

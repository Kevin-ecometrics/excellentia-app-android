package com.example.test

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.BatchItem
import com.example.test.data.OrderDto
import com.example.test.data.print.PrintService
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.repository.OrderRepository
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.util.Locale

class CurrentOrderActivity : AppCompatActivity() {

    private lateinit var layoutOrderItems: LinearLayout
    private lateinit var layoutEmpty: View
    private lateinit var layoutLoading: View
    private lateinit var tvLoadingTitle: TextView
    private lateinit var tvLoadingSubtitle: TextView
    private lateinit var tvStep1Icon: TextView
    private lateinit var tvStep2Icon: TextView
    private lateinit var tvStep2Label: TextView
    private lateinit var tvStep3Icon: TextView
    private lateinit var tvStep3Label: TextView
    private lateinit var tvGrandTotal: TextView
    private lateinit var tvTotalQty: TextView
    private lateinit var tvTotalItems: TextView
    private lateinit var tvOrderCount: TextView
    private lateinit var btnViewTicket: MaterialButton
    private lateinit var btnFinalize: MaterialButton
    private lateinit var orderRepository: OrderRepository

    private val customerPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val customerId = result.data?.getStringExtra("customer_id")
            val customerName = result.data?.getStringExtra("customer_name")
            finalizeOrder(customerId, customerName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_current_order)
        val pad = resources.getDimensionPixelSize(R.dimen.padding_screen)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad)
            insets
        }

        val db = AppDatabase.getInstance(this)
        val securePrefs = SecurePreferences(this)
        orderRepository = OrderRepository(db, securePrefs)

        layoutOrderItems = findViewById(R.id.layoutOrderItems)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        layoutLoading = findViewById(R.id.layoutLoading)
        tvLoadingTitle = findViewById(R.id.tvLoadingTitle)
        tvLoadingSubtitle = findViewById(R.id.tvLoadingSubtitle)
        tvStep1Icon = findViewById(R.id.tvStep1Icon)
        tvStep2Icon = findViewById(R.id.tvStep2Icon)
        tvStep2Label = findViewById(R.id.tvStep2Label)
        tvStep3Icon = findViewById(R.id.tvStep3Icon)
        tvStep3Label = findViewById(R.id.tvStep3Label)
        tvGrandTotal = findViewById(R.id.tvGrandTotal)
        tvTotalQty = findViewById(R.id.tvTotalQty)
        tvTotalItems = findViewById(R.id.tvTotalItems)
        tvOrderCount = findViewById(R.id.tvOrderCount)
        btnViewTicket = findViewById(R.id.btnViewTicket)
        btnFinalize = findViewById(R.id.btnFinalize)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        btnViewTicket.setOnClickListener { openTicket() }
        btnFinalize.setOnClickListener {
            customerPickerLauncher.launch(Intent(this, CustomerPickerActivity::class.java))
        }

        loadOrder()
    }

    override fun onResume() {
        super.onResume()
        loadOrder()
    }

    private fun loadOrder() {
        lifecycleScope.launch {
            val pending = orderRepository.getPendingOrders()

            layoutOrderItems.removeAllViews()

            if (pending.isEmpty()) {
                layoutEmpty.visibility = View.VISIBLE
                tvGrandTotal.text = "$0.00"
                tvTotalQty.text = "0.00 lb"
                tvTotalItems.text = "0 productos"
                tvOrderCount.text = "0"
                btnFinalize.isEnabled = false
                btnViewTicket.isEnabled = false
                return@launch
            }

            layoutEmpty.visibility = View.GONE
            btnFinalize.isEnabled = true
            btnViewTicket.isEnabled = true

            val inflater = LayoutInflater.from(this@CurrentOrderActivity)
            for (order in pending) {
                val row = inflater.inflate(R.layout.item_pending_order, layoutOrderItems, false)
                row.findViewById<TextView>(R.id.tvPendingName).text = order.productName
                row.findViewById<TextView>(R.id.tvPendingMeta).text =
                    "${order.barcode}  ·  ${String.format(Locale.US, "$%.2f/lb", order.price)}"
                row.findViewById<TextView>(R.id.tvPendingQtyTotal).text =
                    String.format(Locale.US, "%.2f lb  =  $%.2f", order.quantity, order.price * order.quantity)
                row.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditItem)
                    .setOnClickListener { showEditDialog(order) }
                row.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeleteItem)
                    .setOnClickListener { confirmDelete(order.id, order.productName) }
                layoutOrderItems.addView(row)
            }

            val grandTotal = pending.sumOf { it.price * it.quantity }
            val totalQty = pending.sumOf { it.quantity }
            tvGrandTotal.text = String.format(Locale.US, "$%.2f", grandTotal)
            tvTotalQty.text = String.format(Locale.US, "%.2f lb", totalQty)
            tvTotalItems.text = "${pending.size} producto(s)"
            tvOrderCount.text = pending.size.toString()
        }
    }

    private fun showEditDialog(order: com.example.test.data.local.entities.PendingOrderEntity) {
        val margin = resources.getDimensionPixelSize(R.dimen.padding_screen)

        val etQty = android.widget.EditText(this).apply {
            setBackgroundResource(R.drawable.bg_edittext)
            hint = "Cantidad total (lb)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.2f", order.quantity))
            selectAll()
        }
        val etPrice = android.widget.EditText(this).apply {
            setBackgroundResource(R.drawable.bg_edittext)
            hint = "Precio ($/lb)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.2f", order.price))
        }

        // Resumen dinámico
        val tvTotal = android.widget.TextView(this).apply {
            textSize = 13f
            setTextColor(resources.getColor(R.color.primary, theme))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(0, 14, 0, 0)
        }
        fun refresh() {
            val q = etQty.text.toString().toDoubleOrNull() ?: 0.0
            val p = etPrice.text.toString().toDoubleOrNull() ?: 0.0
            tvTotal.text = String.format(Locale.US, "%.2f lb  =  \$%.2f", q, q * p)
        }
        val w = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { refresh() }
        }
        etQty.addTextChangedListener(w)
        etPrice.addTextChangedListener(w)
        refresh()

        fun label(txt: String) = android.widget.TextView(this).apply {
            text = txt; textSize = 11f
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 14 }
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(margin, margin / 2, margin, margin / 2)
            addView(label("CANTIDAD TOTAL (lb)"))
            addView(etQty)
            addView(label("PRECIO ($/lb)"))
            addView(etPrice)
            addView(tvTotal)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(order.productName)
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                val qty   = etQty.text.toString().toDoubleOrNull()
                val price = etPrice.text.toString().toDoubleOrNull()
                if (qty != null && qty > 0 && price != null && price > 0) {
                    orderRepository.updatePendingOrder(order.id, price, qty)
                    loadOrder()
                } else {
                    android.widget.Toast.makeText(this, "Valores inválidos", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDelete(id: Int, name: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Eliminar producto")
            .setMessage("¿Eliminar \"$name\" del pedido?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch {
                    orderRepository.deletePendingOrder(id)
                    loadOrder()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openTicket() {
        lifecycleScope.launch {
            val pending = orderRepository.getPendingOrders()
            if (pending.isEmpty()) return@launch
            val orders = pending.map { order ->
                OrderDto(
                    id = order.id,
                    barcode = order.barcode,
                    productName = order.productName,
                    price = order.price,
                    quantity = order.quantity,
                    total = order.price * order.quantity,
                    status = "PENDING"
                )
            }
            startActivity(Intent(this@CurrentOrderActivity, TicketDetailActivity::class.java).apply {
                putExtra("batch_id", "")
                putExtra("invoice_id", "")
                putExtra("orders_json", Gson().toJson(orders))
            })
        }
    }

    private fun setStep(step: Int) {
        val successColor = getColor(R.color.success)
        val primaryColor = getColor(R.color.primary)
        val dimColor = getColor(R.color.divider)
        val textPrimary = getColor(R.color.text_primary)
        val textSecondary = getColor(R.color.text_secondary)
        when (step) {
            1 -> {
                tvStep1Icon.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
                tvStep2Icon.backgroundTintList = android.content.res.ColorStateList.valueOf(dimColor)
                tvStep2Label.setTextColor(textSecondary)
                tvStep3Icon.backgroundTintList = android.content.res.ColorStateList.valueOf(dimColor)
                tvStep3Label.setTextColor(textSecondary)
            }
            2 -> {
                tvStep1Icon.backgroundTintList = android.content.res.ColorStateList.valueOf(successColor)
                tvStep1Icon.text = "✓"
                tvStep2Icon.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
                tvStep2Label.setTextColor(textPrimary)
                tvStep3Icon.backgroundTintList = android.content.res.ColorStateList.valueOf(dimColor)
                tvStep3Label.setTextColor(textSecondary)
            }
            3 -> {
                tvStep1Icon.backgroundTintList = android.content.res.ColorStateList.valueOf(successColor)
                tvStep1Icon.text = "✓"
                tvStep2Icon.backgroundTintList = android.content.res.ColorStateList.valueOf(successColor)
                tvStep2Icon.text = "✓"
                tvStep3Icon.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
                tvStep3Label.setTextColor(textPrimary)
            }
        }
    }

    private fun finalizeOrder(customerId: String?, customerName: String?) {
        lifecycleScope.launch {
            // Mostrar loading
            layoutLoading.visibility = View.VISIBLE
            setStep(1)
            tvLoadingTitle.text = "Enviando pedido..."
            tvLoadingSubtitle.text = if (!customerName.isNullOrBlank())
                "Cliente: $customerName" else "Conectando con QuickBooks"
            btnFinalize.isEnabled = false
            btnViewTicket.isEnabled = false

            val pending = orderRepository.getPendingOrders()
            if (pending.isEmpty()) {
                layoutLoading.visibility = View.GONE
                finish()
                return@launch
            }

            val items = pending.map { order ->
                BatchItem(
                    barcode = order.barcode,
                    productName = order.productName,
                    price = order.price,
                    quantity = order.quantity,
                    total = order.price * order.quantity
                )
            }

            val result = orderRepository.sendBatch(items, customerId, customerName)
            result.onSuccess { response ->
                val sentCount = response.orders.count { it.status == "SENT" }
                setStep(2)
                tvLoadingTitle.text = "Generando factura..."
                tvLoadingSubtitle.text = "Factura QB: ${response.invoiceId ?: "—"}"
                orderRepository.clearPending()

                // Print ticket via Bluetooth if printer is configured
                val printerAddress = SecurePreferences(this@CurrentOrderActivity).getPrinterAddress()
                if (!printerAddress.isNullOrBlank()) {
                    setStep(3)
                    tvLoadingTitle.text = "Imprimiendo ticket..."
                    tvLoadingSubtitle.text = "Conectando con impresora"
                    val printResult = PrintService.printTicket(
                        context = this@CurrentOrderActivity,
                        deviceAddress = printerAddress,
                        items = items,
                        customerName = customerName,
                        batchId = response.batchId,
                        invoiceId = response.invoiceId
                    )
                    printResult.onFailure { e ->
                        Toast.makeText(
                            this@CurrentOrderActivity,
                            "Pedido enviado · Error al imprimir: ${e.localizedMessage ?: "sin conexión a impresora"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                layoutLoading.visibility = View.GONE
                val grandTotal = items.sumOf { it.total }
                startActivity(
                    Intent(this@CurrentOrderActivity, OrderSuccessActivity::class.java).apply {
                        putExtra("batch_id", response.batchId ?: "")
                        putExtra("invoice_id", response.invoiceId ?: "")
                        putExtra("customer_name", customerName)
                        putExtra("total", grandTotal)
                        putExtra("item_count", items.size)
                        putExtra("orders_json", Gson().toJson(
                            items.map { bi ->
                                OrderDto(
                                    id = 0,
                                    barcode = bi.barcode,
                                    productName = bi.productName,
                                    price = bi.price,
                                    quantity = bi.quantity,
                                    total = bi.total,
                                    status = "SENT"
                                )
                            }
                        ))
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
                finish()
            }.onFailure { e ->
                layoutLoading.visibility = View.GONE
                btnFinalize.isEnabled = true
                btnViewTicket.isEnabled = true
                Toast.makeText(
                    this@CurrentOrderActivity,
                    "Error al enviar: ${e.localizedMessage ?: "desconocido"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

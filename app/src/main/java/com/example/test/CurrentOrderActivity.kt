package com.example.test

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.appbar.MaterialToolbar
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
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
import com.example.test.data.network.RetrofitClient
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
    private lateinit var tvCustomerLabel: TextView
    private lateinit var btnViewTicket: MaterialButton
    private lateinit var btnFinalize: MaterialButton
    private lateinit var orderRepository: OrderRepository
    private lateinit var securePrefs: SecurePreferences

    private var customerId: String? = null
    private var customerName: String? = null

    private val customerPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getStringExtra("customer_id") ?: return@registerForActivityResult
            val name = result.data?.getStringExtra("customer_name") ?: return@registerForActivityResult
            securePrefs.setActiveCustomer(id, name)
            customerId = id
            customerName = name
            updateCustomerLabel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_current_order)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        val db = AppDatabase.getInstance(this)
        securePrefs = SecurePreferences(this)
        orderRepository = OrderRepository(db, securePrefs)

        customerId = securePrefs.getActiveCustomerId()
        customerName = securePrefs.getActiveCustomerName()

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
        tvCustomerLabel = findViewById(R.id.tvCustomerLabel)
        btnViewTicket = findViewById(R.id.btnViewTicket)
        btnFinalize = findViewById(R.id.btnFinalize)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        btnViewTicket.setOnClickListener { openTicket() }
        btnFinalize.setOnClickListener {
            if (customerId != null && customerName != null) {
                finalizeOrder(customerId, customerName)
            } else {
                customerPickerLauncher.launch(Intent(this, CustomerPickerActivity::class.java))
            }
        }

        tvCustomerLabel.setOnClickListener {
            customerPickerLauncher.launch(Intent(this, CustomerPickerActivity::class.java))
        }

        updateCustomerLabel()
        loadOrder()
    }

    override fun onResume() {
        super.onResume()
        customerId = securePrefs.getActiveCustomerId()
        customerName = securePrefs.getActiveCustomerName()
        updateCustomerLabel()
        loadOrder()
    }

    private fun updateCustomerLabel() {
        if (!customerName.isNullOrBlank()) {
            tvCustomerLabel.text = "Cliente: $customerName  ·  Toca para cambiar"
            tvCustomerLabel.visibility = View.VISIBLE
        } else {
            tvCustomerLabel.visibility = View.GONE
        }
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
                    "${order.barcode}  ·  ${String.format(Locale.US, "$%.2f total", order.price * order.quantity)}"
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
        lifecycleScope.launch {
            var minPrice: Double? = null
            try {
                val res = RetrofitClient.getApi().getProductByBarcode(order.barcode)
                if (res.isSuccessful) {
                    val dto = res.body()?.data
                    if (dto?.minPrice != null && dto.minPrice > 0) {
                        minPrice = dto.minPrice
                    }
                }
            } catch (_: Exception) {}

            val ctx = this@CurrentOrderActivity
            val dialogView = layoutInflater.inflate(R.layout.dialog_edit_order, null)
            val etQty        = dialogView.findViewById<android.widget.EditText>(R.id.etQty)
            val etPrice      = dialogView.findViewById<android.widget.EditText>(R.id.etPrice)
            val tvTotal      = dialogView.findViewById<android.widget.TextView>(R.id.tvTotal)
            val tvRate       = dialogView.findViewById<android.widget.TextView>(R.id.tvRate)
            val tvMinWarning = dialogView.findViewById<android.widget.TextView>(R.id.tvMinWarning)

            etQty.setText(String.format(Locale.US, "%.2f", order.quantity))
            etQty.selectAll()
            etPrice.setText(String.format(Locale.US, "%.2f", order.price * order.quantity))

            val baseRate = order.price
            var isUpdating = false

            fun refresh() {
                val q = etQty.text.toString().toDoubleOrNull() ?: 0.0
                val p = etPrice.text.toString().toDoubleOrNull() ?: 0.0
                tvTotal.text = String.format(Locale.US, "%.2f lb  =  \$%.2f", q, p)
                tvRate.text = if (q > 0 && p > 0)
                    "($${String.format(Locale.US, "%.2f", p / q)} / lb)" else ""
                if (minPrice != null && p > 0) {
                    if (Math.round(p * 100) < Math.round(minPrice * 100)) {
                        tvMinWarning.text = "Mínimo: $${String.format(Locale.US, "%.2f", minPrice)}"
                        tvMinWarning.visibility = android.view.View.VISIBLE
                    } else {
                        tvMinWarning.visibility = android.view.View.GONE
                    }
                }
            }

            val qtyWatcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (isUpdating) return
                    isUpdating = true
                    val q = s.toString().toDoubleOrNull()
                    if (q != null && q > 0) {
                        etPrice.setText(String.format(Locale.US, "%.2f", q * baseRate))
                    }
                    isUpdating = false
                    refresh()
                }
            }

            val priceWatcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (isUpdating) return
                    isUpdating = true
                    val p = s.toString().toDoubleOrNull()
                    if (p != null && p > 0) {
                        etQty.setText(String.format(Locale.US, "%.2f", p / baseRate))
                    }
                    isUpdating = false
                    refresh()
                }
            }

            etQty.addTextChangedListener(qtyWatcher)
            etPrice.addTextChangedListener(priceWatcher)
            refresh()

            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(order.productName)
                .setView(dialogView)
                .setPositiveButton("Guardar") { _, _ ->
                    val qty   = etQty.text.toString().toDoubleOrNull()
                    val total = etPrice.text.toString().toDoubleOrNull()
                    if (qty != null && qty > 0 && total != null && total > 0) {
                        if (minPrice != null && Math.round(total * 100) < Math.round(minPrice * 100)) {
                            Snackbar.make(
                                ctx.findViewById(android.R.id.content),
                                "Mínimo: $${String.format(Locale.US, "%.2f", minPrice)}",
                                Snackbar.LENGTH_LONG
                            ).show()
                            return@setPositiveButton
                        }
                        val perLb = total / qty
                        orderRepository.updatePendingOrder(order.id, perLb, qty)
                        loadOrder()
                    } else {
                        Snackbar.make(ctx.findViewById(android.R.id.content), "Valores inválidos", Snackbar.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun confirmDelete(id: Int, name: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
                    status = "PENDING",
                    customerId = customerId,
                    customerName = customerName
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
                setStep(2)
                tvLoadingTitle.text = "Generando factura..."
                tvLoadingSubtitle.text = "Factura QB: ${response.invoiceId ?: "—"}"
                orderRepository.clearPending()

                securePrefs.clearActiveCustomer()

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
                        Snackbar.make(
                            findViewById(android.R.id.content),
                            "Pedido enviado · Error al imprimir: ${e.localizedMessage ?: "sin conexión"}",
                            Snackbar.LENGTH_LONG
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
                                    status = "SENT",
                                    customerId = customerId,
                                    customerName = customerName
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
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "Error al enviar: ${e.localizedMessage ?: "desconocido"}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }
}
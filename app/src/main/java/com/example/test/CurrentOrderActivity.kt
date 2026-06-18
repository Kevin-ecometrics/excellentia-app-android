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

class CurrentOrderActivity : BaseActivity() {

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
    private lateinit var tvCustomerAddress: TextView
    private lateinit var btnViewTicket: MaterialButton
    private lateinit var btnFinalize: MaterialButton
    private lateinit var orderRepository: OrderRepository
    private lateinit var securePrefs: SecurePreferences

    private var customerId: String? = null
    private var customerName: String? = null
    private var customerAddress: String? = null
    private var pendingSignature: String? = null
    private var pendingDamageItems: List<com.example.test.data.DamageItem> = emptyList()
    private var pendingPaymentMethod: String? = null
    private var launchSignatureAfterCustomer = false

    private val customerPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getStringExtra("customer_id") ?: return@registerForActivityResult
            val name = result.data?.getStringExtra("customer_name") ?: return@registerForActivityResult
            val address = result.data?.getStringExtra("customer_address")
            securePrefs.setActiveCustomer(id, name, address)
            customerId = id
            customerName = name
            customerAddress = address
            updateCustomerLabel()
            if (launchSignatureAfterCustomer) {
                launchSignatureAfterCustomer = false
                askDamagedItems()
            }
        }
    }

    private val signatureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingSignature = result.data?.getStringExtra("signature")
            checkPrinterThenFinalize()
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
        customerAddress = securePrefs.getActiveCustomerAddress()

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
        tvCustomerAddress = findViewById(R.id.tvCustomerAddress)
        btnViewTicket = findViewById(R.id.btnViewTicket)
        btnFinalize = findViewById(R.id.btnFinalize)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        btnViewTicket.setOnClickListener { openTicket() }
        btnFinalize.setOnClickListener {
            if (customerId != null && customerName != null) {
                askDamagedItems()
            } else {
                launchSignatureAfterCustomer = true
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
        customerAddress = securePrefs.getActiveCustomerAddress()
        updateCustomerLabel()
        loadOrder()
    }

    private fun updateCustomerLabel() {
        if (!customerName.isNullOrBlank()) {
            tvCustomerLabel.text = getString(R.string.label_customer_tap_change, customerName)
            tvCustomerLabel.visibility = View.VISIBLE
            if (!customerAddress.isNullOrBlank()) {
                tvCustomerAddress.text = customerAddress
                tvCustomerAddress.visibility = View.VISIBLE
            } else {
                tvCustomerAddress.visibility = View.GONE
            }
        } else {
            tvCustomerLabel.visibility = View.GONE
            tvCustomerAddress.visibility = View.GONE
        }
    }

    private fun loadOrder() {
        lifecycleScope.launch {
            val pending = orderRepository.getPendingOrders()

            layoutOrderItems.removeAllViews()

            if (pending.isEmpty()) {
                layoutEmpty.visibility = View.VISIBLE
                tvGrandTotal.text = getString(R.string.default_total_zero)
                tvTotalQty.text = getString(R.string.default_qty_zero)
                tvTotalItems.text = getString(R.string.label_products_count, 0)
                tvOrderCount.text = getString(R.string.default_count_zero)
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
            tvTotalItems.text = getString(R.string.label_products_count, pending.size)
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
            val etPricePerLb = dialogView.findViewById<android.widget.EditText>(R.id.etPricePerLb)
            val tvTotal      = dialogView.findViewById<android.widget.TextView>(R.id.tvTotal)
            val tvMinWarning = dialogView.findViewById<android.widget.TextView>(R.id.tvMinWarning)

            etQty.setText(String.format(Locale.US, "%.2f", order.quantity))
            etQty.selectAll()
            etPricePerLb.setText(String.format(Locale.US, "%.2f", order.price))

            fun refresh() {
                val q = etQty.text.toString().toDoubleOrNull() ?: 0.0
                val r = etPricePerLb.text.toString().toDoubleOrNull() ?: 0.0
                val total = q * r
                tvTotal.text = String.format(Locale.US, "%.2f lb  =  \$%.2f", q, total)
                if (minPrice != null && total > 0) {
                    if (Math.round(total * 100) < Math.round(minPrice * 100)) {
                        tvMinWarning.text = getString(R.string.error_min_price, String.format(Locale.US, "%.2f", minPrice))
                        tvMinWarning.visibility = android.view.View.VISIBLE
                    } else {
                        tvMinWarning.visibility = android.view.View.GONE
                    }
                }
            }

            val watcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { refresh() }
            }

            etQty.addTextChangedListener(watcher)
            etPricePerLb.addTextChangedListener(watcher)
            refresh()

            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(order.productName)
                .setView(dialogView)
                .setPositiveButton(getString(R.string.btn_save_dialog)) { _, _ ->
                    val qty  = etQty.text.toString().toDoubleOrNull()
                    val rate = etPricePerLb.text.toString().toDoubleOrNull()
                    if (qty != null && qty > 0 && rate != null && rate > 0) {
                        val total = qty * rate
                        if (minPrice != null && Math.round(total * 100) < Math.round(minPrice * 100)) {
                            Snackbar.make(
                                ctx.findViewById(android.R.id.content),
                                getString(R.string.error_min_price, String.format(Locale.US, "%.2f", minPrice)),
                                Snackbar.LENGTH_LONG
                            ).show()
                            return@setPositiveButton
                        }
                        orderRepository.updatePendingOrder(order.id, rate, qty)
                        loadOrder()
                    } else {
                        Snackbar.make(ctx.findViewById(android.R.id.content), getString(R.string.error_invalid_values), Snackbar.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    private fun confirmDelete(id: Int, name: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_delete_product))
            .setMessage(getString(R.string.msg_delete_product, name))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                lifecycleScope.launch {
                    orderRepository.deletePendingOrder(id)
                    loadOrder()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
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
                putExtra("customer_address", customerAddress)
                putExtra("damage_items_json", Gson().toJson(pendingDamageItems))
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
                tvStep1Icon.text = getString(R.string.checkmark_icon)
                tvStep2Icon.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
                tvStep2Label.setTextColor(textPrimary)
                tvStep3Icon.backgroundTintList = android.content.res.ColorStateList.valueOf(dimColor)
                tvStep3Label.setTextColor(textSecondary)
            }
            3 -> {
                tvStep1Icon.backgroundTintList = android.content.res.ColorStateList.valueOf(successColor)
                tvStep1Icon.text = getString(R.string.checkmark_icon)
                tvStep2Icon.backgroundTintList = android.content.res.ColorStateList.valueOf(successColor)
                tvStep2Icon.text = getString(R.string.checkmark_icon)
                tvStep3Icon.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
                tvStep3Label.setTextColor(textPrimary)
            }
        }
    }

    private fun launchSignature() {
        signatureLauncher.launch(
            Intent(this, SignatureActivity::class.java).apply {
                putExtra("customer_name", customerName)
            }
        )
    }

    private fun askDamagedItems() {
        lifecycleScope.launch {
            val pending = orderRepository.getPendingOrders()
            if (pending.isEmpty()) { launchSignature(); return@launch }

            val ctx = this@CurrentOrderActivity
            val density = resources.displayMetrics.density

            // ScrollView con una fila por producto
            val scroll = android.widget.ScrollView(ctx)
            val container = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(
                    (20 * density).toInt(), (4 * density).toInt(),
                    (20 * density).toInt(), (8 * density).toInt()
                )
            }

            // Mensaje dentro del scroll para no ocupar espacio del diálogo
            container.addView(android.widget.TextView(ctx).apply {
                text = getString(R.string.msg_damaged_items_hint)
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * density).toInt() }
            })

            scroll.addView(container)

            // Lista de pares (orden, editText) para leer al confirmar
            val inputs = mutableListOf<Pair<com.example.test.data.local.entities.PendingOrderEntity, android.widget.EditText>>()

            for (order in pending) {
                // Nombre del producto
                val tvName = android.widget.TextView(ctx).apply {
                    text = order.productName
                    textSize = 14f
                    setTextColor(getColor(R.color.text_primary))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (14 * density).toInt() }
                }

                // Fila: detalle (izq) + input (der)
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (4 * density).toInt() }
                }

                val tvDetail = android.widget.TextView(ctx).apply {
                    text = String.format(Locale.US, "%.2f lb · $%.2f/lb", order.quantity, order.price)
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val etQty = android.widget.EditText(ctx).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setText(getString(R.string.default_count_zero))
                    textSize = 15f
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (64 * density).toInt(),
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    selectAll()
                }

                row.addView(tvDetail)
                row.addView(etQty)
                container.addView(tvName)
                container.addView(row)
                inputs.add(Pair(order, etQty))
            }

            // Wrapper de altura fija — el diálogo respeta el tamaño del wrapper
            val maxH = (resources.displayMetrics.heightPixels * 0.38).toInt()
            val wrapper = android.widget.FrameLayout(ctx)
            wrapper.addView(scroll, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, maxH
            ))

            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(getString(R.string.title_damaged_items))
                .setView(wrapper)
                .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                    pendingDamageItems = inputs.mapNotNull { (order, et) ->
                        val qty = et.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                        if (qty > 0) com.example.test.data.DamageItem(
                            barcode     = order.barcode,
                            productName = order.productName,
                            qty         = qty
                        ) else null
                    }
                    launchSignature()
                }
                .setNegativeButton(getString(R.string.btn_none)) { _, _ ->
                    pendingDamageItems = emptyList()
                    launchSignature()
                }
                .show()
        }
    }

    private fun askPaymentMethod() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_payment_method))
            .setMessage(getString(R.string.msg_payment_method))
            .setPositiveButton(getString(R.string.btn_cash)) { _, _ ->
                pendingPaymentMethod = getString(R.string.btn_cash)
                launchSignature()
            }
            .setNeutralButton(getString(R.string.btn_check)) { _, _ ->
                pendingPaymentMethod = getString(R.string.btn_check)
                launchSignature()
            }
            .setNegativeButton(getString(R.string.btn_skip)) { _, _ ->
                pendingPaymentMethod = null
                launchSignature()
            }
            .show()
    }

    private fun checkPrinterThenFinalize() {
        val printerAddress = securePrefs.getPrinterAddress()
        val printerName    = securePrefs.getPrinterName() ?: getString(R.string.label_no_printer_selected)

        if (printerAddress.isNullOrBlank()) {
            // No printer configured
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.title_no_printer))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(getString(R.string.msg_no_printer))
                .setPositiveButton(getString(R.string.btn_continue_no_print)) { _, _ ->
                    finalizeOrder(customerId, customerName, skipPrint = true)
                }
                .setNeutralButton(getString(R.string.btn_go_to_settings)) { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        } else {
            // Printer configured — confirm it's on
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.title_confirm_print))
                .setMessage(getString(R.string.msg_confirm_print, printerName))
                .setPositiveButton(getString(R.string.btn_finalize_and_print)) { _, _ ->
                    finalizeOrder(customerId, customerName, skipPrint = false)
                }
                .setNeutralButton(getString(R.string.btn_finalize_no_print)) { _, _ ->
                    finalizeOrder(customerId, customerName, skipPrint = true)
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    private fun finalizeOrder(customerId: String?, customerName: String?, skipPrint: Boolean = false) {
        lifecycleScope.launch {
            layoutLoading.visibility = View.VISIBLE
            setStep(1)
            tvLoadingTitle.text = getString(R.string.loading_sending_order)
            tvLoadingSubtitle.text = if (!customerName.isNullOrBlank())
                getString(R.string.loading_client, customerName) else getString(R.string.loading_connecting_qb)
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

            val sigForPrinting     = pendingSignature
            val damageForPrinting  = pendingDamageItems
            val paymentForPrinting = pendingPaymentMethod
            android.util.Log.d("DamageDebug", "sendBatch damageItems=${pendingDamageItems.size}: ${pendingDamageItems.map { "${it.productName}:${it.qty}" }}")
            val result = orderRepository.sendBatch(items, customerId, customerName, pendingSignature, pendingDamageItems, pendingPaymentMethod)
            pendingSignature    = null
            pendingDamageItems  = emptyList()
            pendingPaymentMethod = null
            result.onSuccess { response ->
                val isOfflinePending = response.batchId == "OFFLINE_PENDING"

                if (isOfflinePending) {
                    // Flujo offline: no hay factura, solo imprimir y navegar
                    tvLoadingTitle.text = getString(R.string.loading_saving_offline)
                    tvLoadingSubtitle.text = ""
                    orderRepository.clearPending()
                    securePrefs.clearActiveCustomer()

                    val printerAddress = securePrefs.getPrinterAddress()
                    if (!skipPrint && !printerAddress.isNullOrBlank()) {
                        setStep(3)
                        tvLoadingTitle.text = getString(R.string.loading_printing_ticket)
                        tvLoadingSubtitle.text = getString(R.string.loading_connecting_printer)
                        PrintService.printTicket(
                            context       = this@CurrentOrderActivity,
                            deviceAddress = printerAddress,
                            items         = items,
                            customerName  = customerName,
                            batchId       = "",
                            invoiceId     = null,
                            customerAddress = customerAddress,
                            damageItems   = damageForPrinting,
                            paymentMethod = paymentForPrinting,
                            signature     = sigForPrinting
                        )
                    }

                    layoutLoading.visibility = View.GONE
                    val grandTotal = items.sumOf { it.total }
                    startActivity(
                        Intent(this@CurrentOrderActivity, OrderSuccessActivity::class.java).apply {
                            putExtra("batch_id", "")
                            putExtra("invoice_id", "")
                            putExtra("offline_pending", true)
                            putExtra("customer_name", customerName)
                            putExtra("customer_address", customerAddress)
                            putExtra("signature", sigForPrinting)
                            putExtra("damage_items_json", Gson().toJson(damageForPrinting))
                            putExtra("total", grandTotal)
                            putExtra("item_count", items.size)
                            putExtra("orders_json", Gson().toJson(
                                items.map { bi ->
                                    OrderDto(
                                        id = 0, barcode = bi.barcode,
                                        productName = bi.productName, price = bi.price,
                                        quantity = bi.quantity, total = bi.total,
                                        status = "PENDING", customerId = customerId,
                                        customerName = customerName
                                    )
                                }
                            ))
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    )
                    finish()
                    return@onSuccess
                }

                // Flujo online normal
                setStep(2)
                tvLoadingTitle.text = getString(R.string.loading_generating_invoice)
                tvLoadingSubtitle.text = getString(R.string.loading_invoice_qb, response.invoiceId ?: "—")
                orderRepository.clearPending()

                securePrefs.clearActiveCustomer()

                val printerAddress = securePrefs.getPrinterAddress()
                if (!skipPrint && !printerAddress.isNullOrBlank()) {
                    setStep(3)
                    tvLoadingTitle.text = getString(R.string.loading_printing_ticket)
                    tvLoadingSubtitle.text = getString(R.string.loading_connecting_printer)
                    val printResult = PrintService.printTicket(
                        context = this@CurrentOrderActivity,
                        deviceAddress = printerAddress,
                        items = items,
                        customerName = customerName,
                        batchId = response.batchId,
                        invoiceId = response.invoiceId,
                        customerAddress = customerAddress,
                        damageItems = damageForPrinting,
                        paymentMethod = paymentForPrinting,
                        signature = sigForPrinting
                    )
                    printResult.onFailure { e ->
                        Snackbar.make(
                            findViewById(android.R.id.content),
                            getString(R.string.error_order_sent_print_fail, e.localizedMessage ?: getString(R.string.error_no_connection)),
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
                        putExtra("customer_address", customerAddress)
                        putExtra("signature", sigForPrinting)
                        putExtra("damage_items_json", Gson().toJson(damageForPrinting))
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
                    getString(R.string.error_send_order, e.localizedMessage ?: getString(R.string.error_unknown)),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }
}
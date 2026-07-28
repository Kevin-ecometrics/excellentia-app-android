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
import com.example.test.data.ProductDto
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
    private lateinit var tvCreditsTotal: TextView
    private lateinit var tvTotalQty: TextView
    private lateinit var tvTotalItems: TextView
    private lateinit var tvOrderCount: TextView
    private lateinit var tvCustomerLabel: TextView
    private lateinit var tvCustomerAddress: TextView
    private lateinit var btnViewTicket: MaterialButton
    private lateinit var btnFinalize: MaterialButton
    private lateinit var btnAddCreditItem: MaterialButton
    private lateinit var orderRepository: OrderRepository
    private lateinit var securePrefs: SecurePreferences

    private var customerId: String? = null
    private var customerName: String? = null
    private var customerAddress: String? = null
    private var pendingSignature: String? = null
    private var pendingDamageItems: List<com.example.test.data.DamageItem> = emptyList()
    private var pendingPaymentMethod: String? = null
    private var pendingCheckNumber: String? = null
    private var pendingApplyCredit: Double? = null
    private var launchSignatureAfterCustomer = false

    // Fase 82 — el batch se manda a QBO antes del ticket #1 (para tener el
    // invoice real ahí); esto guarda esa respuesta para reusarla al elegir
    // el método de pago (ticket #2 + adjuntar payment_method) sin volver a
    // consultar `orderRepository.getPendingOrders()` (el carrito ya se
    // limpió apenas se mandó el batch).
    private data class SentBatch(
        val response: com.example.test.data.BatchResponse,
        val items: List<BatchItem>,
        val isOfflinePending: Boolean
    )
    private var sentBatch: SentBatch? = null

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
        tvCreditsTotal = findViewById(R.id.tvCreditsTotal)
        tvTotalQty = findViewById(R.id.tvTotalQty)
        tvTotalItems = findViewById(R.id.tvTotalItems)
        tvOrderCount = findViewById(R.id.tvOrderCount)
        tvCustomerLabel = findViewById(R.id.tvCustomerLabel)
        tvCustomerAddress = findViewById(R.id.tvCustomerAddress)
        btnViewTicket = findViewById(R.id.btnViewTicket)
        btnFinalize = findViewById(R.id.btnFinalize)
        btnAddCreditItem = findViewById(R.id.btnAddCreditItem)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        btnViewTicket.setOnClickListener { openTicket() }
        btnAddCreditItem.setOnClickListener { showAddCreditItemDialog() }
        btnFinalize.setOnClickListener {
            pendingApplyCredit = null // reset al inicio del flujo (Fase 83 — el crédito se decide antes de la firma)
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
            val normalItems = pending.filter { !it.isCredit }
            val creditRows = pending.filter { it.isCredit }

            layoutOrderItems.removeAllViews()

            if (pending.isEmpty()) {
                layoutEmpty.visibility = View.VISIBLE
                tvGrandTotal.text = getString(R.string.default_total_zero)
                tvCreditsTotal.visibility = View.GONE
                tvTotalQty.text = getString(R.string.default_qty_zero)
                tvTotalItems.text = getString(R.string.label_products_count, 0)
                tvOrderCount.text = getString(R.string.default_count_zero)
                btnFinalize.isEnabled = false
                btnViewTicket.isEnabled = false
                return@launch
            }

            layoutEmpty.visibility = View.GONE
            // No se puede finalizar solo con créditos — el backend exige
            // items[] no vacío (BatchRequest siempre manda al menos un
            // producto real).
            btnFinalize.isEnabled = normalItems.isNotEmpty()
            btnViewTicket.isEnabled = normalItems.isNotEmpty()

            val inflater = LayoutInflater.from(this@CurrentOrderActivity)
            for (order in normalItems) {
                val row = inflater.inflate(R.layout.item_pending_order, layoutOrderItems, false)
                val unitLabel = if (order.unit.isNullOrBlank() || order.unit == "Lbs") "lb" else order.unit
                row.findViewById<TextView>(R.id.tvPendingName).text = order.productName
                row.findViewById<TextView>(R.id.tvPendingMeta).text =
                    "${order.barcode}  ·  ${String.format(Locale.US, "$%.2f total", order.price * order.quantity)}"
                row.findViewById<TextView>(R.id.tvPendingQtyTotal).text =
                    String.format(Locale.US, "%.2f %s  =  $%.2f", order.quantity, unitLabel, order.price * order.quantity)
                row.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditItem)
                    .setOnClickListener { editItem(order) }
                row.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeleteItem)
                    .setOnClickListener { confirmDelete(order.id, order.productName) }
                layoutOrderItems.addView(row)
            }

            // Créditos agregados a mano (btnAddCreditItem) — persistidos en
            // la misma tabla (Fase 86, is_credit = true), sobreviven cierre
            // de la app igual que los productos normales. Mismo layout de
            // fila, pero en rojo y sin editar, solo borrar (confirmDelete
            // reusa el mismo diálogo de confirmación que los productos
            // normales — es la misma tabla, mismo id). Sí se restan del
            // ORDER TOTAL mostrado acá (ver más abajo) — es una estimación
            // local (mismo criterio que el ticket offline, creditsTotalOf()
            // en data/Models.kt); el backend recalcula la cifra autoritativa
            // al mandar el batch.
            for (credit in creditRows) {
                val row = inflater.inflate(R.layout.item_pending_order, layoutOrderItems, false)
                row.findViewById<TextView>(R.id.tvPendingName).text =
                    "${getString(R.string.label_credit_item_tag)} · ${credit.productName}"
                row.findViewById<TextView>(R.id.tvPendingMeta).text = credit.barcode
                row.findViewById<TextView>(R.id.tvPendingQtyTotal).apply {
                    text = String.format(Locale.US, "%d × -$%.2f", credit.quantity.toInt(), credit.quantity * credit.price)
                    setTextColor(getColor(R.color.red))
                }
                row.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditItem).visibility = View.GONE
                row.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeleteItem)
                    .setOnClickListener { confirmDelete(credit.id, credit.productName) }
                layoutOrderItems.addView(row)
            }

            val grandTotal = normalItems.sumOf { it.price * it.quantity }
            val creditsTotal = creditRows.sumOf { it.price * it.quantity }
            val totalQty = normalItems.sumOf { it.quantity }
            val overallUnit = normalItems.firstOrNull()?.let {
                if (it.unit.isNullOrBlank() || it.unit == "Lbs") "lb" else it.unit
            } ?: "lb"
            tvGrandTotal.text = String.format(Locale.US, "$%.2f", grandTotal - creditsTotal)
            if (creditsTotal > 0) {
                tvCreditsTotal.text = getString(R.string.label_order_credits, creditsTotal)
                tvCreditsTotal.visibility = View.VISIBLE
            } else {
                tvCreditsTotal.visibility = View.GONE
            }
            tvTotalQty.text = String.format(Locale.US, "%.2f %s", totalQty, overallUnit)
            tvTotalItems.text = getString(R.string.label_products_count, normalItems.size)
            tvOrderCount.text = normalItems.size.toString()
        }
    }

    // Reabre la misma pantalla que se usa al escanear/agregar, precargada con los
    // datos de esta fila — reemplaza el diálogo genérico anterior (que solo
    // manejaba "cantidad + precio/lb" y no tenía sentido para Case/Unit/Bucket).
    // KEY_PRICE se manda como precio POR UNIDAD (no por caja): para Case se
    // reconstruye dividiendo por caseQty, así ProductDetailActivity puede volver a
    // multiplicar por caseQty con su lógica normal sin casos especiales.
    private fun editItem(order: com.example.test.data.local.entities.PendingOrderEntity) {
        val isCaseBased = order.unit.equals("Case", ignoreCase = true) && (order.caseQty ?: 0) > 0
        val perUnitPrice = if (isCaseBased) order.price / (order.caseQty ?: 1) else order.price
        startActivity(
            Intent(this, ProductDetailActivity::class.java).apply {
                putExtra("BARCODE", order.barcode)
                putExtra("PRODUCT_NAME", order.productName)
                putExtra("PRODUCT_PRICE", perUnitPrice)
                putExtra("QUANTITY", order.quantity)
                putExtra("UNIT", order.unit)
                putExtra("CASE_QTY", order.caseQty ?: 0)
                putExtra("CUSTOMER_ID", customerId)
                putExtra("CUSTOMER_NAME", customerName)
                putExtra("EDIT_ORDER_ID", order.id)
            }
        )
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

    // Botón "+ Agregar crédito" — busca cualquier producto (no hace falta que
    // esté en el carrito) y lo agrega como línea de crédito a este pedido,
    // igual que hace askDamagedItems() para los productos que sí están en el
    // carrito. Ambos caminos terminan en el mismo pendingDamageItems, así que
    // comparten toda la lógica de cálculo/impresión/envío ya existente.
    private fun showAddCreditItemDialog() {
        val ctx = this
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val etSearch = android.widget.EditText(ctx).apply {
            hint = getString(R.string.hint_product_name_search)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val tvStatus = TextView(ctx).apply {
            textSize = 13f
            setPadding(0, 12, 0, 0)
            setTextColor(getColor(R.color.text_secondary))
            text = getString(R.string.label_type_to_search)
        }
        val listHeightPx = (240 * resources.displayMetrics.density).toInt()
        val lvResults = android.widget.ListView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, listHeightPx
            )
            visibility = View.GONE
            divider = android.graphics.drawable.ColorDrawable(getColor(R.color.text_secondary))
            dividerHeight = 1
            clipToPadding = false
            setPadding(0, 0, 0, 24)
        }
        layout.addView(etSearch)
        layout.addView(tvStatus)
        layout.addView(lvResults)

        var foundProducts: List<ProductDto> = emptyList()
        val resultsAdapter = android.widget.ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, mutableListOf())
        lvResults.adapter = resultsAdapter

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.title_search_product_by_name))
            .setView(layout)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()

        lvResults.setOnItemClickListener { _, _, idx, _ ->
            foundProducts.getOrNull(idx)?.let { p ->
                dialog.dismiss()
                askCreditQtyThenAdd(p)
            }
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: return
                if (query.length < 2) {
                    tvStatus.text = getString(R.string.label_type_at_least_2)
                    lvResults.visibility = View.GONE
                    return
                }
                tvStatus.text = getString(R.string.label_searching)
                lvResults.visibility = View.GONE
                lifecycleScope.launch {
                    try {
                        val resp = RetrofitClient.getApi().searchProducts(query)
                        if (resp.isSuccessful) {
                            foundProducts = resp.body()?.data ?: emptyList()
                            if (foundProducts.isEmpty()) {
                                tvStatus.text = getString(R.string.label_no_results, query)
                                lvResults.visibility = View.GONE
                            } else {
                                tvStatus.text = ""
                                resultsAdapter.clear()
                                foundProducts.forEach { p ->
                                    resultsAdapter.add("${p.name}  ·  $${String.format(Locale.US, "%.2f", p.price)}/${p.unit ?: "lb"}  ·  ${p.barcode ?: getString(R.string.no_barcode_label)}")
                                }
                                lvResults.visibility = View.VISIBLE
                                lvResults.post { lvResults.setSelection(0) }
                            }
                        }
                    } catch (_: Exception) {
                        tvStatus.text = getString(R.string.label_search_error)
                        lvResults.visibility = View.GONE
                    }
                }
            }
        })

        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    // Espeja unitValueOf() del backend (creditCalculator.ts) — mismo criterio
    // que IssueCreditActivity, para un producto recién buscado (no viene de
    // una fila del carrito, así que no aplica el ajuste por caseQty que usa
    // unitValueOf(order: PendingOrderEntity) más abajo).
    private fun estimatedUnitValueOf(product: ProductDto): Double =
        if (product.unit.equals("Case", true) || product.unit.equals("Unit", true) || product.unit.equals("Bucket", true))
            product.price
        else
            product.price * (product.weightPerUnit ?: 1.0)

    private fun askCreditQtyThenAdd(product: ProductDto) {
        val barcode = product.barcode ?: return
        val ctx = this
        val etQty = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.hint_credit_qty)
            setText("1")
            selectAll()
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.title_credit_qty))
            .setMessage(product.name)
            .setView(etQty)
            .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                val qty = etQty.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
                lifecycleScope.launch {
                    orderRepository.saveCreditItem(barcode, product.name, qty, estimatedUnitValueOf(product))
                    loadOrder()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun openTicket() {
        lifecycleScope.launch {
            val pending = orderRepository.getPendingOrders()
            val normalItems = pending.filter { !it.isCredit }
            if (normalItems.isEmpty()) return@launch
            val orders = normalItems.map { order ->
                OrderDto(
                    id = order.id,
                    barcode = order.barcode,
                    productName = order.productName,
                    price = order.price,
                    quantity = order.quantity,
                    total = order.price * order.quantity,
                    status = "PENDING",
                    customerId = customerId,
                    customerName = customerName,
                    unit = order.unit,
                    caseQty = order.caseQty
                )
            }
            // Si pendingDamageItems ya tiene contenido es porque askDamagedItems()
            // ya corrió en esta sesión y ya mergeó los créditos persistidos — no
            // volver a agregarlos acá (se duplicarían). Si está vacío (preview
            // antes de iniciar el flujo de finalizar), se arma desde lo persistido.
            val previewDamageItems = pendingDamageItems.ifEmpty {
                pending.filter { it.isCredit }.map { it.toDamageItem() }
            }
            startActivity(Intent(this@CurrentOrderActivity, TicketDetailActivity::class.java).apply {
                putExtra("batch_id", "")
                putExtra("invoice_id", "")
                putExtra("orders_json", Gson().toJson(orders))
                putExtra("customer_address", customerAddress)
                putExtra("damage_items_json", Gson().toJson(previewDamageItems))
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

    // Valor por unidad de un producto en el carrito, para la estimación LOCAL
    // del crédito (preview antes de finalizar, y ticket offline). Espeja la
    // regla autoritativa del backend (creditCalculator.ts): para Case,
    // order.price ya es el precio de la caja completa (price/unit × caseQty,
    // per ProductDetailActivity) — se divide para volver al valor de una sola
    // unidad. Para Lbs/Unit/Bucket, order.price ya es efectivamente el valor
    // por unidad.
    private fun unitValueOf(order: com.example.test.data.local.entities.PendingOrderEntity): Double =
        if (order.unit.equals("Case", ignoreCase = true) && (order.caseQty ?: 0) > 0)
            order.price / order.caseQty!!
        else
            order.price

    // Fase 86 — una fila de crédito (is_credit = true) ya guarda barcode/
    // productName/quantity/price con la semántica correcta de DamageItem
    // (price = unitPrice directo, sin el ajuste por caseQty de unitValueOf()
    // de arriba, porque se guardó así desde estimatedUnitValueOf() al
    // agregarla) — solo hace falta el cast de tipos.
    private fun com.example.test.data.local.entities.PendingOrderEntity.toDamageItem(): com.example.test.data.DamageItem =
        com.example.test.data.DamageItem(
            barcode     = barcode,
            productName = productName,
            qty         = quantity.toInt(),
            unitPrice   = price
        )

    private fun askDamagedItems() {
        lifecycleScope.launch {
            val allPending = orderRepository.getPendingOrders()
            val pending = allPending.filter { !it.isCredit }
            val creditRows = allPending.filter { it.isCredit }
            if (pending.isEmpty()) {
                pendingDamageItems = creditRows.map { it.toDamageItem() }
                askApplyCredit()
                return@launch
            }

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

                val unitLabel = if (order.unit.isNullOrBlank() || order.unit == "Lbs") "lb" else order.unit
                val tvDetail = android.widget.TextView(ctx).apply {
                    val detailText = if (order.unit.equals("Case", ignoreCase = true) && (order.caseQty ?: 0) > 0) {
                        val perUnitPrice = order.price / order.caseQty!!
                        String.format(Locale.US, "%.0f %s of %d · \$%.2f/unit", order.quantity, unitLabel, order.caseQty, perUnitPrice)
                    } else if (order.unit.isNullOrBlank() || order.unit == "Lbs") {
                        String.format(Locale.US, "%.2f %s · \$%.2f/%s", order.quantity, unitLabel, order.price, unitLabel)
                    } else {
                        String.format(Locale.US, "%.0f %s · \$%.2f/%s", order.quantity, unitLabel, order.price, unitLabel)
                    }
                    text = detailText
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val etQty = android.widget.EditText(ctx).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    // Prellena si este barcode ya tiene qty de un crédito
                    // agregado a mano (btnAddCreditItem) para el mismo producto.
                    setText((pendingDamageItems.firstOrNull { it.barcode == order.barcode }?.qty
                        ?: 0).toString())
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
                    // Los créditos agregados a mano (btnAddCreditItem) ya
                    // están persistidos aparte (Fase 86, is_credit = true) —
                    // se agregan siempre, sin importar lo que se marque acá
                    // para los productos del carrito.
                    val fromCart = inputs.mapNotNull { (order, et) ->
                        val qty = et.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                        if (qty > 0) com.example.test.data.DamageItem(
                            barcode     = order.barcode,
                            productName = order.productName,
                            qty         = qty,
                            unitPrice   = unitValueOf(order)
                        ) else null
                    }
                    pendingDamageItems = fromCart + creditRows.map { it.toDamageItem() }
                    askApplyCredit()
                }
                .setNegativeButton(getString(R.string.btn_none)) { _, _ ->
                    pendingDamageItems = creditRows.map { it.toDamageItem() }
                    askApplyCredit()
                }
                .show()
        }
    }

    private fun toBatchItems(pending: List<com.example.test.data.local.entities.PendingOrderEntity>): List<BatchItem> =
        pending.map { order ->
            BatchItem(
                barcode = order.barcode,
                productName = order.productName,
                price = order.price,
                quantity = order.quantity,
                total = order.price * order.quantity,
                unit = order.unit,
                caseQty = order.caseQty
            )
        }

    private fun askPaymentMethod(skipPrint: Boolean) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_payment_method))
            .setMessage(getString(R.string.msg_payment_method))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.btn_cash)) { _, _ ->
                pendingPaymentMethod = "Cash"
                pendingCheckNumber = null
                sendBatchAndPrint(skipPrint)
            }
            .setNeutralButton(getString(R.string.btn_check)) { _, _ ->
                pendingPaymentMethod = "Check"
                val input = android.widget.EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    hint = getString(R.string.hint_check_number)
                }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.title_check_number))
                    .setMessage(getString(R.string.msg_check_number))
                    .setView(input)
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                        pendingCheckNumber = input.text.toString().take(20)
                        sendBatchAndPrint(skipPrint)
                    }
                    .setNegativeButton(getString(R.string.btn_cancel)) { _, _ ->
                        // Vuelve al diálogo de método de pago
                        askPaymentMethod(skipPrint)
                    }
                    .show()
            }
            .setNegativeButton(getString(R.string.btn_account)) { _, _ ->
                pendingPaymentMethod = "On Account"
                pendingCheckNumber = null
                sendBatchAndPrint(skipPrint)
            }
            .show()
    }

    // Fase 83 — confirmar impresora corre después de la firma, no antes del
    // crédito: el crédito aplicado es una línea real de descuento en la
    // factura de QBO (a diferencia del método de pago, que es solo memo), así
    // que debe quedar resuelto ANTES de mandar el batch/crear la factura
    // (que pasa en printFirstTicketThenAskPayment). Orden completo: dañados →
    // crédito → firma → impresora → mandar batch + ticket #1 → método de
    // pago → adjuntar pago + ticket #2.
    private fun checkPrinterThenFinalize() {
        val printerAddress = securePrefs.getPrinterAddress()
        val printerName    = securePrefs.getPrinterName() ?: getString(R.string.label_no_printer_selected)

        if (printerAddress.isNullOrBlank()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.title_no_printer))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(getString(R.string.msg_no_printer))
                .setPositiveButton(getString(R.string.btn_continue_no_print)) { _, _ ->
                    printFirstTicketThenAskPayment(skipPrint = true)
                }
                .setNeutralButton(getString(R.string.btn_go_to_settings)) { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        } else {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.title_confirm_print))
                .setMessage(getString(R.string.msg_confirm_print, printerName))
                .setPositiveButton(getString(R.string.btn_finalize_and_print)) { _, _ ->
                    printFirstTicketThenAskPayment(skipPrint = false)
                }
                .setNeutralButton(getString(R.string.btn_finalize_no_print)) { _, _ ->
                    printFirstTicketThenAskPayment(skipPrint = true)
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    private fun askApplyCredit() {
        if (customerId.isNullOrBlank()) { launchSignature(); return }
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getCustomerCreditBalance(customerId!!)
                if (resp.isSuccessful) {
                    val balance = resp.body()!!
                    if (balance.balance > 0) {
                        val pendingAll = orderRepository.getPendingOrders()
                        val netTotal = pendingAll.filter { !it.isCredit }.sumOf { it.price * it.quantity } -
                            pendingAll.filter { it.isCredit }.sumOf { it.price * it.quantity }
                        val maxApply = minOf(balance.balance, netTotal.coerceAtLeast(0.0))
                        val msg = getString(R.string.msg_credit_apply,
                            String.format(Locale.US, "%.2f", balance.balance),
                            String.format(Locale.US, "%.2f", maxApply))
                        val etAmount = android.widget.EditText(this@CurrentOrderActivity).apply {
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                            hint = getString(R.string.hint_credit_amount)
                            setText(String.format(Locale.US, "%.2f", maxApply))
                            selectAll()
                        }
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@CurrentOrderActivity)
                            .setTitle(getString(R.string.title_credit_available))
                            .setMessage(msg)
                            .setView(etAmount)
                            .setCancelable(false)
                            .setPositiveButton(getString(R.string.btn_apply)) { _, _ ->
                                val entered = etAmount.text.toString().toDoubleOrNull() ?: 0.0
                                val clamped = entered.coerceIn(0.0, maxApply)
                                pendingApplyCredit = if (clamped > 0) clamped else null
                                launchSignature()
                            }
                            .setNegativeButton(getString(R.string.btn_no_apply_credit)) { _, _ ->
                                pendingApplyCredit = null
                                launchSignature()
                            }
                            .show()
                        return@launch
                    }
                }
            } catch (_: Exception) { }
            launchSignature()
        }
    }

    // Fase 82 — el batch se manda a QBO (createBatch) acá, ANTES del diálogo
    // de método de pago, para que el ticket #1 ya traiga el número de
    // factura real. payment_method/check_number todavía no se conocen — se
    // mandan null y se "pegan" después (attachPaymentMethod/Offline) una vez
    // elegidos, sin tocar el CustomerMemo de la factura en QBO (decisión
    // explícita del usuario: con el ticket impreso alcanza).
    private fun printFirstTicketThenAskPayment(skipPrint: Boolean) {
        btnFinalize.isEnabled = false
        btnViewTicket.isEnabled = false
        lifecycleScope.launch {
            layoutLoading.visibility = View.VISIBLE
            setStep(1)
            tvLoadingTitle.text = getString(R.string.loading_sending_order)
            tvLoadingSubtitle.text = if (!customerName.isNullOrBlank())
                getString(R.string.loading_client, customerName) else getString(R.string.loading_connecting_qb)

            // Solo productos reales van como items[] (líneas positivas de la
            // factura) — los créditos (is_credit = true) ya viven en
            // pendingDamageItems (sembrado por askDamagedItems()) y se mandan
            // aparte como damage_items, nunca como ítem positivo.
            val pending = orderRepository.getPendingOrders().filter { !it.isCredit }
            if (pending.isEmpty()) {
                layoutLoading.visibility = View.GONE
                finish()
                return@launch
            }

            val items = toBatchItems(pending)

            val result = orderRepository.sendBatch(
                items, customerId, customerName, pendingSignature, pendingDamageItems,
                paymentMethod = null, checkNumber = null, applyCredit = pendingApplyCredit
            )

            result.onSuccess { response ->
                val isOfflinePending = response.batchId == "OFFLINE_PENDING"
                sentBatch = SentBatch(response, items, isOfflinePending)

                if (!isOfflinePending) {
                    setStep(2)
                    tvLoadingTitle.text = getString(R.string.loading_generating_invoice)
                    tvLoadingSubtitle.text = getString(R.string.loading_invoice_qb, response.invoiceId ?: "—")
                } else {
                    setStep(2)
                    tvLoadingTitle.text = getString(R.string.loading_saving_offline)
                    tvLoadingSubtitle.text = ""
                }

                orderRepository.clearPending()
                securePrefs.clearActiveCustomer()

                val printerAddress = securePrefs.getPrinterAddress()
                if (!skipPrint && !printerAddress.isNullOrBlank()) {
                    setStep(3)
                    tvLoadingTitle.text = getString(R.string.loading_printing_ticket)
                    tvLoadingSubtitle.text = getString(R.string.loading_connecting_printer)

                    // Primer ticket (Fase 76/82) — ya con el invoice real (si
                    // hubo conexión), sin Payment: todavía.
                    PrintService.printTicket(
                        context = this@CurrentOrderActivity,
                        deviceAddress = printerAddress,
                        items = items,
                        customerName = customerName,
                        batchId = response.batchId,
                        invoiceId = if (isOfflinePending) null else response.invoiceId,
                        invoiceNumber = if (isOfflinePending) null else response.invoiceNumber,
                        customerAddress = customerAddress,
                        damageItems = pendingDamageItems,
                        paymentMethod = null,
                        signature = pendingSignature,
                        creditsTotal = response.creditsTotal,
                        checkNumber = null,
                        creditApplied = pendingApplyCredit
                    ).onFailure { e ->
                        Snackbar.make(
                            findViewById(android.R.id.content),
                            getString(R.string.error_print_generic, e.localizedMessage ?: getString(R.string.error_no_connection)),
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                }

                layoutLoading.visibility = View.GONE
                askPaymentMethod(skipPrint)
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

    // Fase 82 — el batch ya se mandó (printFirstTicketThenAskPayment); acá
    // solo se adjunta el payment_method/check_number recién elegido (al
    // batch ya sentado en el servidor, o al que sigue encolado offline) y se
    // imprime el segundo ticket con el mismo invoice + Payment:.
    private fun sendBatchAndPrint(skipPrint: Boolean) {
        val sent = sentBatch ?: return
        val sigForPrinting     = pendingSignature
        val damageForPrinting  = pendingDamageItems
        val paymentForPrinting = pendingPaymentMethod
        val checkForPrinting   = pendingCheckNumber
        val creditForPrinting  = pendingApplyCredit

        pendingSignature     = null
        pendingDamageItems   = emptyList()
        pendingPaymentMethod = null
        pendingCheckNumber   = null
        pendingApplyCredit   = null
        sentBatch = null

        lifecycleScope.launch {
            layoutLoading.visibility = View.VISIBLE
            setStep(1)
            tvLoadingTitle.text = getString(R.string.loading_saving_payment)
            tvLoadingSubtitle.text = ""

            if (!sent.isOfflinePending && sent.response.batchId.isNotBlank()) {
                orderRepository.attachPaymentMethod(sent.response.batchId, paymentForPrinting, checkForPrinting)
            } else {
                sent.response.localPendingId?.let {
                    orderRepository.attachPaymentMethodOffline(it, paymentForPrinting, checkForPrinting)
                }
            }

            val printerAddress = securePrefs.getPrinterAddress()
            if (!skipPrint && !printerAddress.isNullOrBlank()) {
                setStep(2)
                tvLoadingTitle.text = getString(R.string.loading_printing_ticket)
                tvLoadingSubtitle.text = getString(R.string.loading_connecting_printer)

                // Segundo ticket (Fase 76) — mismo invoice del ticket #1,
                // ahora con Payment:.
                PrintService.printTicket(
                    context = this@CurrentOrderActivity,
                    deviceAddress = printerAddress,
                    items = sent.items,
                    customerName = customerName,
                    batchId = sent.response.batchId,
                    invoiceId = if (sent.isOfflinePending) null else sent.response.invoiceId,
                    invoiceNumber = if (sent.isOfflinePending) null else sent.response.invoiceNumber,
                    customerAddress = customerAddress,
                    damageItems = damageForPrinting,
                    paymentMethod = paymentForPrinting,
                    signature = sigForPrinting,
                    creditsTotal = sent.response.creditsTotal,
                    checkNumber = checkForPrinting,
                    creditApplied = creditForPrinting
                ).onFailure { e ->
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        getString(R.string.error_order_sent_print_fail, e.localizedMessage ?: getString(R.string.error_no_connection)),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }

            layoutLoading.visibility = View.GONE
            val grandTotal = sent.items.sumOf { it.total }
            val creditAppliedForTicket = sent.response.creditApplied ?: creditForPrinting
            startActivity(
                Intent(this@CurrentOrderActivity, OrderSuccessActivity::class.java).apply {
                    putExtra("batch_id", sent.response.batchId ?: "")
                    putExtra("invoice_id", sent.response.invoiceId ?: "")
                    putExtra("invoice_number", sent.response.invoiceNumber ?: 0)
                    putExtra("offline_pending", sent.isOfflinePending)
                    putExtra("customer_name", customerName)
                    putExtra("customer_address", customerAddress)
                    putExtra("signature", sigForPrinting)
                    putExtra("damage_items_json", Gson().toJson(damageForPrinting))
                    putExtra("total", grandTotal)
                    putExtra("item_count", sent.items.size)
                    putExtra("credits_total", sent.response.creditsTotal ?: -1.0)
                    putExtra("credit_applied", creditAppliedForTicket ?: 0.0)
                    putExtra("orders_json", Gson().toJson(
                        sent.items.map { bi ->
                            OrderDto(
                                id = 0,
                                barcode = bi.barcode,
                                productName = bi.productName,
                                price = bi.price,
                                quantity = bi.quantity,
                                total = bi.total,
                                status = if (sent.isOfflinePending) "PENDING" else "SENT",
                                customerId = customerId,
                                customerName = customerName,
                                unit = bi.unit,
                                caseQty = bi.caseQty,
                                creditApplied = creditAppliedForTicket,
                                paymentMethod = paymentForPrinting,
                                checkNumber = checkForPrinting
                            )
                        }
                    ))
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            finish()
        }
    }
}
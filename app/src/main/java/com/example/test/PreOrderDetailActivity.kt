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

import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.BatchItem
import com.example.test.data.ConvertPreOrderRequest
import com.example.test.data.ConvertPreOrderResponse
import com.example.test.data.DamageItem
import com.example.test.data.OrderDto
import com.example.test.data.PreOrderDto
import com.example.test.data.PreOrderItem
import com.example.test.data.UpdatePaymentRequest
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.example.test.data.print.PrintService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PreOrderDetailActivity : BaseActivity() {

    private lateinit var tvDetailCustomer: TextView
    private lateinit var tvDetailStatus: TextView
    private lateinit var tvDetailDate: TextView
    private lateinit var tvDetailSalesperson: TextView
    private lateinit var tvDetailNotes: TextView
    private lateinit var tvItemsSectionHeader: TextView
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
    // pre_orders no guarda dirección (solo customer_id) — se resuelve al vuelo
    // contra el cliente de QB para que el ticket impreso al convertir sí la
    // incluya (antes viajaba null/"" siempre).
    private var resolvedCustomerAddress: String? = null

    private var pendingSignature: String? = null
    private var pendingDamageItems: List<DamageItem> = emptyList()
    private var pendingPaymentMethod: String? = null
    private var pendingCheckNumber: String? = null
    private var pendingApplyCredit: Double? = null

    // Doble impresión — mismo patrón que CurrentOrderActivity (Fase 76→83): la
    // conversión (con su factura QBO real) se manda ANTES de preguntar el método
    // de pago, para que el ticket #1 ya tenga el número de factura; el pago se
    // adjunta después vía el endpoint genérico updateBatchPayment (el mismo que
    // usan los pedidos normales — convertPreOrder ya escribe en `orders` con el
    // mismo esquema de batch_id) y se imprime el ticket #2 con "Payment: X".
    private data class SentConversion(
        val response: ConvertPreOrderResponse,
        val items: List<BatchItem>,
        val signature: String?,
        val damageItems: List<DamageItem>,
        val creditApplied: Double?
    )
    private var sentConversion: SentConversion? = null

    // Fase 87 → captura temprana: una pre-orden DRAFT/CONFIRMED puede traer items ya
    // con quantity/unit/caseQty (capturados en CreatePreOrderActivity con el mismo
    // stepper de ProductDetailActivity), pero el precio nunca se considera definitivo
    // hasta acá — antes de convertir, cada item debe "finalizarse" con el precio
    // FRESCO del catálogo: si ya hay cantidad guardada, con un diálogo de
    // confirmación rápida (quickFinalizeItem, sin reabrir el stepper) o "Cambiar"
    // (reabre el stepper precargado); si no hay cantidad guardada (pre-órdenes viejas
    // sin este dato, o creadas vía "Reusar pre-orden", que descarta el detalle),
    // reabriendo el stepper completo (finalizeItem) como en el diseño original.
    private var draftItems: List<PreOrderItem> = emptyList()
    private val finalizedByIndex = mutableMapOf<Int, List<PreOrderItem>>()
    private var pendingFinalizeIndex: Int = -1

    private val finalizedItemsFlat: List<PreOrderItem>
        get() = draftItems.indices.flatMap { finalizedByIndex[it] ?: emptyList() }

    private val signatureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingSignature = result.data?.getStringExtra("signature")
            askDamagedItems()
        }
    }

    private val finalizeItemLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val idx = pendingFinalizeIndex
        pendingFinalizeIndex = -1
        if (result.resultCode == Activity.RESULT_OK && idx >= 0) {
            val itemsJson = result.data?.getStringExtra(ProductDetailActivity.RESULT_ITEMS_JSON)
            if (itemsJson != null) {
                try {
                    val type = object : TypeToken<List<PreOrderItem>>() {}.type
                    finalizedByIndex[idx] = Gson().fromJson(itemsJson, type)
                    renderItemsSection()
                } catch (_: Exception) {
                    showError(getString(R.string.error_finalizing_item))
                }
            }
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
        tvDetailSalesperson = findViewById(R.id.tvDetailSalesperson)
        tvDetailNotes     = findViewById(R.id.tvDetailNotes)
        tvItemsSectionHeader = findViewById(R.id.tvItemsSectionHeader)
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
                    if (po != null) {
                        currentPreOrder = po
                        renderPreOrder(po)
                        if (!po.customerId.isNullOrBlank()) {
                            try {
                                val custResp = RetrofitClient.getApi().getCustomer(po.customerId)
                                resolvedCustomerAddress = custResp.takeIf { it.isSuccessful }?.body()?.fullAddress
                            } catch (_: Exception) { }
                        }
                    }
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
            "DRAFT"     -> getString(R.string.status_draft)
            "CONFIRMED" -> getString(R.string.status_confirmed)
            "CONVERTED" -> getString(R.string.status_converted)
            "CANCELLED" -> getString(R.string.status_cancelled)
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
                val formatted = formatDate(raw, "MMM dd, yyyy")
                append(getString(R.string.label_scheduled_delivery, formatted))
            }
            po.createdAt?.let { raw ->
                val formatted = formatDate(raw, "MMM dd, yyyy  HH:mm")
                if (isNotEmpty()) append("\n")
                append(getString(R.string.label_created_on, formatted))
            }
        }
        tvDetailDate.text = dateStr

        if (!po.salespersonName.isNullOrBlank()) {
            tvDetailSalesperson.text = getString(R.string.label_salesperson_prefix, po.salespersonName)
            tvDetailSalesperson.visibility = View.VISIBLE
        } else {
            tvDetailSalesperson.visibility = View.GONE
        }

        if (!po.notes.isNullOrBlank()) {
            tvDetailNotes.text = getString(R.string.label_notes_prefix, po.notes)
            tvDetailNotes.visibility = View.VISIBLE
        } else {
            tvDetailNotes.visibility = View.GONE
        }

        if (po.status == "DRAFT" || po.status == "CONFIRMED") {
            tvItemsSectionHeader.text = getString(R.string.label_products_section)
            draftItems = po.items
            finalizedByIndex.clear()
            renderItemsSection()
        } else {
            renderReadOnlyItems(po)
        }

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

    // Render de solo-lectura para CONVERTED/CANCELLED — mismo comportamiento que antes
    // de esta feature: los items ya tienen precio/peso/case persistidos (convertPreOrder
    // los reescribe en pre_order_items al convertir).
    private fun renderReadOnlyItems(po: PreOrderDto) {
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
            val unitLabel = if (item.unit.isNullOrBlank() || item.unit == "Lbs") "lb" else item.unit
            val price = item.price ?: 0.0
            val quantity = item.quantity ?: 0.0
            val total = item.total ?: 0.0
            val tvName = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "${item.productName}\n${String.format(Locale.US, "%.2f %s × \$%.2f/%s", quantity, unitLabel, price, unitLabel)}"
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
            }
            val tvItemTotal = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                text = String.format(Locale.US, "$%.2f", total)
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
            }
            row.addView(tvName)
            row.addView(tvItemTotal)
            layoutDetailItems.addView(row)
            runningTotal += total
        }
        tvDetailTotal.text = String.format(Locale.US, "$%.2f", runningTotal)
    }

    // Render para DRAFT/CONFIRMED — cada draftItem sin detallar muestra un botón
    // "Detallar"; una vez finalizado muestra el resumen y un botón "Editar". btnConvert
    // solo se habilita cuando todos los draftItems tienen su fila en finalizedByIndex.
    private fun renderItemsSection() {
        layoutDetailItems.removeAllViews()
        var finalizedCount = 0
        var runningTotal = 0.0

        for ((idx, draft) in draftItems.withIndex()) {
            val rows = finalizedByIndex[idx]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dp }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = draft.productName
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.text_primary))
            })
            if (rows != null) {
                // (c) ya finalizado en esta sesión — sin cambios.
                finalizedCount++
                val qtySum = rows.sumOf { it.quantity ?: 0.0 }
                val totalSum = rows.sumOf { it.total ?: 0.0 }
                runningTotal += totalSum
                val unitLabel = rows.firstOrNull()?.unit?.let { if (it.isBlank() || it == "Lbs") "lb" else it } ?: "lb"
                info.addView(TextView(this).apply {
                    text = String.format(Locale.US, "%.2f %s · \$%.2f", qtySum, unitLabel, totalSum)
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                })
            } else if (draft.quantity != null) {
                // (a) tiene cantidad guardada desde la creación, todavía no
                // finalizado en esta sesión — mostrarla para que el usuario la
                // vea antes de tocar el botón (que abrirá el diálogo de confirmación).
                val unitLabel = draft.unit?.let { if (it.isBlank() || it == "Lbs") "lb" else it } ?: "lb"
                info.addView(TextView(this).apply {
                    text = getString(R.string.label_saved_quantity,
                        String.format(Locale.US, "%.2f %s", draft.quantity ?: 0.0, unitLabel))
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                })
            } else {
                // (b) fallback — pre-orden vieja sin cantidad guardada, o creada vía
                // "Reusar pre-orden" (descarta el detalle). Sin cambios.
                info.addView(TextView(this).apply {
                    text = draft.barcode
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                })
            }
            row.addView(info)
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = if (rows != null) getString(R.string.btn_edit_detail) else getString(R.string.btn_add_detail)
                textSize = 12f
                setOnClickListener {
                    if (rows == null && draft.quantity != null) {
                        showConfirmSavedQuantityDialog(idx, draft)
                    } else {
                        finalizeItem(idx)
                    }
                }
            })
            layoutDetailItems.addView(row)
        }

        val allDone = draftItems.isNotEmpty() && finalizedCount == draftItems.size
        tvDetailTotal.text = if (finalizedCount > 0) String.format(Locale.US, "$%.2f", runningTotal) else "—"
        tvItemsSectionHeader.text = getString(R.string.label_products_section) + "  ·  " +
            getString(R.string.label_items_detailed_progress, finalizedCount, draftItems.size)
        btnConvert.isEnabled = allDone
        btnConvert.alpha = if (allDone) 1f else 0.5f
    }

    // Pide el producto FRESCO del catálogo (precio/unit/caseQty pueden haber cambiado
    // desde que se creó el borrador — es el punto de este feature) y relanza el mismo
    // stepper que usaba CreatePreOrderActivity, ahora acá.
    private fun finalizeItem(index: Int, prefillUnits: Double? = null) {
        val po = currentPreOrder ?: return
        val draft = draftItems.getOrNull(index) ?: return
        if (draft.barcode.isBlank() || draft.barcode == "unknown") {
            showError(getString(R.string.error_no_barcode_preorder))
            return
        }
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getProductByBarcode(draft.barcode)
                val product = if (resp.isSuccessful) resp.body()?.data else null
                if (product == null) {
                    showError(getString(R.string.error_product_not_found_barcode, draft.barcode))
                    return@launch
                }
                // Igual que MainActivity.openDetail(): para productos Case, la tabla
                // `products` no tiene columna case_qty — el tamaño real de la caja viaja
                // en `qty` y ProductDetailActivity lo recupera del extra QUANTITY (su
                // fallback interno usa QUANTITY como caseQty cuando CASE_QTY llega en 0).
                // Mandar 1.0 fijo acá hacía que toda caja se tratara como "caja de 1".
                //
                // "Cambiar" en el diálogo de confirmación manda prefillUnits (la
                // cantidad ya guardada). Para case-based, QUANTITY ya está ocupado con
                // el tamaño de la caja, así que la cantidad elegida viaja aparte por
                // PREFILL_UNITS; para peso/unidad simple no hay conflicto y puede
                // reemplazar directo el seed de QUANTITY.
                val isCaseBasedProduct = com.example.test.data.isCaseUnitType(product.unit) && (product.caseQty ?: 0) > 0
                val initialQty = when {
                    prefillUnits != null && !isCaseBasedProduct -> prefillUnits
                    product.qty > 0 -> product.qty.toDouble()
                    else -> product.weightPerUnit?.takeIf { it > 0 } ?: 1.0
                }
                pendingFinalizeIndex = index
                finalizeItemLauncher.launch(Intent(this@PreOrderDetailActivity, ProductDetailActivity::class.java).apply {
                    putExtra("BARCODE", draft.barcode)
                    putExtra("PRODUCT_NAME", product.name)
                    putExtra("SHORT_NAME", product.shortName)
                    putExtra("PRODUCT_PRICE", product.price)
                    putExtra("QUANTITY", initialQty)
                    putExtra("STOCK", product.stock)
                    putExtra("CUSTOMER_ID", po.customerId)
                    putExtra("CUSTOMER_NAME", po.customerName)
                    putExtra("UNIT", product.unit)
                    putExtra("CASE_QTY", product.caseQty ?: 0)
                    if (prefillUnits != null && isCaseBasedProduct) {
                        putExtra("PREFILL_UNITS", prefillUnits)
                    }
                    putExtra(ProductDetailActivity.PRE_ORDER_MODE, true)
                })
            } catch (e: Exception) {
                showError(e.localizedMessage ?: getString(R.string.error_no_connection))
            }
        }
    }

    // Diálogo de confirmación rápida sobre una cantidad ya capturada en
    // CreatePreOrderActivity. "Confirmar" finaliza sin reabrir el stepper (solo
    // re-consulta el precio fresco); "Cambiar" reabre el stepper de siempre,
    // precargado con la cantidad guardada como punto de partida.
    private fun showConfirmSavedQuantityDialog(index: Int, draft: PreOrderItem) {
        val unitLabel = draft.unit?.let { if (it.isBlank() || it == "Lbs") "lb" else it } ?: "lb"
        val qtyStr = String.format(Locale.US, "%.2f %s", draft.quantity ?: 0.0, unitLabel)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_confirm_saved_quantity))
            .setMessage(getString(R.string.msg_confirm_saved_quantity, draft.productName, qtyStr))
            .setPositiveButton(getString(R.string.btn_confirm_quantity)) { _, _ -> quickFinalizeItem(index, draft) }
            .setNegativeButton(getString(R.string.btn_change_quantity)) { _, _ -> finalizeItem(index, prefillUnits = draft.quantity) }
            .show()
    }

    // "Confirmar" — no reabre el stepper: solo re-consulta el precio fresco del
    // catálogo (mismo fetch que finalizeItem()) y recalcula el total con la
    // cantidad/unit/caseQty YA guardados en el draft desde CreatePreOrderActivity.
    private fun quickFinalizeItem(index: Int, draft: PreOrderItem) {
        val quantity = draft.quantity
        if (draft.barcode.isBlank() || draft.barcode == "unknown" || quantity == null) {
            showError(getString(R.string.error_no_barcode_preorder))
            return
        }
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getProductByBarcode(draft.barcode)
                val product = if (resp.isSuccessful) resp.body()?.data else null
                if (product == null) {
                    showError(getString(R.string.error_product_not_found_barcode, draft.barcode))
                    return@launch
                }
                val freshPrice = product.price
                val finalized = PreOrderItem(
                    barcode = draft.barcode,
                    productName = product.name,
                    price = freshPrice,
                    quantity = quantity,
                    total = freshPrice * quantity,
                    unit = draft.unit ?: product.unit,
                    caseQty = draft.caseQty ?: product.caseQty,
                    shortName = product.shortName ?: draft.shortName
                )
                finalizedByIndex[index] = listOf(finalized)
                renderItemsSection()
            } catch (e: Exception) {
                showError(e.localizedMessage ?: getString(R.string.error_no_connection))
            }
        }
    }

    // ── Conversion flow (identical to CurrentOrderActivity) ──────────────────

    private fun startConversionFlow() {
        val po = currentPreOrder ?: return
        if (draftItems.isEmpty() || finalizedByIndex.size < draftItems.size) {
            showError(getString(R.string.error_finish_detailing_items))
            return
        }
        signatureLauncher.launch(Intent(this, SignatureActivity::class.java).apply {
            putExtra("customer_name", po.customerName)
        })
    }

    private fun askDamagedItems() {
        currentPreOrder ?: return
        val items = finalizedItemsFlat
        if (items.isEmpty()) { pendingApplyCredit = null; askApplyCredit(); return }

        val density = resources.displayMetrics.density
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (4 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.msg_damaged_items_hint)
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() }
        })
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
            val unitLabel = if (item.unit.isNullOrBlank() || item.unit == "Lbs") "lb" else item.unit
            val tvDetail = TextView(this).apply {
                text = String.format(Locale.US, "%.2f %s · \$%.2f/%s", item.quantity ?: 0.0, unitLabel, item.price ?: 0.0, unitLabel)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val isLbs = com.example.test.data.isLbsUnit(item.unit)
            val etQty = EditText(this).apply {
                inputType = if (isLbs)
                    android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                else
                    android.text.InputType.TYPE_CLASS_NUMBER
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

        val maxH = (resources.displayMetrics.heightPixels * 0.38).toInt()
        val wrapper = android.widget.FrameLayout(this)
        wrapper.addView(scroll, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, maxH
        ))

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_damaged_items))
            .setView(wrapper)
            .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                pendingDamageItems = inputs.mapNotNull { (item, et) ->
                    val qty = et.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                    if (qty > 0) DamageItem(barcode = item.barcode, productName = item.productName, qty = qty, unit = item.unit)
                    else null
                }
                pendingApplyCredit = null
                askApplyCredit()
            }
            .setNegativeButton(getString(R.string.btn_none)) { _, _ ->
                pendingDamageItems = emptyList()
                pendingApplyCredit = null
                askApplyCredit()
            }
            .show()
    }

    private fun askApplyCredit() {
        val po = currentPreOrder ?: return
        if (po.customerId.isNullOrBlank()) { checkPrinterThenConvert(); return }
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getCustomerCreditBalance(po.customerId)
                if (resp.isSuccessful) {
                    val balance = resp.body()!!
                    if (balance.balance > 0) {
                        val total = finalizedItemsFlat.sumOf { it.total ?: 0.0 }
                        val maxApply = minOf(balance.balance, total)
                        val msg = getString(R.string.msg_credit_apply,
                            String.format(Locale.US, "%.2f", balance.balance),
                            String.format(Locale.US, "%.2f", maxApply))
                        val etAmount = android.widget.EditText(this@PreOrderDetailActivity).apply {
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                            hint = getString(R.string.hint_credit_amount)
                            setText(String.format(Locale.US, "%.2f", maxApply))
                            selectAll()
                        }
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@PreOrderDetailActivity)
                            .setTitle(getString(R.string.title_credit_available))
                            .setMessage(msg)
                            .setView(etAmount)
                            .setCancelable(false)
                            .setPositiveButton(getString(R.string.btn_apply)) { _, _ ->
                                val entered = etAmount.text.toString().toDoubleOrNull() ?: 0.0
                                val clamped = entered.coerceIn(0.0, maxApply)
                                pendingApplyCredit = if (clamped > 0) clamped else null
                                checkPrinterThenConvert()
                            }
                            .setNegativeButton(getString(R.string.btn_no_apply_credit)) { _, _ ->
                                pendingApplyCredit = null
                                checkPrinterThenConvert()
                            }
                            .show()
                        return@launch
                    }
                }
            } catch (_: Exception) { }
            checkPrinterThenConvert()
        }
    }

    // Obligatorio (setCancelable(false), sin "Omitir") — mismo criterio que
    // CurrentOrderActivity desde la Fase 76/77. Corre DESPUÉS de convertir (Fase 88 —
    // doble impresión), no antes: necesitamos el batchId real de la conversión para
    // poder adjuntarle el pago después.
    private fun askPaymentMethod(skipPrint: Boolean) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_payment_method))
            .setMessage(getString(R.string.msg_payment_method))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.btn_cash)) { _, _ ->
                pendingPaymentMethod = "Cash"
                pendingCheckNumber = null
                sendPaymentAndPrint(skipPrint)
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
                        sendPaymentAndPrint(skipPrint)
                    }
                    .setNegativeButton(getString(R.string.btn_cancel)) { _, _ ->
                        askPaymentMethod(skipPrint)
                    }
                    .show()
            }
            .setNegativeButton(getString(R.string.btn_account)) { _, _ ->
                pendingPaymentMethod = "On Account"
                pendingCheckNumber = null
                sendPaymentAndPrint(skipPrint)
            }
            .show()
    }

    private fun checkPrinterThenConvert() {
        val printerAddress = securePrefs.getPrinterAddress()
        val printerName    = securePrefs.getPrinterName() ?: getString(R.string.btn_select_printer)

        if (printerAddress.isNullOrBlank()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.title_no_printer))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(getString(R.string.msg_no_printer))
                .setPositiveButton(getString(R.string.btn_continue_no_print)) { _, _ -> doConvertAndPrintFirst(skipPrint = true) }
                .setNeutralButton(getString(R.string.btn_go_to_settings)) { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        } else {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.title_confirm_print))
                .setMessage(getString(R.string.msg_confirm_print, printerName))
                .setPositiveButton(getString(R.string.btn_finalize_and_print))    { _, _ -> doConvertAndPrintFirst(skipPrint = false) }
                .setNeutralButton(getString(R.string.btn_finalize_no_print))   { _, _ -> doConvertAndPrintFirst(skipPrint = true) }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    // Fase 88 — manda la conversión (con firma/dañados/crédito ya resueltos, pero
    // payment_method/check_number todavía null) para tener el invoice real del
    // ticket #1; guarda la respuesta en sentConversion y recién ahí pregunta el
    // método de pago. Mismo patrón que CurrentOrderActivity.printFirstTicketThenAskPayment().
    private fun doConvertAndPrintFirst(skipPrint: Boolean) {
        val po = currentPreOrder ?: return
        val itemsToConvert = finalizedItemsFlat

        layoutLoading.visibility = View.VISIBLE
        tvLoadingTitle.text    = getString(R.string.loading_converting_pre_order)
        tvLoadingSubtitle.text = getString(R.string.loading_client, po.customerName)
        btnConvert.isEnabled   = false
        btnCancel.isEnabled    = false

        val sigForPrinting    = pendingSignature
        val damageForPrinting = pendingDamageItems
        val creditForPrinting = pendingApplyCredit

        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().convertPreOrder(
                    id = preOrderId,
                    request = ConvertPreOrderRequest(
                        signature     = sigForPrinting,
                        paymentMethod = null,
                        damageItems   = damageForPrinting.takeIf { it.isNotEmpty() },
                        checkNumber   = null,
                        applyCredit   = creditForPrinting,
                        items         = itemsToConvert
                    )
                )

                if (resp.isSuccessful) {
                    val body = resp.body()!!

                    tvLoadingTitle.text    = getString(R.string.loading_generating_invoice)
                    tvLoadingSubtitle.text = getString(R.string.loading_invoice_qb, body.invoiceId ?: "—")

                    val batchItems = itemsToConvert.map { item ->
                        BatchItem(
                            barcode     = item.barcode,
                            productName = item.productName,
                            price       = item.price ?: 0.0,
                            quantity    = item.quantity ?: 0.0,
                            total       = item.total ?: 0.0,
                            unit        = item.unit,
                            caseQty     = item.caseQty,
                            shortName   = item.shortName
                        )
                    }

                    sentConversion = SentConversion(
                        response      = body,
                        items         = batchItems,
                        signature     = sigForPrinting,
                        damageItems   = damageForPrinting,
                        creditApplied = creditForPrinting
                    )

                    val printerAddress = securePrefs.getPrinterAddress()
                    if (!skipPrint && !printerAddress.isNullOrBlank()) {
                        tvLoadingTitle.text    = getString(R.string.loading_printing_ticket)
                        tvLoadingSubtitle.text = getString(R.string.loading_connecting_printer)
                        // Primer ticket — ya con el invoice real, sin Payment: todavía.
                        val printResult = PrintService.printTicket(
                            context         = this@PreOrderDetailActivity,
                            deviceAddress   = printerAddress,
                            items           = batchItems,
                            customerName    = po.customerName,
                            batchId         = body.batchId,
                            invoiceId       = body.invoiceId,
                            invoiceNumber   = body.invoiceNumber,
                            customerAddress = resolvedCustomerAddress,
                            damageItems     = damageForPrinting,
                            paymentMethod   = null,
                            signature       = sigForPrinting,
                            checkNumber     = null,
                            creditApplied   = creditForPrinting
                        )
                        printResult.onFailure { e ->
                            Snackbar.make(
                                findViewById(android.R.id.content),
                                getString(R.string.error_print_after_send, e.localizedMessage ?: getString(R.string.error_no_connection)),
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }

                    layoutLoading.visibility = View.GONE
                    askPaymentMethod(skipPrint)
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

    // Fase 88 — la conversión ya se mandó (doConvertAndPrintFirst); acá solo se
    // adjunta el payment_method/check_number recién elegido, vía el mismo endpoint
    // genérico que usan los pedidos normales (updateBatchPayment — convertPreOrder
    // ya insertó en `orders` con el mismo esquema de batch_id), y se imprime el
    // segundo ticket con el mismo invoice + Payment:.
    private fun sendPaymentAndPrint(skipPrint: Boolean) {
        val po = currentPreOrder ?: return
        val sent = sentConversion ?: return
        val paymentForPrinting = pendingPaymentMethod
        val checkForPrinting   = pendingCheckNumber

        pendingSignature     = null
        pendingDamageItems   = emptyList()
        pendingPaymentMethod = null
        pendingCheckNumber   = null
        pendingApplyCredit   = null
        sentConversion = null

        lifecycleScope.launch {
            layoutLoading.visibility = View.VISIBLE
            tvLoadingTitle.text    = getString(R.string.loading_saving_payment)
            tvLoadingSubtitle.text = ""

            try {
                RetrofitClient.getApi().updateBatchPayment(
                    sent.response.batchId, UpdatePaymentRequest(paymentForPrinting, checkForPrinting)
                )
            } catch (_: Exception) { }

            val printerAddress = securePrefs.getPrinterAddress()
            if (!skipPrint && !printerAddress.isNullOrBlank()) {
                tvLoadingTitle.text    = getString(R.string.loading_printing_ticket)
                tvLoadingSubtitle.text = getString(R.string.loading_connecting_printer)
                val printResult = PrintService.printTicket(
                    context         = this@PreOrderDetailActivity,
                    deviceAddress   = printerAddress,
                    items           = sent.items,
                    customerName    = po.customerName,
                    batchId         = sent.response.batchId,
                    invoiceId       = sent.response.invoiceId,
                    invoiceNumber   = sent.response.invoiceNumber,
                    customerAddress = resolvedCustomerAddress,
                    damageItems     = sent.damageItems,
                    paymentMethod   = paymentForPrinting,
                    signature       = sent.signature,
                    checkNumber     = checkForPrinting,
                    creditApplied   = sent.creditApplied
                )
                printResult.onFailure { e ->
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        getString(R.string.error_print_after_send, e.localizedMessage ?: getString(R.string.error_no_connection)),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }

            layoutLoading.visibility = View.GONE
            val grandTotal = sent.items.sumOf { it.total }
            startActivity(
                Intent(this@PreOrderDetailActivity, OrderSuccessActivity::class.java).apply {
                    putExtra("batch_id",          sent.response.batchId)
                    putExtra("invoice_id",        sent.response.invoiceId ?: "")
                    putExtra("invoice_number",    sent.response.invoiceNumber ?: 0)
                    putExtra("customer_name",     po.customerName)
                    putExtra("customer_address",  resolvedCustomerAddress)
                    putExtra("signature",         sent.signature)
                    putExtra("damage_items_json", Gson().toJson(sent.damageItems))
                    putExtra("total",             grandTotal)
                    putExtra("item_count",        sent.items.size)
                    putExtra("credits_total",     sent.response.creditsTotal ?: -1.0)
                    putExtra("credit_applied",    sent.response.creditApplied ?: sent.creditApplied ?: 0.0)
                    putExtra("orders_json",       Gson().toJson(
                        sent.items.map { bi ->
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
                                unit         = bi.unit,
                                caseQty      = bi.caseQty,
                                shortName    = bi.shortName
                            )
                        }
                    ))
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun confirmCancel() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_cancel_pre_order))
            .setMessage(getString(R.string.msg_cancel_pre_order))
            .setPositiveButton(getString(R.string.btn_yes_cancel)) { _, _ -> cancelPreOrder() }
            .setNegativeButton(getString(R.string.btn_no), null)
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
            showError(getString(R.string.error_no_items_reuse))
            return
        }
        btnReuse.isEnabled = false
        btnReuse.text = getString(R.string.btn_creating)

        lifecycleScope.launch {
            try {
                // Reusar una pre-orden CONVERTED no debe arrastrar el precio/detalle ya
                // finalizado — la nueva pre-orden también debe pasar por su propio
                // finalize-at-conversion el día que le toque, no heredar precios viejos.
                val draftItemsForReuse = po.items.map {
                    PreOrderItem(barcode = it.barcode, productName = it.productName)
                }
                val request = com.example.test.data.PreOrderRequest(
                    customerId    = po.customerId,
                    customerName  = po.customerName,
                    scheduledDate = null,
                    notes         = po.notes,
                    items         = draftItemsForReuse
                )
                val resp = RetrofitClient.getApi().createPreOrder(request)
                if (resp.isSuccessful) {
                    val newId = resp.body()?.id ?: 0
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        getString(R.string.success_new_pre_order_created),
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
                btnReuse.text = getString(R.string.btn_reuse_pre_order)
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

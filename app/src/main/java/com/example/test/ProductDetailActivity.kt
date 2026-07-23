package com.example.test

import android.os.Bundle
import android.view.View
import com.google.android.material.appbar.MaterialToolbar
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import androidx.activity.enableEdgeToEdge

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.PreOrderItem
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.repository.OrderRepository
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import java.util.Locale

class ProductDetailActivity : BaseActivity() {

    companion object {
        private const val KEY_BARCODE = "BARCODE"
        private const val KEY_NAME = "PRODUCT_NAME"
        private const val KEY_PRICE = "PRODUCT_PRICE"
        private const val KEY_QUANTITY = "QUANTITY"
        private const val KEY_CUSTOMER_ID = "CUSTOMER_ID"
        private const val KEY_CUSTOMER_NAME = "CUSTOMER_NAME"
        private const val KEY_UNIT = "UNIT"
        const val PRE_ORDER_MODE = "pre_order_mode"
        const val RESULT_ITEMS_JSON = "items_json"
    }

    private lateinit var tvBarcode: TextView
    private lateinit var tvProductName: TextView
    private lateinit var tvPrice: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvUnits: TextView
    private lateinit var tvTotalWeight: TextView
    private lateinit var tvMinPrice: TextView
    private lateinit var tvStock: TextView
    private lateinit var layoutWeights: LinearLayout
    private lateinit var layoutHistory: LinearLayout
    private lateinit var cardWeights: View
    private lateinit var cardHistory: MaterialCardView
    private lateinit var btnUnitMinus: MaterialButton
    private lateinit var btnUnitPlus: MaterialButton
    private lateinit var btnAddOrder: MaterialButton
    private lateinit var btnViewHistory: MaterialButton

    private var barcode = ""
    private var productName = ""
    private var productPrice = 0.0
    private var defaultWeight = 1.0
    private var baseTotal = 0.0
    private var units = 1
    private val weights = mutableListOf<Double>()
    private var pricePerLb = 0.0
    private var minTotal: Double? = null
    private var customerId: String? = null
    private var productUnit: String? = null
    private var caseQty: Int? = null
    private var isPreOrderMode = false
    private lateinit var orderRepository: OrderRepository

    private val isCaseBased: Boolean
        get() = productUnit.equals("Case", true) && (caseQty ?: 0) > 0

    private val isWeightBased: Boolean
        get() = !isCaseBased && (productUnit == null || productUnit == "" || productUnit == "Lbs")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_product_detail)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        barcode = intent.getStringExtra(KEY_BARCODE) ?: ""
        productName = intent.getStringExtra(KEY_NAME) ?: getString(R.string.label_unknown_product)
        productPrice = intent.getDoubleExtra(KEY_PRICE, 0.0)
        defaultWeight = intent.getDoubleExtra(KEY_QUANTITY, 1.0).coerceAtLeast(0.1)
        customerId = intent.getStringExtra(KEY_CUSTOMER_ID)
        productUnit = intent.getStringExtra(KEY_UNIT)
        caseQty = intent.getIntExtra("CASE_QTY", 0).takeIf { it > 0 }
            ?: (if (productUnit.equals("Case", true)) defaultWeight.toInt().coerceAtLeast(1) else null)
        isPreOrderMode = intent.getBooleanExtra(PRE_ORDER_MODE, false)
        baseTotal = productPrice
        if (isCaseBased) {
            val cq = caseQty
            if (cq != null) {
                baseTotal = productPrice * cq
                pricePerLb = baseTotal
            }
        }

        val db = AppDatabase.getInstance(this)
        val securePrefs = SecurePreferences(this)
        orderRepository = OrderRepository(db, securePrefs)

        initViews()
        when {
            isCaseBased -> resetCount()
            isWeightBased -> resetWeights()
            else -> resetCount()
        }
        showProduct()
        if (customerId != null) loadPriceHistory()

        val stock = intent.getIntExtra("STOCK", -1)
        if (stock >= 0) {
            tvStock.visibility = View.VISIBLE
            if (stock == 0) {
                tvStock.text = getString(R.string.label_no_stock)
                tvStock.setTextColor(resources.getColor(R.color.red, theme))
                btnAddOrder.text = getString(R.string.btn_no_stock)
                btnAddOrder.isEnabled = false
                btnAddOrder.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(resources.getColor(R.color.red, theme))
                btnAddOrder.setTextColor(resources.getColor(android.R.color.white, theme))
                btnUnitMinus.isEnabled = false
                btnUnitPlus.isEnabled = false
            } else {
                tvStock.text = getString(R.string.label_stock_available, stock)
                tvStock.setTextColor(resources.getColor(R.color.success, theme))
            }
        }
    }

    private fun initViews() {
        tvBarcode = findViewById(R.id.tvBarcode)
        tvProductName = findViewById(R.id.tvProductName)
        tvPrice = findViewById(R.id.tvPrice)
        tvTotal = findViewById(R.id.tvTotal)
        tvUnits = findViewById(R.id.tvUnits)
        tvTotalWeight = findViewById(R.id.tvTotalWeight)
        tvMinPrice = findViewById(R.id.tvMinPrice)
        tvStock = findViewById(R.id.tvStock)
        layoutWeights = findViewById(R.id.layoutWeights)
        layoutHistory = findViewById(R.id.layoutHistory)
        cardWeights = findViewById(R.id.cardWeights)
        cardHistory = findViewById(R.id.cardHistory)
        btnUnitMinus = findViewById(R.id.btnUnitMinus)
        btnUnitPlus = findViewById(R.id.btnUnitPlus)
        btnAddOrder = findViewById(R.id.btnAddOrder)
        btnViewHistory = findViewById(R.id.btnViewHistory)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        btnUnitMinus.setOnClickListener {
            if (units > 1) {
                units--
                if (isWeightBased) {
                    weights.removeLastOrNull()
                    rebuildWeightRows()
                }
                recalcTotal()
            }
        }

        btnUnitPlus.setOnClickListener {
            if (units < 50) {
                units++
                if (isWeightBased) {
                    weights.add(defaultWeight)
                    rebuildWeightRows()
                }
                recalcTotal()
            }
        }

        tvPrice.setOnClickListener { showPriceEditDialog() }

        btnAddOrder.setOnClickListener { saveOrder() }

        btnViewHistory.setOnClickListener {
            startActivity(android.content.Intent(this, HistoryActivity::class.java))
        }
    }

    private fun resetWeights() {
        units = 1
        weights.clear()
        weights.add(defaultWeight)
        rebuildWeightRows()
        recalcTotal()
    }

    private fun resetCount() {
        units = if (isCaseBased) 1 else defaultWeight.toInt().coerceAtLeast(1)
        cardWeights.visibility = View.GONE
        recalcTotal()
    }

    private fun rebuildWeightRows() {
        layoutWeights.removeAllViews()

        if (weights.isEmpty()) {
            cardWeights.visibility = View.GONE
            return
        }
        cardWeights.visibility = View.VISIBLE

        for (i in weights.indices) {
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (i > 0) 8 else 0 }
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                text = getString(R.string.label_unit_number, i + 1)
                textSize = 14f
                setTextColor(resources.getColor(R.color.text_primary, theme))
            }

            val btnMinus = MaterialButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp).apply { marginEnd = 8.dp }
                cornerRadius = 22
                setIconResource(R.drawable.ic_remove)
                iconSize = 20
                tag = i
                setOnClickListener { v ->
                    val idx = v.tag as Int
                    val newW = Math.round((weights[idx] - 0.1) * 100.0) / 100.0
                    if (newW >= 0.1) {
                        weights[idx] = newW
                        rebuildWeightRows()
                        recalcTotal()
                    }
                }
            }

            val tvW = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    width = 64.dp
                    gravity = android.view.Gravity.CENTER
                }
                text = String.format(Locale.US, "%.2f", weights[i])
                textSize = 18f
                setTextColor(resources.getColor(R.color.text_primary, theme))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                tag = i
                setOnClickListener { v ->
                    val idx = v.tag as Int
                    showWeightDialog(idx)
                }
            }

            val btnPlus = MaterialButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp)
                cornerRadius = 22
                setIconResource(R.drawable.ic_add)
                iconSize = 20
                tag = i
                setOnClickListener { v ->
                    val idx = v.tag as Int
                    weights[idx] = Math.round((weights[idx] + 0.1) * 100.0) / 100.0
                    rebuildWeightRows()
                    recalcTotal()
                }
            }

            row.addView(label)
            row.addView(btnMinus)
            row.addView(tvW)
            row.addView(btnPlus)
            layoutWeights.addView(row)
        }
    }

    private fun showWeightDialog(index: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_weight, null)
        val input = dialogView.findViewById<android.widget.EditText>(R.id.etWeight)
        input.setText(String.format(Locale.US, "%.2f", weights[index]))
        input.selectAll()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_unit_weight, index + 1))
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                val qty = input.text.toString().toDoubleOrNull()
                if (qty != null && qty > 0) {
                    weights[index] = Math.round(qty * 100.0) / 100.0
                    rebuildWeightRows()
                    recalcTotal()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showProduct() {
        pricePerLb = productPrice
        if (isCaseBased) {
            val cq = caseQty
            if (cq != null) {
                pricePerLb = productPrice * cq
                baseTotal = pricePerLb
            }
        }
        tvBarcode.text = barcode
        tvProductName.text = productName
        when {
            isCaseBased -> {
                val cq = caseQty ?: 0
                tvPrice.text = if (cq > 0)
                    String.format(Locale.US, "$%.2f / Case de %d ($%.2f/unit)", baseTotal, cq, productPrice)
                else
                    String.format(Locale.US, "$%.2f / Case", baseTotal)
            }
            isWeightBased -> tvPrice.text = String.format(Locale.US, "$%.2f", baseTotal)
            else -> tvPrice.text = String.format(Locale.US, "$%.2f / %s", baseTotal, productUnit ?: "Unit")
        }
        tvUnits.text = units.toString()
        when {
            isCaseBased -> tvTotal.text = String.format(Locale.US, "$%.2f", pricePerLb * units)
            isWeightBased -> tvTotal.text = String.format(Locale.US, "$%.2f", pricePerLb * defaultWeight)
            else -> tvTotal.text = String.format(Locale.US, "$%.2f", pricePerLb * units)
        }
        recalcTotal()
    }

    private fun recalcTotal() {
        tvUnits.text = units.toString()
        when {
            isCaseBased -> {
                val total = units * pricePerLb
                val cq = caseQty ?: 0
                tvTotalWeight.text = if (cq > 0) {
                    val totalUnits = units * cq
                    val unitPrice = productPrice
                    val cases = if (units > 1) "$units cases" else "1 case"
                    "$cases × $cq = $totalUnits units · ${String.format(Locale.US, "$%.2f", unitPrice)}/unit"
                } else {
                    "$units Case"
                }
                tvTotal.text = String.format(Locale.US, "$%.2f", total)
            }
            isWeightBased -> {
                val totalWeight = weights.sum()
                val total = totalWeight * pricePerLb
                tvTotalWeight.text = if (units > 1) {
                    val parts = weights.joinToString(" + ") { String.format(Locale.US, "%.2f", it) }
                    "$parts = ${String.format(Locale.US, "%.2f", totalWeight)} lb"
                } else {
                    String.format(Locale.US, getString(R.string.label_weight_display), totalWeight)
                }
                tvTotal.text = String.format(Locale.US, "$%.2f", total)
            }
            else -> {
                val total = units * pricePerLb
                val unitLabel = productUnit ?: "Unit"
                tvTotalWeight.text = "$units $unitLabel"
                tvTotal.text = String.format(Locale.US, "$%.2f", total)
            }
        }
    }

    private fun showPriceEditDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_price, null)
        val etPrice = dialogView.findViewById<android.widget.EditText>(R.id.etPrice)
        etPrice.setText(String.format(Locale.US, "%.2f", baseTotal))
        etPrice.selectAll()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_edit_price))
            .setMessage(if (minTotal != null) getString(R.string.error_min_price, String.format(Locale.US, "%.2f", minTotal)) else "")
            .setView(dialogView)
            .setPositiveButton(getString(R.string.btn_apply)) { _, _ ->
                val newTotal = etPrice.text.toString().toDoubleOrNull()
                if (newTotal != null && newTotal > 0) {
                    if (minTotal != null && Math.round(newTotal * 100) < Math.round(minTotal!! * 100)) {
                        Snackbar.make(
                            findViewById(android.R.id.content),
                            getString(R.string.error_min_price, String.format(Locale.US, "%.2f", minTotal)),
                            Snackbar.LENGTH_LONG
                        ).show()
                        return@setPositiveButton
                    }
                    baseTotal = newTotal
                    pricePerLb = baseTotal
                    when {
                        isCaseBased -> tvPrice.text = String.format(Locale.US, "$%.2f / Case", baseTotal)
                        isWeightBased -> tvPrice.text = String.format(Locale.US, "$%.2f", baseTotal)
                        else -> tvPrice.text = String.format(Locale.US, "$%.2f / %s", baseTotal, productUnit ?: "Unit")
                    }
                    recalcTotal()
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_invalid_price), Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun loadPriceHistory() {
        lifecycleScope.launch {
            val result = orderRepository.getProductPriceHistory(barcode, customerId!!)
            result.onSuccess { response ->
                val rawMin = response.product?.minPrice
                if (rawMin != null) {
                    minTotal = rawMin
                    tvMinPrice.text = getString(R.string.label_min_price_display, String.format(Locale.US, "%.2f", minTotal))
                    tvMinPrice.visibility = View.VISIBLE
                }

                if (response.history.isNotEmpty()) {
                    layoutHistory.removeAllViews()
                    for ((i, item) in response.history.withIndex()) {
                        val row = LinearLayout(this@ProductDetailActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = if (i > 0) 8 else 0 }
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }

                        val dot = TextView(this@ProductDetailActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(10.dp, 10.dp).apply { marginEnd = 10.dp }
                            setBackgroundResource(R.drawable.circle_green)
                        }
                        row.addView(dot)

                        val info = LinearLayout(this@ProductDetailActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            orientation = LinearLayout.VERTICAL
                        }

                        val unitLabel = productUnit ?: "lb"
                        val tvPriceLine = TextView(this@ProductDetailActivity).apply {
                            text = String.format(Locale.US, "%.2f %s  =  \$%.2f", item.quantity, unitLabel, item.price * item.quantity)
                            textSize = 13f
                            setTextColor(resources.getColor(R.color.text_primary, theme))
                        }
                        info.addView(tvPriceLine)

                        val dateStr = item.date?.let {
                            try {
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                                val d = sdf.parse(it.substringBefore("."))
                                java.text.SimpleDateFormat("MM/dd/yy HH:mm", Locale.getDefault()).format(d!!)
                            } catch (_: Exception) { it.substringBefore("T") }
                        } ?: ""
                        val tvDate = TextView(this@ProductDetailActivity).apply {
                            text = if (item.invoiceId != null) "$dateStr  •  Factura #${item.invoiceId}" else dateStr
                            textSize = 11f
                            setTextColor(resources.getColor(R.color.text_secondary, theme))
                        }
                        info.addView(tvDate)
                        row.addView(info)
                        layoutHistory.addView(row)
                    }
                    cardHistory.visibility = View.VISIBLE
                }
            }
            result.onFailure {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.error_load_price_history),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun saveOrder() {
        btnAddOrder.isEnabled = false
        if (isPreOrderMode) {
            val items = mutableListOf<PreOrderItem>()
            when {
                isCaseBased -> items.add(PreOrderItem(
                    barcode = barcode,
                    productName = productName,
                    price = pricePerLb,
                    quantity = units.toDouble(),
                    total = pricePerLb * units,
                    unit = productUnit
                ))
                isWeightBased -> for (weight in weights) {
                    items.add(PreOrderItem(
                        barcode = barcode,
                        productName = productName,
                        price = pricePerLb,
                        quantity = weight,
                        total = pricePerLb * weight,
                        unit = productUnit
                    ))
                }
                else -> items.add(PreOrderItem(
                    barcode = barcode,
                    productName = productName,
                    price = pricePerLb,
                    quantity = units.toDouble(),
                    total = pricePerLb * units,
                    unit = productUnit
                ))
            }
            val resultIntent = android.content.Intent().apply {
                putExtra(RESULT_ITEMS_JSON, Gson().toJson(items))
            }
            setResult(RESULT_OK, resultIntent)
            finish()
            return
        }
        when {
            isCaseBased -> orderRepository.savePendingOrder(
                barcode = barcode,
                productName = productName,
                price = pricePerLb,
                quantity = units.toDouble(),
                unit = productUnit
            )
            isWeightBased -> for (weight in weights) {
                orderRepository.savePendingOrder(
                    barcode = barcode,
                    productName = productName,
                    price = pricePerLb,
                    quantity = weight,
                    unit = productUnit
                )
            }
            else -> orderRepository.savePendingOrder(
                barcode = barcode,
                productName = productName,
                price = pricePerLb,
                quantity = units.toDouble(),
                unit = productUnit
            )
        }
        btnAddOrder.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        finish()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

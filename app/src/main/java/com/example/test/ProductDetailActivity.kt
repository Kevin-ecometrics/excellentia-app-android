package com.example.test

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.repository.OrderRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import java.util.Locale

class ProductDetailActivity : AppCompatActivity() {

    companion object {
        private const val KEY_BARCODE = "BARCODE"
        private const val KEY_NAME = "PRODUCT_NAME"
        private const val KEY_PRICE = "PRODUCT_PRICE"
        private const val KEY_QUANTITY = "QUANTITY"
        private const val KEY_CUSTOMER_ID = "CUSTOMER_ID"
        private const val KEY_CUSTOMER_NAME = "CUSTOMER_NAME"
    }

    private lateinit var tvBarcode: TextView
    private lateinit var tvProductName: TextView
    private lateinit var tvPrice: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvUnits: TextView
    private lateinit var tvTotalWeight: TextView
    private lateinit var tvMinPrice: TextView
    private lateinit var layoutWeights: LinearLayout
    private lateinit var layoutHistory: LinearLayout
    private lateinit var cardWeights: View
    private lateinit var cardHistory: MaterialCardView
    private lateinit var btnBack: ImageButton
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
    private lateinit var orderRepository: OrderRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_product_detail)
        val pad = resources.getDimensionPixelSize(R.dimen.padding_screen)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad)
            insets
        }

        barcode = intent.getStringExtra(KEY_BARCODE) ?: ""
        productName = intent.getStringExtra(KEY_NAME) ?: "Producto Desconocido"
        productPrice = intent.getDoubleExtra(KEY_PRICE, 0.0)
        defaultWeight = intent.getDoubleExtra(KEY_QUANTITY, 1.0).coerceAtLeast(0.1)
        customerId = intent.getStringExtra(KEY_CUSTOMER_ID)
        baseTotal = productPrice

        val db = AppDatabase.getInstance(this)
        val securePrefs = SecurePreferences(this)
        orderRepository = OrderRepository(db, securePrefs)

        initViews()
        resetWeights()
        showProduct()
        if (customerId != null) loadPriceHistory()
    }

    private fun initViews() {
        tvBarcode = findViewById(R.id.tvBarcode)
        tvProductName = findViewById(R.id.tvProductName)
        tvPrice = findViewById(R.id.tvPrice)
        tvTotal = findViewById(R.id.tvTotal)
        tvUnits = findViewById(R.id.tvUnits)
        tvTotalWeight = findViewById(R.id.tvTotalWeight)
        tvMinPrice = findViewById(R.id.tvMinPrice)
        layoutWeights = findViewById(R.id.layoutWeights)
        layoutHistory = findViewById(R.id.layoutHistory)
        cardWeights = findViewById(R.id.cardWeights)
        cardHistory = findViewById(R.id.cardHistory)
        btnBack = findViewById(R.id.btnBack)
        btnUnitMinus = findViewById(R.id.btnUnitMinus)
        btnUnitPlus = findViewById(R.id.btnUnitPlus)
        btnAddOrder = findViewById(R.id.btnAddOrder)
        btnViewHistory = findViewById(R.id.btnViewHistory)

        btnBack.setOnClickListener { finish() }

        btnUnitMinus.setOnClickListener {
            if (units > 1) {
                units--
                weights.removeLastOrNull()
                rebuildWeightRows()
                recalcTotal()
            }
        }

        btnUnitPlus.setOnClickListener {
            if (units < 50) {
                units++
                weights.add(defaultWeight)
                rebuildWeightRows()
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
                text = "Unidad ${i + 1}:"
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
        val margin = resources.getDimensionPixelSize(R.dimen.padding_screen)
        val input = android.widget.EditText(this).apply {
            setBackgroundResource(R.drawable.bg_edittext)
            setPadding(margin, 12, margin, 12)
            setText(String.format(Locale.US, "%.2f", weights[index]))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            selectAll()
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(margin, margin / 2, margin, margin / 2)
            addView(input, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Peso unidad ${index + 1}")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val qty = input.text.toString().toDoubleOrNull()
                if (qty != null && qty > 0) {
                    weights[index] = Math.round(qty * 100.0) / 100.0
                    rebuildWeightRows()
                    recalcTotal()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showProduct() {
        pricePerLb = productPrice / defaultWeight
        tvBarcode.text = barcode
        tvProductName.text = productName
        tvPrice.text = String.format(Locale.US, "$%.2f", baseTotal)
        tvUnits.text = units.toString()
        tvTotal.text = String.format(Locale.US, "$%.2f", pricePerLb * defaultWeight)
        recalcTotal()
    }

    private fun recalcTotal() {
        tvUnits.text = units.toString()
        val totalWeight = weights.sum()
        val total = totalWeight * pricePerLb
        tvTotalWeight.text = if (units > 1) {
            val parts = weights.joinToString(" + ") { String.format(Locale.US, "%.2f", it) }
            "$parts = ${String.format(Locale.US, "%.2f", totalWeight)} lb"
        } else {
            String.format(Locale.US, "Peso: %.2f lb", totalWeight)
        }
        tvTotal.text = String.format(Locale.US, "$%.2f", total)
    }

    private fun showPriceEditDialog() {
        val margin = resources.getDimensionPixelSize(R.dimen.padding_screen)
        val etPrice = android.widget.EditText(this).apply {
            setBackgroundResource(R.drawable.bg_edittext)
            setPadding(margin, 12, margin, 12)
            hint = "Precio total"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.2f", baseTotal))
            selectAll()
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(margin, margin / 2, margin, margin / 2)
            addView(etPrice, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Editar precio total")
            .setMessage(if (minTotal != null) "Mínimo: $${String.format(Locale.US, "%.2f", minTotal)}" else "")
            .setView(container)
            .setPositiveButton("Aplicar") { _, _ ->
                val newTotal = etPrice.text.toString().toDoubleOrNull()
                if (newTotal != null && newTotal > 0) {
                    if (minTotal != null && Math.round(newTotal * 100) < Math.round(minTotal!! * 100)) {
                        Toast.makeText(
                            this,
                            "El precio no puede ser menor a $${String.format(Locale.US, "%.2f", minTotal)}",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setPositiveButton
                    }
                    baseTotal = newTotal
                    pricePerLb = baseTotal / defaultWeight
                    tvPrice.text = String.format(Locale.US, "$%.2f", baseTotal)
                    recalcTotal()
                } else {
                    Toast.makeText(this, "Precio inválido", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadPriceHistory() {
        lifecycleScope.launch {
            try {
                val result = orderRepository.getProductPriceHistory(barcode, customerId!!)
                result.onSuccess { response ->
                    val rawMin = response.product?.minPrice
                    if (rawMin != null) {
                        minTotal = rawMin
                        tvMinPrice.text = "Precio mínimo: $${String.format(Locale.US, "%.2f", minTotal)}"
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

                            val tvPriceLine = TextView(this@ProductDetailActivity).apply {
                                text = String.format(Locale.US, "%.2f lb  =  \$%.2f", item.quantity, item.price * item.quantity)
                                textSize = 13f
                                setTextColor(resources.getColor(R.color.text_primary, theme))
                            }
                            info.addView(tvPriceLine)

                            val dateStr = item.date?.let {
                                try {
                                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                                    val d = sdf.parse(it.substringBefore("."))
                                    java.text.SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(d!!)
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
            } catch (_: Exception) {

            }
        }
    }

    private fun saveOrder() {
        btnAddOrder.isEnabled = false
        for (weight in weights) {
            orderRepository.savePendingOrder(
                barcode = barcode,
                productName = productName,
                price = pricePerLb,
                quantity = weight
            )
        }
        val msg = if (weights.size > 1) "✓ ${weights.size} unidades agregadas" else "✓ Agregado al pedido"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
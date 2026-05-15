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
import kotlinx.coroutines.launch
import java.util.Locale

class ProductDetailActivity : AppCompatActivity() {

    companion object {
        private const val KEY_BARCODE = "BARCODE"
        private const val KEY_NAME = "PRODUCT_NAME"
        private const val KEY_PRICE = "PRODUCT_PRICE"
        private const val KEY_QUANTITY = "QUANTITY"
    }

    private lateinit var tvBarcode: TextView
    private lateinit var tvProductName: TextView
    private lateinit var tvPrice: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvUnits: TextView
    private lateinit var tvTotalWeight: TextView
    private lateinit var layoutWeights: LinearLayout
    private lateinit var cardWeights: View
    private lateinit var btnBack: ImageButton
    private lateinit var btnUnitMinus: MaterialButton
    private lateinit var btnUnitPlus: MaterialButton
    private lateinit var btnAddOrder: MaterialButton
    private lateinit var btnViewHistory: MaterialButton

    private var barcode = ""
    private var productName = ""
    private var productPrice = 0.0
    private var defaultWeight = 1.0
    private var units = 1
    private val weights = mutableListOf<Double>()
    private var pricePerLb = 0.0
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

        val db = AppDatabase.getInstance(this)
        val securePrefs = SecurePreferences(this)
        orderRepository = OrderRepository(db, securePrefs)

        initViews()
        resetWeights()
        showProduct()
    }

    private fun initViews() {
        tvBarcode = findViewById(R.id.tvBarcode)
        tvProductName = findViewById(R.id.tvProductName)
        tvPrice = findViewById(R.id.tvPrice)
        tvTotal = findViewById(R.id.tvTotal)
        tvUnits = findViewById(R.id.tvUnits)
        tvTotalWeight = findViewById(R.id.tvTotalWeight)
        layoutWeights = findViewById(R.id.layoutWeights)
        cardWeights = findViewById(R.id.cardWeights)
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
        val input = android.widget.EditText(this).apply {
            setBackgroundResource(R.drawable.bg_edittext)
            setText(String.format(Locale.US, "%.2f", weights[index]))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            selectAll()
        }
        val margin = resources.getDimensionPixelSize(R.dimen.padding_screen)
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
        tvPrice.text = String.format(Locale.US, "$%.2f / lb", pricePerLb)
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

    private fun saveOrder() {
        btnAddOrder.isEnabled = false
        val totalWeight = weights.sum()
        val totalPrice = totalWeight * pricePerLb
        orderRepository.savePendingOrder(
            barcode = barcode,
            productName = productName,
            price = pricePerLb,
            quantity = totalWeight
        )
        Toast.makeText(this, "✓ Agregado al pedido", Toast.LENGTH_SHORT).show()
        finish()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

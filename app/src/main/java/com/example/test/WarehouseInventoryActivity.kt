package com.example.test

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.ProductDto
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

// Backlog cliente (2026-09-23) — pedido explícito: el almacén necesita
// poder consultar el stock disponible de los productos, mismo dato que ya
// ve el admin en `/products` (webapp), pero SOLO lectura — nada de crear ni
// editar productos desde acá (eso sigue siendo exclusivo de la webapp,
// mismo criterio que "la webapp no muta ventas" documentado para /orders).
// Reusa GET /api/products tal cual (ya expone name/unit/stock/sku/barcode/
// weight_per_unit) — no hizo falta ningún endpoint nuevo.
class WarehouseInventoryActivity : BaseActivity() {

    private lateinit var etSearch: EditText
    private lateinit var layoutProducts: LinearLayout
    private lateinit var tvNoResults: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var securePrefs: SecurePreferences

    private var allProducts: List<ProductDto> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_warehouse_inventory)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        etSearch        = findViewById(R.id.etSearch)
        layoutProducts  = findViewById(R.id.layoutProducts)
        tvNoResults     = findViewById(R.id.tvNoResults)
        progressBar     = findViewById(R.id.progressBar)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // Filtro local — ya se trajo el catálogo completo de una
                // sola vez (ver loadProducts), no hace falta golpear la red
                // en cada letra tipeada.
                renderProducts(s?.toString()?.trim() ?: "")
            }
        })

        loadProducts()
    }

    private fun loadProducts() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                // Mismo límite que ya usa OrderRepository para cachear el
                // catálogo completo (getAllProducts) — suficiente margen
                // para no paginar en una pantalla de solo consulta.
                val resp = RetrofitClient.getApi().getAllProducts(page = 1, limit = 1000)
                if (resp.isSuccessful) {
                    allProducts = (resp.body()?.data ?: emptyList()).sortedBy { it.name }
                    renderProducts(etSearch.text?.toString()?.trim() ?: "")
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun renderProducts(query: String) {
        val filtered = if (query.length < 2) allProducts else allProducts.filter {
            it.name.contains(query, ignoreCase = true) ||
                (it.sku?.contains(query, ignoreCase = true) == true) ||
                (it.barcode?.contains(query, ignoreCase = true) == true)
        }
        layoutProducts.removeAllViews()
        tvNoResults.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        for (product in filtered) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 6.dp
                }
                setCardBackgroundColor(getColor(R.color.surface))
                radius = 0f
                cardElevation = 1f
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14.dp, 10.dp, 14.dp, 10.dp)
            }
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val tvName = TextView(this).apply {
                text = product.name
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val stock = product.stock
            val stockColor = when {
                stock <= 0 -> R.color.red
                stock <= 5 -> R.color.amber_dark
                else -> R.color.success
            }
            val tvStock = TextView(this).apply {
                text = getString(R.string.wh_available_qty_badge, stock.toDouble())
                textSize = 10f
                setTextColor(getColor(stockColor))
                setBackgroundResource(R.drawable.bg_chip_sent)
                setPadding(8.dp, 3.dp, 8.dp, 3.dp)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            headerRow.addView(tvName)
            headerRow.addView(tvStock)
            row.addView(headerRow)

            // Type — columna pedida explícitamente (Producto / Type / Stock,
            // mismo criterio que la tabla de /products en la webapp).
            val tvType = TextView(this).apply {
                text = com.example.test.data.unitDisplayLabel(product.unit)
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 2.dp, 0, 0)
            }
            row.addView(tvType)

            // Backlog cliente (2026-09-23) — peso de referencia del catálogo
            // (columna "Weight/lb" de /products, webapp), pero ACOTADO a
            // Lbs — a diferencia de la webapp, que muestra la columna
            // siempre. Case/Unit/Bucket no usan este campo para nada, así
            // que no aporta mostrarlo ahí.
            val isLbs = com.example.test.data.isLbsUnit(product.unit)
            val expectedBoxWeight = product.weightPerUnit?.takeIf { it > 0 }
            if (isLbs && expectedBoxWeight != null) {
                row.addView(TextView(this).apply {
                    text = getString(R.string.wh_weight_per_unit, com.example.test.data.formatQty(expectedBoxWeight))
                    textSize = 11f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(0, 1.dp, 0, 0)
                })
            }

            // Columna extra que se ofreció agregar si convenía — "≈ N cajas"
            // para Lbs, mismo cálculo/criterio que ya se usa en /products
            // (webapp) y en Sub-inventario (Disponible): puramente
            // informativo, las cajas reales no siempre pesan el nominal del
            // catálogo.
            if (isLbs && expectedBoxWeight != null) {
                row.addView(TextView(this).apply {
                    text = getString(R.string.wh_approx_boxes, stock / expectedBoxWeight)
                    textSize = 10f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(0, 1.dp, 0, 0)
                })
            }

            card.addView(row)
            layoutProducts.addView(card)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

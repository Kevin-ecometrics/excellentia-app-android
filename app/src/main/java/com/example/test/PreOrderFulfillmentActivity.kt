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
import com.example.test.data.FulfillmentLineDto
import com.example.test.data.FulfillmentReportDto
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

// Pre-órdenes vs inventario disponible (2026-11-19, pedido del cliente: el reporte
// también "en la app"). Solo lectura, para el almacén: lo que piden las
// pre-órdenes confirmadas de hoy a 7 días contra lo que hay, por producto — mismo
// endpoint que /warehouse/preorders en la webapp. Reusa el layout de
// WarehouseInventoryActivity (toolbar + búsqueda + lista).
class PreOrderFulfillmentActivity : BaseActivity() {

    private lateinit var etSearch: EditText
    private lateinit var layoutList: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private var report: FulfillmentReportDto? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_warehouse_inventory)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }
        val securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        etSearch    = findViewById(R.id.etSearch)
        layoutList  = findViewById(R.id.layoutProducts)
        tvEmpty     = findViewById(R.id.tvNoResults)
        progressBar = findViewById(R.id.progressBar)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = getString(R.string.pof_title)
            setNavigationOnClickListener { finish() }
        }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { render(s?.toString()?.trim() ?: "") }
        })
        load()
    }

    private fun load() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getFulfillmentReport()
                if (resp.isSuccessful) {
                    report = resp.body()
                    render(etSearch.text?.toString()?.trim() ?: "")
                } else {
                    val msg = try { org.json.JSONObject(resp.errorBody()?.string() ?: "").optString("error") } catch (_: Exception) { "" }
                    Snackbar.make(findViewById(android.R.id.content), msg.ifBlank { getString(R.string.msg_server_error, resp.code().toString()) }, Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun render(query: String) {
        val r = report ?: return
        layoutList.removeAllViews()
        layoutList.addView(TextView(this).apply {
            text = getString(R.string.pof_summary, r.dateFrom, r.dateTo, r.preOrderCount)
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(4.dp, 0, 4.dp, 8.dp)
        })
        val lines = if (query.length < 2) r.data else r.data.filter { it.productName.contains(query, ignoreCase = true) }
        tvEmpty.text = getString(R.string.pof_empty)
        tvEmpty.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
        for (line in lines) layoutList.addView(card(line))
    }

    private fun card(l: FulfillmentLineDto): View {
        val isLbs = com.example.test.data.isLbsUnit(l.unit)
        val unit = if (isLbs) getString(R.string.pof_boxes) else (l.unit ?: "")
        val fmt = { v: Double -> com.example.test.data.formatQty(v) }
        val (statusText, statusColor) = when (l.status) {
            "out" -> getString(R.string.pof_status_out) to R.color.red
            "short" -> getString(R.string.pof_status_short) to R.color.amber_dark
            else -> getString(R.string.pof_status_ok) to R.color.success
        }
        val available = fmt(l.available) + (l.availableLbs?.let { " (${fmt(it)} lb)" } ?: "")
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 10.dp, 14.dp, 10.dp)
            addView(LinearLayout(this@PreOrderFulfillmentActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(context).apply {
                    text = "${l.productName} · $unit"
                    textSize = 13f
                    setTextColor(getColor(R.color.text_primary))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(context).apply {
                    text = statusText
                    textSize = 11f
                    setTextColor(getColor(statusColor))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
            })
            val extra = listOfNotNull(
                l.shortage.takeIf { it > 0 }?.let { getString(R.string.pof_short, fmt(it)) },
                l.withoutQty.takeIf { it > 0 }?.let { getString(R.string.pof_without_qty, it) },
            )
            addView(TextView(context).apply {
                text = (listOf(getString(R.string.pof_line, fmt(l.requested), available)) + extra).joinToString(" · ")
                textSize = 12f
                setTextColor(getColor(R.color.text_primary))
            })
            addView(TextView(context).apply {
                text = l.orders.joinToString("\n") { o ->
                    "#${o.preOrderId} ${o.customerName} · ${o.scheduledDate}" + (o.requested?.let { " · ${fmt(it)}" } ?: "")
                }
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 4.dp, 0, 0)
            })
        }
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dp }
            setCardBackgroundColor(getColor(R.color.surface))
            radius = 0f
            cardElevation = 1f
            addView(body)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

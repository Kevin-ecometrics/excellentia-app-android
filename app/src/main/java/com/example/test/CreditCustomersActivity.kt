package com.example.test

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.test.data.CreditCustomerDto
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Locale

// Créditos por cliente (ticket 2, 2026-10-27): el operador ve el saldo de
// crédito de todos los clientes sin tener que imprimir un ticket. Solo con
// conexión (decisión del usuario) — GET /api/credits/customers.
class CreditCustomersActivity : BaseActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutEntries: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: EditText

    private var customers: List<CreditCustomerDto> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_credit_customers)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        val securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        swipeRefresh  = findViewById(R.id.swipeRefresh)
        layoutEntries = findViewById(R.id.layoutEntries)
        tvEmpty       = findViewById(R.id.tvEmpty)
        etSearch      = findViewById(R.id.etSearch)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        swipeRefresh.setColorSchemeColors(getColor(R.color.primary))
        swipeRefresh.setOnRefreshListener { load() }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { render() }
        })
    }

    // onResume y no onCreate: al volver del detalle (o de "Agregar crédito")
    // el saldo puede haber cambiado.
    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getCreditCustomers()
                if (resp.isSuccessful) {
                    customers = resp.body()?.data ?: emptyList()
                    render()
                } else {
                    showError(getString(R.string.msg_server_error, resp.code().toString()))
                }
            } catch (e: Exception) {
                showError(e.localizedMessage ?: getString(R.string.error_no_connection))
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun render() {
        val query = etSearch.text.toString().trim().lowercase(Locale.getDefault())
        val shown = if (query.isEmpty()) customers
            else customers.filter { it.customerName.lowercase(Locale.getDefault()).contains(query) }

        layoutEntries.removeAllViews()
        tvEmpty.text = if (query.isEmpty()) getString(R.string.label_no_customer_credits)
            else getString(R.string.label_no_results, etSearch.text.toString().trim())
        tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        for (c in shown) layoutEntries.addView(buildCard(c))
    }

    private fun buildCard(c: CreditCustomerDto): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp }
            radius = 0f
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.white))
            strokeColor = getColor(R.color.ex_line)
            strokeWidth = 1.dp
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@CreditCustomersActivity, CustomerCreditsActivity::class.java).apply {
                    putExtra("customer_id", c.customerId)
                    putExtra("customer_name", c.customerName)
                })
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = c.customerName
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.ex_ink))
        })
        texts.addView(TextView(this).apply {
            text = getString(R.string.label_credit_earned_used, money(c.totalEarned), money(c.totalUsed))
            textSize = 12f
            setTextColor(getColor(R.color.ex_ink_soft))
        })
        row.addView(texts)
        row.addView(TextView(this).apply {
            text = money(c.balance)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(if (c.balance > 0) R.color.ex_danger_deep else R.color.ex_ink_soft))
        })
        card.addView(row)
        return card
    }

    private fun money(v: Double) = String.format(Locale.US, "$%.2f", v)

    private fun showError(msg: String) {
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

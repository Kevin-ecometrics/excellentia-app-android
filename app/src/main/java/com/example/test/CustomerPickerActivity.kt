package com.example.test

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.QbCustomer
import com.example.test.data.network.RetrofitClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class CustomerPickerActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutCustomers: LinearLayout
    private lateinit var layoutError: View
    private lateinit var tvError: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var scrollCustomers: View

    private var allCustomers: List<QbCustomer> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_customer_picker)
        val pad = resources.getDimensionPixelSize(R.dimen.padding_screen)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad)
            insets
        }

        etSearch = findViewById(R.id.etSearch)
        progressBar = findViewById(R.id.progressBar)
        layoutCustomers = findViewById(R.id.layoutCustomers)
        layoutError = findViewById(R.id.layoutError)
        tvError = findViewById(R.id.tvError)
        btnRetry = findViewById(R.id.btnRetry)
        scrollCustomers = findViewById(R.id.scrollCustomers)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        btnRetry.setOnClickListener { loadCustomers() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterAndShow(s?.toString() ?: "")
            }
        })

        loadCustomers()
    }

    private fun loadCustomers() {
        progressBar.visibility = View.VISIBLE
        scrollCustomers.visibility = View.GONE
        layoutError.visibility = View.GONE
        layoutCustomers.removeAllViews()

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.getApi().getCustomers()
                if (response.isSuccessful) {
                    allCustomers = response.body()
                        ?.queryResponse?.customers
                        ?.filter { it.active }
                        ?.sortedBy { it.displayName }
                        ?: emptyList()
                    progressBar.visibility = View.GONE
                    scrollCustomers.visibility = View.VISIBLE
                    filterAndShow(etSearch.text.toString())
                } else {
                    showError("Error al cargar clientes (${response.code()})")
                }
            } catch (e: Exception) {
                showError("Sin conexión — verifica el servidor")
            }
        }
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        scrollCustomers.visibility = View.GONE
        layoutError.visibility = View.VISIBLE
        tvError.text = message
    }

    private fun filterAndShow(query: String) {
        val filtered = if (query.isBlank()) allCustomers
        else allCustomers.filter { it.displayName.contains(query, ignoreCase = true) }
        buildRows(filtered)
    }

    private fun buildRows(customers: List<QbCustomer>) {
        layoutCustomers.removeAllViews()

        if (customers.isEmpty()) {
            val empty = TextView(this).apply {
                text = if (allCustomers.isEmpty()) "No hay clientes en QuickBooks"
                       else "No se encontraron clientes"
                textSize = 14f
                setTextColor(resources.getColor(R.color.text_secondary, theme))
                setPadding(0, 32.dp, 0, 0)
                gravity = android.view.Gravity.CENTER
            }
            layoutCustomers.addView(empty)
            return
        }

        for (customer in customers) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8.dp }
                radius = 14.dp.toFloat()
                cardElevation = 2.dp.toFloat()
                setCardBackgroundColor(resources.getColor(R.color.surface, theme))
                isClickable = true
                isFocusable = true
                setOnClickListener { confirmSelection(customer) }
            }

            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(16.dp, 16.dp, 16.dp, 16.dp)
            }

            val tvName = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = customer.displayName
                textSize = 15f
                setTextColor(resources.getColor(R.color.text_primary, theme))
            }

            val tvId = TextView(this).apply {
                text = "#${customer.id}"
                textSize = 12f
                setTextColor(resources.getColor(R.color.text_secondary, theme))
            }

            row.addView(tvName)
            row.addView(tvId)
            card.addView(row)
            layoutCustomers.addView(card)
        }
    }

    private fun confirmSelection(customer: QbCustomer) {
        AlertDialog.Builder(this)
            .setTitle("Confirmar cliente")
            .setMessage("¿Asignar este pedido a:\n\n${customer.displayName}?")
            .setPositiveButton("Sí, asignar") { _, _ ->
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra("customer_id", customer.id)
                    putExtra("customer_name", customer.displayName)
                })
                finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

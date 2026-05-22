package com.example.test

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import com.google.android.material.appbar.MaterialToolbar
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
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.dao.CustomerDao
import com.example.test.data.local.entities.CachedCustomerEntity
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
    private lateinit var tvOfflineBanner: TextView
    private lateinit var customerDao: CustomerDao

    private var allCustomers: List<QbCustomer> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_customer_picker)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        customerDao = CustomerDao(AppDatabase.getInstance(this))

        etSearch = findViewById(R.id.etSearch)
        progressBar = findViewById(R.id.progressBar)
        layoutCustomers = findViewById(R.id.layoutCustomers)
        layoutError = findViewById(R.id.layoutError)
        tvError = findViewById(R.id.tvError)
        btnRetry = findViewById(R.id.btnRetry)
        scrollCustomers = findViewById(R.id.scrollCustomers)
        tvOfflineBanner = findViewById(R.id.tvOfflineBanner)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
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
        tvOfflineBanner.visibility = View.GONE
        layoutCustomers.removeAllViews()

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.getApi().getCustomers()
                if (response.isSuccessful) {
                    val customers = response.body()
                        ?.queryResponse?.customers
                        ?.filter { it.active }
                        ?.sortedBy { it.displayName }
                        ?: emptyList()

                    // Guardar en cache local
                    customerDao.insertAll(customers.map {
                        CachedCustomerEntity(
                            id = it.id,
                            displayName = it.displayName,
                            addressLine1 = it.addressLine1,
                            city = it.city,
                            stateCode = it.stateCode,
                            postalCode = it.postalCode
                        )
                    })

                    allCustomers = customers
                    progressBar.visibility = View.GONE
                    scrollCustomers.visibility = View.VISIBLE
                    filterAndShow(etSearch.text.toString())
                } else {
                    loadFromCache("Error del servidor (${response.code()})")
                }
            } catch (e: Exception) {
                loadFromCache("Sin conexión — mostrando clientes guardados")
            }
        }
    }

    private fun loadFromCache(reason: String) {
        val cached = customerDao.getAll()
        if (cached.isNotEmpty()) {
            allCustomers = cached.map {
                QbCustomer(
                    id = it.id,
                    displayName = it.displayName,
                    addressLine1 = it.addressLine1,
                    city = it.city,
                    stateCode = it.stateCode,
                    postalCode = it.postalCode
                )
            }
            progressBar.visibility = View.GONE
            scrollCustomers.visibility = View.VISIBLE
            tvOfflineBanner.visibility = View.VISIBLE
            tvOfflineBanner.text = reason
            filterAndShow(etSearch.text.toString())
        } else {
            showError("Sin conexión y sin clientes guardados.\nConéctate al servidor al menos una vez.")
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
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            }

            val nameAddressCol = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
            }

            val tvName = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = customer.displayName
                textSize = 15f
                setTextColor(resources.getColor(R.color.text_primary, theme))
            }
            nameAddressCol.addView(tvName)

            val address = customer.fullAddress
            if (!address.isNullOrBlank()) {
                val tvAddress = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 2.dp }
                    text = address
                    textSize = 12f
                    setTextColor(resources.getColor(R.color.text_secondary, theme))
                }
                nameAddressCol.addView(tvAddress)
            }

            val tvId = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 8.dp }
                text = "#${customer.id}"
                textSize = 12f
                setTextColor(resources.getColor(R.color.text_secondary, theme))
            }

            row.addView(nameAddressCol)
            row.addView(tvId)
            card.addView(row)
            layoutCustomers.addView(card)
        }
    }

    private fun confirmSelection(customer: QbCustomer) {
        val addressLine = customer.fullAddress?.let { "\n$it" } ?: ""
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Confirmar cliente")
            .setMessage("¿Asignar este pedido a:\n\n${customer.displayName}$addressLine?")
            .setPositiveButton("Sí, asignar") { _, _ ->
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra("customer_id", customer.id)
                    putExtra("customer_name", customer.displayName)
                    putExtra("customer_address", customer.fullAddress)
                })
                finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

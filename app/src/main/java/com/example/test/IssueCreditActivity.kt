package com.example.test

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.CreditItemRequest
import com.example.test.data.IssueCreditRequest
import com.example.test.data.ProductDto
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Locale

class IssueCreditActivity : BaseActivity() {

    private lateinit var cardCustomer: MaterialCardView
    private lateinit var tvSelectedCustomer: TextView
    private lateinit var tvCustomerCredit: TextView
    private lateinit var layoutItems: LinearLayout
    private lateinit var tvNoItems: TextView
    private lateinit var tvTotalEstimated: TextView
    private lateinit var btnSearchProduct: MaterialButton
    private lateinit var btnSaveCredit: MaterialButton
    private lateinit var securePrefs: SecurePreferences

    private var selectedCustomerId: String? = null
    private var selectedCustomerName: String? = null

    private data class CreditLine(
        val barcode: String,
        val productName: String,
        var qty: Double,
        val unit: String?,
        val estimatedUnitValue: Double
    )

    private val lines = mutableListOf<CreditLine>()

    private companion object {
        const val DW_RESULT_ACTION = "com.symbol.datawedge.datawedge.ACTION_RESULT"
        const val DW_EXTRA_DATA    = "com.symbol.datawedge.data_string"
    }

    private val dwReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DW_RESULT_ACTION) {
                val data = intent.getStringExtra(DW_EXTRA_DATA) ?: return
                if (data.isNotBlank()) onBarcodeScanned(data)
            }
        }
    }

    private val customerPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedCustomerId   = result.data?.getStringExtra("customer_id")
            selectedCustomerName = result.data?.getStringExtra("customer_name")
            tvSelectedCustomer.text = selectedCustomerName ?: getString(R.string.label_customer_selected)
            tvSelectedCustomer.setTextColor(getColor(R.color.text_primary))
            selectedCustomerId?.let { refreshCustomerCredit(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_issue_credit)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        cardCustomer       = findViewById(R.id.cardCustomer)
        tvSelectedCustomer = findViewById(R.id.tvSelectedCustomer)
        tvCustomerCredit   = findViewById(R.id.tvCustomerCredit)
        layoutItems        = findViewById(R.id.layoutItems)
        tvNoItems          = findViewById(R.id.tvNoItems)
        tvTotalEstimated   = findViewById(R.id.tvTotalEstimated)
        btnSearchProduct   = findViewById(R.id.btnSearchProduct)
        btnSaveCredit      = findViewById(R.id.btnSaveCredit)

        securePrefs.getActiveCustomerId()?.let { id ->
            selectedCustomerId   = id
            selectedCustomerName = securePrefs.getActiveCustomerName()
            tvSelectedCustomer.text = selectedCustomerName ?: ""
            tvSelectedCustomer.setTextColor(getColor(R.color.text_primary))
            refreshCustomerCredit(id)
        }

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        cardCustomer.setOnClickListener {
            customerPickerLauncher.launch(Intent(this, CustomerPickerActivity::class.java))
        }

        btnSearchProduct.setOnClickListener { showProductSearchDialog() }
        btnSaveCredit.setOnClickListener { saveCredit() }

        registerDwReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(dwReceiver) } catch (_: Exception) {}
    }

    private fun refreshCustomerCredit(customerId: String) {
        tvCustomerCredit.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getCustomerCreditBalance(customerId)
                if (resp.isSuccessful) {
                    val balance = resp.body()?.balance ?: 0.0
                    if (balance > 0) {
                        tvCustomerCredit.text = getString(R.string.label_available_credit, balance)
                        tvCustomerCredit.visibility = View.VISIBLE
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun registerDwReceiver() {
        val filter = IntentFilter(DW_RESULT_ACTION).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dwReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(dwReceiver, filter)
        }
    }

    private fun onBarcodeScanned(barcode: String) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getProductByBarcode(barcode)
                if (resp.isSuccessful) {
                    val product = resp.body()?.data ?: return@launch
                    askQtyThenAdd(product)
                } else {
                    Snackbar.make(findViewById(android.R.id.content),
                        getString(R.string.error_product_not_found_barcode, barcode), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content),
                    getString(R.string.error_searching_product), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showProductSearchDialog() {
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
                askQtyThenAdd(p)
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

    // Espeja unitValueOf() del backend (creditCalculator.ts) solo para el
    // estimado en pantalla — el backend recalcula la cifra autoritativa.
    // Case/Unit: product.price es el precio del paquete completo, se divide
    // por el tamaño de paquete para obtener el valor de una sola unidad
    // dañada. products.case_qty no existe en MySQL — product.caseQty siempre
    // llega null/0, el tamaño real de paquete viaja en product.qty.
    private fun estimatedUnitValueOf(product: ProductDto): Double {
        val caseSize = product.caseQty?.takeIf { it > 0 } ?: product.qty.takeIf { it > 0 }
        return if (com.example.test.data.isCaseUnitType(product.unit) && caseSize != null)
            product.price / caseSize
        else
            // Bucket y Lbs: product.price ya es el valor por unidad/lb, directo
            // (para Lbs, qty ya es el peso real, no hace falta estimar con
            // weightPerUnit).
            product.price
    }

    private fun askQtyThenAdd(product: ProductDto) {
        val barcode = product.barcode ?: return
        val ctx = this
        val isLbs = com.example.test.data.isLbsUnit(product.unit)
        val etQty = android.widget.EditText(ctx).apply {
            inputType = if (isLbs)
                android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            else
                android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.hint_credit_qty)
            setText("1")
            selectAll()
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.title_credit_qty))
            .setMessage(product.name)
            .setView(etQty)
            .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                val qty = etQty.text.toString().toDoubleOrNull()?.coerceAtLeast(if (isLbs) 0.01 else 1.0) ?: 1.0
                addLine(barcode, product.name, qty, product.unit, estimatedUnitValueOf(product))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun addLine(barcode: String, productName: String, qty: Double, unit: String?, estimatedUnitValue: Double) {
        val existing = lines.find { it.barcode == barcode }
        if (existing != null) {
            existing.qty += qty
        } else {
            lines.add(CreditLine(barcode, productName, qty, unit, estimatedUnitValue))
        }
        rebuildItemsList()
        updateTotal()
    }

    private fun rebuildItemsList() {
        layoutItems.removeAllViews()
        tvNoItems.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
        for ((idx, line) in lines.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dp }
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvItem = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val qtyLabel = com.example.test.data.formatDamageQty(line.qty, line.unit)
                text = "${line.productName}\n$qtyLabel ≈ ${String.format(Locale.US, "$%.2f", line.qty * line.estimatedUnitValue)}"
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
            }

            val btnRemove = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp)
                setIconResource(R.drawable.ic_remove)
                iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.red))
                setOnClickListener {
                    lines.removeAt(idx)
                    rebuildItemsList()
                    updateTotal()
                }
            }

            row.addView(tvItem)
            row.addView(btnRemove)
            layoutItems.addView(row)
        }
    }

    private fun updateTotal() {
        val total = lines.sumOf { it.qty * it.estimatedUnitValue }
        tvTotalEstimated.text = String.format(Locale.US, "$%.2f", total)
    }

    private fun saveCredit() {
        if (selectedCustomerId == null || selectedCustomerName == null) {
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_select_customer_first), Snackbar.LENGTH_SHORT).show()
            return
        }
        if (lines.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_add_at_least_one_product), Snackbar.LENGTH_SHORT).show()
            return
        }

        btnSaveCredit.isEnabled = false
        btnSaveCredit.text = getString(R.string.btn_saving)

        val request = IssueCreditRequest(
            customerId   = selectedCustomerId!!,
            customerName = selectedCustomerName,
            items = lines.map { CreditItemRequest(it.barcode, it.productName, it.qty) }
        )

        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().issueCredit(request)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    Snackbar.make(findViewById(android.R.id.content),
                        getString(R.string.msg_credit_issued_success, body?.creditsTotal ?: 0.0, selectedCustomerName ?: ""), Snackbar.LENGTH_LONG).show()
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    Snackbar.make(findViewById(android.R.id.content),
                        getString(R.string.error_issue_credit_failed, resp.code().toString()), Snackbar.LENGTH_LONG).show()
                    btnSaveCredit.isEnabled = true
                    btnSaveCredit.text = getString(R.string.btn_save_credit)
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content),
                    e.localizedMessage ?: getString(R.string.error_no_connection), Snackbar.LENGTH_LONG).show()
                btnSaveCredit.isEnabled = true
                btnSaveCredit.text = getString(R.string.btn_save_credit)
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

package com.example.test

import android.app.Activity
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.CreateReceiptRequest
import com.example.test.data.ProductDto
import com.example.test.data.ReceiptItemRequest
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.example.test.data.scan.DataWedgeScanner
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

// Fase 112 — Recepción de productos: escanear cada caja, indicar cantidad y
// fecha de expiración (opcional), acumular localmente y confirmar de una sola
// vez (POST /api/warehouse/receipts). Mismo patrón que IssueCreditActivity
// (scan → acumular líneas → guardar batch) pero sin cliente asociado.
class ReceivingActivity : BaseActivity() {

    private lateinit var layoutItems: LinearLayout
    private lateinit var tvNoItems: TextView
    private lateinit var btnSearchProduct: MaterialButton
    private lateinit var btnSaveReceipt: MaterialButton
    private lateinit var securePrefs: SecurePreferences
    private lateinit var dwReceiver: android.content.BroadcastReceiver

    private data class ReceiptLine(
        val barcode: String?,
        val productId: Int?,
        val productName: String,
        var qty: Double,
        var expirationDate: String?
    )

    private val lines = mutableListOf<ReceiptLine>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_receiving)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        layoutItems      = findViewById(R.id.layoutItems)
        tvNoItems        = findViewById(R.id.tvNoItems)
        btnSearchProduct = findViewById(R.id.btnSearchProduct)
        btnSaveReceipt   = findViewById(R.id.btnSaveReceipt)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        btnSearchProduct.setOnClickListener { showProductSearchDialog() }
        btnSaveReceipt.setOnClickListener { saveReceipt() }

        dwReceiver = DataWedgeScanner.createReceiver { barcode -> onBarcodeScanned(barcode) }

        // Entrada directa desde el modal de "stock insuficiente" al cargar una
        // ruta (WarehouseRouteDetailActivity) — llega con el barcode ya
        // escaneado ahí, así no hay que volver a buscarlo acá.
        intent.getStringExtra("barcode")?.let { onBarcodeScanned(it) }
    }

    override fun onResume() {
        super.onResume()
        DataWedgeScanner.register(this, dwReceiver)
    }

    override fun onPause() {
        super.onPause()
        DataWedgeScanner.unregister(this, dwReceiver)
    }

    private fun onBarcodeScanned(barcode: String) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getProductByBarcode(barcode)
                if (resp.isSuccessful) {
                    resp.body()?.data?.let { askQtyThenDate(it) }
                } else {
                    Snackbar.make(findViewById(android.R.id.content),
                        getString(R.string.error_product_not_found_barcode, barcode), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content),
                    e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showProductSearchDialog() {
        val ctx = this
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val etSearch = EditText(ctx).apply {
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
        val lvResults = ListView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, listHeightPx)
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

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.title_search_product_by_name))
            .setView(layout)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()

        lvResults.setOnItemClickListener { _, _, idx, _ ->
            foundProducts.getOrNull(idx)?.let { p ->
                dialog.dismiss()
                askQtyThenDate(p)
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
                                    resultsAdapter.add("${p.name}  ·  ${p.barcode ?: getString(R.string.no_barcode_label)}")
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

    private fun askQtyThenDate(product: ProductDto) {
        val isLbs = com.example.test.data.isLbsUnit(product.unit)
        val etQty = EditText(this).apply {
            inputType = if (isLbs)
                android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            else
                android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
            selectAll()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_receiving_qty))
            .setMessage(product.name)
            .setView(etQty)
            .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                val qty = etQty.text.toString().toDoubleOrNull()?.coerceAtLeast(if (isLbs) 0.01 else 1.0) ?: 1.0
                askExpirationThenAdd(product, qty)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun askExpirationThenAdd(product: ProductDto, qty: Double) {
        var chosenDate: String? = null
        val btnDate = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.wh_btn_pick_expiration)
        }
        btnDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                chosenDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                btnDate.text = chosenDate
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val density = resources.displayMetrics.density
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
            addView(btnDate)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.label_expiration_date))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                addLine(product.barcode, product.id, product.name, qty, chosenDate)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun addLine(barcode: String?, productId: Int, productName: String, qty: Double, expirationDate: String?) {
        // A diferencia de créditos/daños, NO se agrupa por barcode: cada
        // escaneo es su propio lote (mismo producto puede tener cajas con
        // expiraciones distintas) — ver createReceipt en warehouseController.ts.
        lines.add(ReceiptLine(barcode, productId, productName, qty, expirationDate))
        rebuildItemsList()
    }

    private fun rebuildItemsList() {
        layoutItems.removeAllViews()
        tvNoItems.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
        for ((idx, line) in lines.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dp }
                gravity = Gravity.CENTER_VERTICAL
            }
            val tvItem = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val exp = line.expirationDate ?: getString(R.string.wh_no_expiration_set)
                text = getString(R.string.wh_receipt_line_summary, line.productName, line.qty, exp)
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
                }
            }
            row.addView(tvItem)
            row.addView(btnRemove)
            layoutItems.addView(row)
        }
    }

    private fun saveReceipt() {
        if (lines.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_add_at_least_one_product), Snackbar.LENGTH_SHORT).show()
            return
        }
        btnSaveReceipt.isEnabled = false
        btnSaveReceipt.text = getString(R.string.btn_saving)

        val request = CreateReceiptRequest(
            items = lines.map {
                ReceiptItemRequest(barcode = it.barcode, productId = if (it.barcode == null) it.productId else null,
                    quantity = it.qty, expirationDate = it.expirationDate)
            }
        )

        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().createReceipt(request)
                if (resp.isSuccessful) {
                    val count = resp.body()?.items?.size ?: lines.size
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_receipt_saved, count), Snackbar.LENGTH_LONG).show()
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_receipt_failed, resp.code().toString()), Snackbar.LENGTH_LONG).show()
                    btnSaveReceipt.isEnabled = true
                    btnSaveReceipt.text = getString(R.string.wh_btn_save_receipt)
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_LONG).show()
                btnSaveReceipt.isEnabled = true
                btnSaveReceipt.text = getString(R.string.wh_btn_save_receipt)
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

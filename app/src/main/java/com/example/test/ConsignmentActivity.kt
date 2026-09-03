package com.example.test

import android.content.BroadcastReceiver
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.ConsignmentItemDto
import com.example.test.data.ConsignmentRegisterItem
import com.example.test.data.ConsignmentRegisterRequest
import com.example.test.data.ConsignmentSettleItem
import com.example.test.data.ConsignmentSettleRequest
import com.example.test.data.ProductDto
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.example.test.data.scan.DataWedgeScanner
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Locale

// Fase 115.4 — Consignación: registrar qué se dejó en esta parada (resta
// stock igual que cargar a la ruta con stock general, mismo movementType
// ROUTE_LOAD) y liquidar más tarde (vendido → venta real AWAITING_APPROVAL,
// devuelto → restituye stock). Una parada puede recibir varias rondas de
// "dejar" antes de liquidar, y una liquidación no exige agotar
// quantity_left — el remanente queda sin liquidar para una próxima visita.
class ConsignmentActivity : BaseActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var btnAddItem: MaterialButton
    private lateinit var layoutItems: LinearLayout
    private lateinit var tvNoItems: TextView
    private lateinit var securePrefs: SecurePreferences
    private lateinit var dwReceiver: BroadcastReceiver

    private var routeId: Int = -1
    private var stopId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_consignment)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        routeId = intent.getIntExtra("route_id", -1)
        stopId = intent.getIntExtra("stop_id", -1)
        val customerName = intent.getStringExtra("customer_name")
        if (routeId == -1 || stopId == -1) { finish(); return }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        toolbar     = findViewById(R.id.toolbar)
        btnAddItem  = findViewById(R.id.btnAddConsignmentItem)
        layoutItems = findViewById(R.id.layoutConsignmentItems)
        tvNoItems   = findViewById(R.id.tvNoConsignmentItems)

        toolbar.title = customerName ?: getString(R.string.title_consignment)
        toolbar.setNavigationOnClickListener { finish() }
        btnAddItem.setOnClickListener { showManualEntryDialog() }

        dwReceiver = DataWedgeScanner.createReceiver { barcode -> onBarcodeScanned(barcode) }

        loadConsignment()
    }

    override fun onResume() {
        super.onResume()
        DataWedgeScanner.register(this, dwReceiver)
    }

    override fun onPause() {
        super.onPause()
        DataWedgeScanner.unregister(this, dwReceiver)
    }

    private fun loadConsignment() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getConsignment(routeId, stopId)
                if (resp.isSuccessful) {
                    renderItems(resp.body()?.data ?: emptyList())
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderItems(items: List<ConsignmentItemDto>) {
        layoutItems.removeAllViews()
        tvNoItems.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (ci in items) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 10.dp
                }
                setCardBackgroundColor(getColor(R.color.surface))
                radius = 0f
                cardElevation = 2f
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            }
            content.addView(TextView(this).apply {
                text = ci.name
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            content.addView(TextView(this).apply {
                text = getString(R.string.wh_consignment_left_label, ci.quantityLeft)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 2.dp, 0, 8.dp)
            })

            if (ci.settledAt != null) {
                content.addView(TextView(this).apply {
                    text = getString(R.string.wh_consignment_settled_summary, ci.quantitySold, ci.quantityReturned)
                    textSize = 12f
                    setTextColor(getColor(R.color.success))
                })
            } else {
                val etSold = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(String.format(Locale.US, "%.2f", 0.0))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val etReturned = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(String.format(Locale.US, "%.2f", 0.0))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = 8.dp
                    }
                }
                fun labeledField(label: String, field: EditText): LinearLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@ConsignmentActivity).apply {
                        text = label
                        textSize = 11f
                        setTextColor(getColor(R.color.text_secondary))
                    })
                    addView(field)
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                row.addView(labeledField(getString(R.string.wh_consignmentSold_label), etSold))
                row.addView(LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(8.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                row.addView(labeledField(getString(R.string.wh_consignmentReturned_label), etReturned))
                content.addView(row)

                val btnSettle = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = getString(R.string.btn_settle_consignment)
                    textSize = 11f
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = 8.dp
                    }
                    setOnClickListener {
                        val sold = etSold.text.toString().toDoubleOrNull() ?: 0.0
                        val returned = etReturned.text.toString().toDoubleOrNull() ?: 0.0
                        settleLine(ci, sold, returned)
                    }
                }
                content.addView(btnSettle)
            }

            card.addView(content)
            layoutItems.addView(card)
        }
    }

    private fun settleLine(item: ConsignmentItemDto, quantitySold: Double, quantityReturned: Double) {
        if (quantitySold <= 0 && quantityReturned <= 0) {
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_consignment_settle_empty), Snackbar.LENGTH_SHORT).show()
            return
        }
        if (quantitySold + quantityReturned > item.quantityLeft) {
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_consignment_settle_exceeds, item.quantityLeft), Snackbar.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().settleConsignment(
                    routeId, stopId,
                    ConsignmentSettleRequest(items = listOf(
                        ConsignmentSettleItem(productId = item.productId, quantitySold = quantitySold, quantityReturned = quantityReturned)
                    ))
                )
                if (resp.isSuccessful) {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_consignment_settled), Snackbar.LENGTH_SHORT).show()
                    loadConsignment()
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun onBarcodeScanned(barcode: String) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getProductByBarcode(barcode)
                if (resp.isSuccessful) {
                    resp.body()?.data?.let { showQuantityDialog(it) }
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_product_not_found_barcode, barcode), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showManualEntryDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_manual_entry, null)
        val etBarcode = dialogView.findViewById<EditText>(R.id.etBarcode)
        val lvSuggestions = dialogView.findViewById<android.widget.ListView>(R.id.lvSuggestions)

        val adapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvSuggestions.adapter = adapter
        var suggestions: List<ProductDto> = emptyList()

        fun labelFor(p: ProductDto): String {
            val priceStr = String.format(Locale.US, "$%.2f", p.price)
            val skuPart = p.sku?.let { "  ·  $it" } ?: ""
            return "${p.name}  ·  $priceStr$skuPart"
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_manual_entry))
            .setView(dialogView)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()

        lvSuggestions.setOnItemClickListener { _, _, idx, _ ->
            suggestions.getOrNull(idx)?.let {
                dialog.dismiss()
                showQuantityDialog(it)
            }
        }

        etBarcode.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: return
                if (query.length < 2) {
                    suggestions = emptyList(); adapter.clear(); lvSuggestions.visibility = View.GONE
                    return
                }
                lifecycleScope.launch {
                    try {
                        val resp = RetrofitClient.getApi().searchProducts(query)
                        if (resp.isSuccessful) {
                            suggestions = resp.body()?.data ?: emptyList()
                            adapter.clear()
                            adapter.addAll(suggestions.map { labelFor(it) })
                            lvSuggestions.visibility = if (suggestions.isEmpty()) View.GONE else View.VISIBLE
                        }
                    } catch (_: Exception) { }
                }
            }
        })

        dialog.show()
    }

    private fun showQuantityDialog(product: ProductDto) {
        val etQty = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("1")
            selectAll()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_load_quantity))
            .setMessage(product.name)
            .setView(etQty)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                val qty = etQty.text.toString().toDoubleOrNull()?.coerceAtLeast(0.01) ?: 1.0
                registerItem(product, qty)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun registerItem(product: ProductDto, quantity: Double) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().registerConsignment(
                    routeId, stopId,
                    ConsignmentRegisterRequest(items = listOf(
                        ConsignmentRegisterItem(productId = product.id, quantity = quantity, unit = product.unit, caseQty = product.caseQty)
                    ))
                )
                if (resp.isSuccessful) {
                    val error = resp.body()?.items?.firstOrNull()?.error
                    if (error != null) {
                        Snackbar.make(findViewById(android.R.id.content), error, Snackbar.LENGTH_LONG).show()
                    } else {
                        Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_consignment_registered), Snackbar.LENGTH_SHORT).show()
                        loadConsignment()
                    }
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

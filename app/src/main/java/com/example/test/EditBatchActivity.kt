package com.example.test

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
import com.example.test.data.BatchItem
import com.example.test.data.OrderDto
import com.example.test.data.ProductDto
import com.example.test.data.isCaseUnitType
import com.example.test.data.isLbsUnit
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.example.test.data.repository.OrderRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.util.Locale

// Fase 117 — editar una venta AWAITING_APPROVAL: reemplazo TOTAL de items[].
// Tocar una línea existente (cantidad/precio) y agregar un producto nuevo son
// la misma mecánica — al guardar se manda la lista completa final, no un
// diff. Solo llega acá desde TicketDetailActivity, que ya validó ownership/
// status antes de mostrar el botón "Edit sale" — esta pantalla no repite esa
// validación (si el backend igual la rechaza, el error se muestra tal cual).
class EditBatchActivity : BaseActivity() {

    // Fila editable en pantalla — qty/price se leen en vivo de los EditText
    // de `view` al guardar, no se cachean acá (evita depender de TextWatchers
    // para mantener todo sincronizado).
    private data class EditRow(
        val barcode: String,
        val productName: String,
        val unit: String?,
        val caseQty: Int?,
        val isCourtesy: Boolean,
        val view: View
    )

    private lateinit var layoutItems: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var btnSave: MaterialButton
    private lateinit var orderRepository: OrderRepository
    private var batchId: String = ""
    private val rows = mutableListOf<EditRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_edit_batch)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        batchId = intent.getStringExtra("batch_id") ?: ""
        if (batchId.isBlank()) { finish(); return }

        val securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)
        orderRepository = OrderRepository(AppDatabase.getInstance(this), securePrefs)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        layoutItems = findViewById(R.id.layoutEditItems)
        tvEmpty = findViewById(R.id.tvEmptyEditItems)
        btnSave = findViewById(R.id.btnSaveChanges)

        val ordersJson = intent.getStringExtra("orders_json") ?: "[]"
        val orders: List<OrderDto> = try {
            Gson().fromJson(ordersJson, Array<OrderDto>::class.java).toList()
        } catch (_: Exception) { emptyList() }

        for (o in orders) {
            addRow(
                barcode = o.barcode,
                productName = o.productName,
                unit = o.unit,
                caseQty = o.caseQty,
                isCourtesy = o.isCourtesy,
                price = o.price,
                quantity = o.quantity
            )
        }
        updateEmptyState()

        findViewById<MaterialButton>(R.id.btnAddProduct).setOnClickListener { showAddProductDialog() }
        btnSave.setOnClickListener { saveChanges() }
    }

    // Los 3 tipos de producto (Lbs/Case-Unit/Bucket) guardan cosas distintas
    // en quantity/price/case_qty — mismo criterio que ProductDetailActivity
    // (isCaseBased/isWeightBased ahí): Lbs = peso real en quantity, price es
    // $/lb; Case/Unit = quantity es cuántas cajas/paquetes (no unidades
    // sueltas), price es el precio de la caja COMPLETA, case_qty son las
    // unidades que trae cada caja (solo para desglose, no se multiplica);
    // Bucket (y cualquier otro unit) = quantity es cuántos buckets, price es
    // por bucket, sin case_qty. Sin este distingo, agregar un producto Case/
    // Unit acá guardaba case_qty mal (ver bug de abajo) y la pantalla no
    // dejaba claro qué estaba pidiendo cada campo.
    private fun addRow(
        barcode: String, productName: String, unit: String?, caseQty: Int?,
        isCourtesy: Boolean, price: Double, quantity: Double
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_edit_batch_row, layoutItems, false)
        val tvName = view.findViewById<TextView>(R.id.tvRowName)
        val tvMeta = view.findViewById<TextView>(R.id.tvRowMeta)
        val tvUnitHint = view.findViewById<TextView>(R.id.tvRowUnitHint)
        val tvQtyLabel = view.findViewById<TextView>(R.id.tvRowQtyLabel)
        val tvPriceLabel = view.findViewById<TextView>(R.id.tvRowPriceLabel)
        val etQty = view.findViewById<EditText>(R.id.etRowQty)
        val etPrice = view.findViewById<EditText>(R.id.etRowPrice)
        val tvTotal = view.findViewById<TextView>(R.id.tvRowTotal)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRowRemove)

        val isLbs = isLbsUnit(unit)
        val isCaseUnit = !isLbs && isCaseUnitType(unit) && (caseQty ?: 0) > 0

        tvName.text = productName
        tvMeta.text = if (unit.isNullOrBlank()) barcode else "$barcode · $unit"
        etQty.setText(formatNumber(quantity))
        etPrice.setText(formatNumber(price))

        // Cantidad: decimal para Lbs (peso real), entero para Case/Unit y
        // Bucket (no existen "2.5 cajas") — mismo criterio que el stepper de
        // ProductDetailActivity, que nunca deja decimales fuera de Lbs.
        etQty.inputType = if (isLbs)
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        else
            InputType.TYPE_CLASS_NUMBER

        tvQtyLabel.text = when {
            isLbs -> getString(R.string.label_qty_weight)
            isCaseUnit -> getString(R.string.label_qty_cases)
            else -> unit?.takeIf { it.isNotBlank() } ?: getString(R.string.label_qty_short)
        }
        tvPriceLabel.text = when {
            isLbs -> getString(R.string.label_price_per_lb)
            isCaseUnit -> getString(R.string.label_price_per_case)
            else -> getString(R.string.label_price_short)
        }

        fun refreshTotal() {
            val q = etQty.text.toString().toDoubleOrNull() ?: 0.0
            val p = etPrice.text.toString().toDoubleOrNull() ?: 0.0
            tvTotal.text = String.format(Locale.US, "$%.2f", q * p)

            // Desglose por unidad individual — mismo texto que
            // ProductDetailActivity.recalcTotal() para Case/Unit (paquetes ×
            // unidades/paquete = unidades totales, y precio por unidad
            // suelta). Lbs/Bucket no necesitan esto: el precio ya está
            // expresado directo en su unidad de venta (por lb / por bucket).
            if (isCaseUnit && caseQty != null && caseQty > 1) {
                val totalUnits = (q * caseQty).let { if (it == it.toLong().toDouble()) it.toLong().toString() else formatNumber(it) }
                val unitPrice = if (caseQty > 0) p / caseQty else 0.0
                tvUnitHint.text = String.format(
                    Locale.US, "%s pack(s) × %d = %s units · $%.2f/unit",
                    formatNumber(q), caseQty, totalUnits, unitPrice
                )
                tvUnitHint.visibility = View.VISIBLE
            } else {
                tvUnitHint.visibility = View.GONE
            }
        }
        refreshTotal()

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { refreshTotal() }
        }
        etQty.addTextChangedListener(watcher)
        etPrice.addTextChangedListener(watcher)

        val row = EditRow(barcode, productName, unit, caseQty, isCourtesy, view)
        btnRemove.setOnClickListener {
            layoutItems.removeView(view)
            rows.remove(row)
            updateEmptyState()
        }

        rows.add(row)
        layoutItems.addView(view)
    }

    private fun formatNumber(n: Double): String =
        if (n == n.toLong().toDouble()) n.toLong().toString() else String.format(Locale.US, "%.2f", n)

    private fun updateEmptyState() {
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    // Mismo patrón que ConsignmentActivity.showManualEntryDialog() — búsqueda
    // en vivo contra GET /api/products mientras se tipea, sin escaneo (esta
    // pantalla no tiene lector DataWedge activo).
    private fun showAddProductDialog() {
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
            .setTitle(getString(R.string.btn_add_product))
            .setView(dialogView)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()

        lvSuggestions.setOnItemClickListener { _, _, idx, _ ->
            suggestions.getOrNull(idx)?.let { p ->
                dialog.dismiss()
                if (p.barcode.isNullOrBlank()) {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.no_barcode_label), Snackbar.LENGTH_SHORT).show()
                    return@let
                }
                addRow(
                    barcode = p.barcode,
                    productName = p.name,
                    unit = p.unit,
                    // Bug real: comparaba contra el valor legacy "Case"
                    // suelto — el valor estándar hoy es "Case/Unit" (Fase de
                    // fusión Case+Unit), así que nunca guardaba case_qty
                    // para un producto agregado acá con ese unit.
                    caseQty = if (isCaseUnitType(p.unit)) p.qty else null,
                    isCourtesy = false,
                    price = p.price,
                    // Lbs arranca en el peso típico de una unidad física del
                    // producto (weightPerUnit) si el catálogo lo tiene —
                    // mismo dato que usa ProductDetailActivity como peso por
                    // default —, no en "1.0 lb" fijo. Case/Unit y Bucket sí
                    // arrancan en 1 (1 caja / 1 bucket).
                    quantity = if (isLbsUnit(p.unit)) (p.weightPerUnit ?: 1.0) else 1.0
                )
                updateEmptyState()
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

    private fun saveChanges() {
        if (rows.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_edit_batch_empty), Snackbar.LENGTH_SHORT).show()
            return
        }
        val items = rows.map { row ->
            val etQty = row.view.findViewById<EditText>(R.id.etRowQty)
            val etPrice = row.view.findViewById<EditText>(R.id.etRowPrice)
            val quantity = etQty.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
            val price = etPrice.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
            BatchItem(
                barcode = row.barcode,
                productName = row.productName,
                price = price,
                quantity = quantity,
                total = Math.round(price * quantity * 100) / 100.0,
                unit = row.unit,
                caseQty = row.caseQty,
                isCourtesy = row.isCourtesy
            )
        }

        btnSave.isEnabled = false
        btnSave.text = getString(R.string.btn_saving_changes)
        lifecycleScope.launch {
            val result = orderRepository.editBatch(batchId, items)
            result.onSuccess {
                Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_batch_edited), Snackbar.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
            result.onFailure { e ->
                btnSave.isEnabled = true
                btnSave.text = getString(R.string.btn_save_changes)
                MaterialAlertDialogBuilder(this@EditBatchActivity)
                    .setMessage(getString(R.string.error_edit_batch_generic, e.localizedMessage ?: getString(R.string.error_unknown)))
                    .setPositiveButton(getString(R.string.btn_understood), null)
                    .show()
            }
        }
    }
}

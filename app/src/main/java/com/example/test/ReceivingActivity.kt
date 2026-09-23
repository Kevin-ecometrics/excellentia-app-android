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
import com.example.test.data.Product
import com.example.test.data.ReceiptItemRequest
import com.example.test.data.ReceiptResultItem
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.example.test.data.print.PrintService
import com.example.test.data.repository.ProductRepository
import com.example.test.data.repository.WarehouseRepository
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
    private lateinit var productRepository: ProductRepository
    private lateinit var warehouseRepository: WarehouseRepository
    private lateinit var dwReceiver: android.content.BroadcastReceiver

    private data class ReceiptLine(
        val barcode: String?,
        val productName: String,
        var qty: Double,
        var expirationDate: String?,
        // Fase 120 (addendum) — número de lote REAL del proveedor/cliente,
        // no un id interno. NULL = el almacenista tildó explícitamente "sin
        // número de lote", nunca un campo salteado sin querer.
        var lotNumber: String?,
        // Backlog cliente (2026-09-22) — offline: sin respuesta del servidor
        // para armar el ticket impreso (ver saveReceipt), el ticket se arma
        // con lo que ya se tipeó acá — `unit` hace falta para elegir el
        // formato de cantidad correcto (formatDamageQty).
        val unit: String?,
        // Backlog cliente (2026-09-22) — de qué proveedor vino esta caja
        // puntual (pueden entregar el mismo producto/barcode más de un
        // proveedor). Texto libre, NULL si no se especificó.
        var supplier: String?
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
        // Backlog cliente (2026-09-22) — el almacén tiene pésima conexión:
        // buscar producto y guardar la recepción ahora pasan por estos
        // repositorios offline-first (mismo patrón que Venta/Pre-órdenes),
        // en vez de llamar a RetrofitClient directo.
        val db = AppDatabase.getInstance(this)
        productRepository = ProductRepository(db, securePrefs)
        warehouseRepository = WarehouseRepository(db, securePrefs)

        layoutItems      = findViewById(R.id.layoutItems)
        tvNoItems        = findViewById(R.id.tvNoItems)
        btnSearchProduct = findViewById(R.id.btnSearchProduct)
        btnSaveReceipt   = findViewById(R.id.btnSaveReceipt)
        findViewById<View>(R.id.bannerOffline).visibility =
            if (securePrefs.isOfflineMode()) View.VISIBLE else View.GONE

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
            // ProductRepository.findByBarcode ya resuelve offline-first (va al
            // cache directo si `isOfflineMode()`, o cae ahí si la red falla) —
            // mismo mecanismo que ya usa MainActivity para vender sin señal.
            val product = productRepository.findByBarcode(barcode)
            if (product != null) {
                askQtyThenDate(product)
            } else {
                Snackbar.make(findViewById(android.R.id.content),
                    getString(R.string.error_product_not_found_barcode, barcode), Snackbar.LENGTH_SHORT).show()
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

        var foundProducts: List<Product> = emptyList()
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
                    // Backlog cliente (2026-09-22) — offline-first, mismo
                    // criterio que ProductRepository.findByBarcode: si el modo
                    // offline está prendido, ni se intenta la red; si la
                    // búsqueda por red falla en el momento (señal
                    // intermitente), cae al cache local en vez de mostrar
                    // "error de búsqueda" sin más.
                    fun applyResults(results: List<Product>) {
                        foundProducts = results
                        if (foundProducts.isEmpty()) {
                            tvStatus.text = getString(R.string.label_no_results, query)
                            lvResults.visibility = View.GONE
                        } else {
                            tvStatus.text = ""
                            resultsAdapter.clear()
                            foundProducts.forEach { p ->
                                resultsAdapter.add("${p.name}  ·  ${p.barcode}")
                            }
                            lvResults.visibility = View.VISIBLE
                            lvResults.post { lvResults.setSelection(0) }
                        }
                    }
                    fun searchOffline() = applyResults(
                        productRepository.searchOffline(query).map {
                            Product(it.barcode, it.name, it.price, it.weightPerUnit, it.stock, it.unit, it.qty, it.caseQty, it.qbItemId, it.qbActive, it.shortName)
                        }
                    )
                    if (securePrefs.isOfflineMode()) {
                        searchOffline()
                        return@launch
                    }
                    try {
                        val resp = RetrofitClient.getApi().searchProducts(query)
                        if (resp.isSuccessful) {
                            applyResults((resp.body()?.data ?: emptyList()).map { it.toProduct() })
                        } else {
                            searchOffline()
                        }
                    } catch (_: Exception) {
                        searchOffline()
                    }
                }
            }
        })

        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun askQtyThenDate(product: Product) {
        val isLbs = com.example.test.data.isLbsUnit(product.unit)
        // Feedback cliente (2026-09-22) — para Lbs, "Cantidad recibida"
        // genérico + arrancar en "1" no tenía sentido: una caja de este tipo
        // de producto pesa varias libras (ej. 30), no 1. Se precarga con el
        // peso esperado del catálogo (`weight_per_unit`, ya cargado a mano
        // por el admin — ej. "15/2lbs" = 30 lbs por caja) para que el
        // almacenista solo ajuste si la báscula/etiqueta da un peso distinto,
        // en vez de partir de un valor que siempre hay que borrar.
        val expectedWeight = product.weightPerUnit?.takeIf { it > 0 }
        val etQty = EditText(this).apply {
            inputType = if (isLbs)
                android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            else
                android.text.InputType.TYPE_CLASS_NUMBER
            setText(if (isLbs && expectedWeight != null) com.example.test.data.formatQty(expectedWeight) else "1")
            selectAll()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isLbs) getString(R.string.title_receiving_qty_lbs) else getString(R.string.title_receiving_qty))
            .setMessage(product.name)
            .setView(etQty)
            .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                val qty = etQty.text.toString().toDoubleOrNull()?.coerceAtLeast(if (isLbs) 0.01 else 1.0) ?: 1.0
                askExpirationThenAdd(product, qty)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // Fase 120 (addendum) — antes esta pantalla solo pedía la fecha de
    // expiración; el "número de lote" que se imprimía en el ticket era en
    // realidad el id interno autoincremental de product_lots (nunca visible
    // al cliente/proveedor, y no significa nada para él). Ahora se pide acá
    // el número de lote REAL — lo que trae escrito la caja — con una opción
    // explícita para cuando el producto no trae ninguno (checkbox, no un
    // campo vacío que se pueda confundir con "me olvidé de escribirlo").
    // Backlog cliente (2026-09-22) — los 3 campos pasan a ser obligatorios
    // (proveedor, lote o "sin número de lote", y fecha de expiración) — antes
    // los 3 eran opcionales. "Confirmar" ya no cierra el diálogo solo: valida
    // primero y marca en rojo lo que falte, sin perder lo que ya se tipeó
    // (mismo patrón que otros diálogos de la app que necesitan bloquear el
    // positive button hasta que la validación pase — se arma con .create()
    // en vez de dejar que el builder cierre solo con el listener).
    private fun askExpirationThenAdd(product: Product, qty: Double) {
        var chosenDate: String? = null
        val density = resources.displayMetrics.density
        val tvDateError = TextView(this).apply {
            text = getString(R.string.error_expiration_required)
            textSize = 11f
            setTextColor(getColor(R.color.red))
            visibility = View.GONE
            setPadding(0, 2.dp, 0, 0)
        }
        val btnDate = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.wh_btn_pick_expiration)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
        }
        btnDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                chosenDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                btnDate.text = chosenDate
                tvDateError.visibility = View.GONE
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        // Backlog cliente (2026-09-23) — algunos productos de verdad nunca
        // vencen (ej. sal, azúcar) — la Fase 127 hizo la fecha obligatoria
        // para todo sin excepción, lo cual obligaba a inventar una fecha
        // lejana en esos casos. Mismo patrón que "sin número de lote": un
        // checkbox explícito, no un campo vacío que se pueda confundir con
        // "me olvidé de cargarlo".
        val cbNoExpiration = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = getString(R.string.cb_no_expiration)
        }
        cbNoExpiration.setOnCheckedChangeListener { _, checked ->
            btnDate.isEnabled = !checked
            if (checked) {
                chosenDate = null
                btnDate.text = getString(R.string.wh_btn_pick_expiration)
                tvDateError.visibility = View.GONE
            }
        }
        val etLotNumber = EditText(this).apply {
            hint = getString(R.string.hint_lot_number)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val cbNoLotNumber = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = getString(R.string.cb_no_lot_number)
        }
        cbNoLotNumber.setOnCheckedChangeListener { _, checked ->
            etLotNumber.isEnabled = !checked
            if (checked) { etLotNumber.setText(""); etLotNumber.error = null }
        }
        // Backlog cliente (2026-09-22) — proveedor de ESTA caja puntual. Ahora
        // obligatorio (antes opcional) a pedido del usuario.
        val etSupplier = EditText(this).apply {
            hint = getString(R.string.hint_supplier)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
            addView(etSupplier)
            addView(etLotNumber)
            addView(cbNoLotNumber)
            addView(btnDate)
            addView(tvDateError)
            addView(cbNoExpiration)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.label_lot_and_expiration))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm), null)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val supplier = etSupplier.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                val lotNumberTyped = etLotNumber.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                var valid = true
                if (supplier == null) {
                    etSupplier.error = getString(R.string.error_field_required)
                    valid = false
                }
                if (!cbNoLotNumber.isChecked && lotNumberTyped == null) {
                    etLotNumber.error = getString(R.string.error_lot_number_required)
                    valid = false
                }
                if (chosenDate == null && !cbNoExpiration.isChecked) {
                    tvDateError.visibility = View.VISIBLE
                    valid = false
                }
                if (!valid) return@setOnClickListener
                val lotNumber = if (cbNoLotNumber.isChecked) null else lotNumberTyped
                addLine(product.barcode, product.name, qty, chosenDate, lotNumber, product.unit, supplier)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun addLine(barcode: String?, productName: String, qty: Double, expirationDate: String?, lotNumber: String?, unit: String?, supplier: String?) {
        // A diferencia de créditos/daños, NO se agrupa por barcode: cada
        // escaneo es su propio lote (mismo producto puede tener cajas con
        // expiraciones/números de lote/proveedor distintos) — ver
        // createReceipt en warehouseController.ts.
        lines.add(ReceiptLine(barcode, productName, qty, expirationDate, lotNumber, unit, supplier))
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
                val lot = line.lotNumber ?: getString(R.string.cb_no_lot_number)
                var summary = getString(R.string.wh_receipt_line_summary, line.productName, line.qty, exp) + "\n" +
                    getString(R.string.label_lot_summary, lot)
                line.supplier?.let { summary += "\n" + getString(R.string.label_supplier_summary, it) }
                text = summary
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
                ReceiptItemRequest(barcode = it.barcode, productId = null,
                    quantity = it.qty, expirationDate = it.expirationDate, lotNumber = it.lotNumber, supplier = it.supplier)
            }
        )

        // Backlog cliente (2026-09-22) — offline-first: WarehouseRepository ya
        // decide si intenta la red o encola local (mismo patrón que
        // OrderRepository.createBatch). Acá solo queda reaccionar al
        // resultado — nunca lanza, siempre Result.success/failure.
        lifecycleScope.launch {
            val result = warehouseRepository.createReceipt(request)
            result.onSuccess { body ->
                val offline = body.receiptBatchId == "OFFLINE_PENDING"
                // Sin respuesta real del servidor todavía (offline) — el
                // ticket se arma con lo que ya se tipeó en pantalla, no con
                // `body.items` (vacío en ese caso). lot_id/qb_synced quedan
                // null: no existen hasta que SyncWorker mande esto de verdad.
                val items = if (offline) buildOfflineReceiptItems() else body.items
                val receiptBatchId = body.receiptBatchId
                val count = items.size.takeIf { it > 0 } ?: lines.size
                setResult(Activity.RESULT_OK)
                // Fase 120 — ticket de recepción con lote/expiración, pedido
                // original del usuario. Best-effort igual que el resto de
                // impresiones de la app: si no hay impresora configurada o
                // falla la conexión, no bloquea el flujo — la recepción ya
                // quedó guardada (local o en el servidor) de cualquier forma.
                val printerAddress = securePrefs.getPrinterAddress()
                if (printerAddress.isNullOrBlank()) {
                    // Antes esto quedaba en silencio total (mismo criterio "best
                    // effort" que el resto de la app) — para depurar el reporte de
                    // "no imprimió nada" hace falta poder distinguir este caso
                    // (nunca se intentó, no hay impresora configurada) de un
                    // intento real que falló sin excepción visible.
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_no_printer_configured), Snackbar.LENGTH_LONG).show()
                } else {
                    PrintService.printReceiptTicket(this@ReceivingActivity, printerAddress, receiptBatchId, items)
                        .onSuccess {
                            Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_receipt_ticket_printed), Snackbar.LENGTH_SHORT).show()
                        }
                        .onFailure { e ->
                            Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_print_generic, e.localizedMessage ?: getString(R.string.error_no_connection)), Snackbar.LENGTH_LONG).show()
                        }
                }
                if (offline) {
                    // No hay forma de saber acá si el push a QBO va a fallar
                    // (todavía no se intentó) — el aviso de qb_synced == 0 solo
                    // aplica al camino online, se reintenta/informa después
                    // desde el Historial cuando SyncWorker la mande de verdad.
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.wh_offline_receipt_banner), Snackbar.LENGTH_LONG).show()
                    finish()
                    return@onSuccess
                }
                // qb_synced == 0 (2026-09-10) — antes esto era invisible acá
                // (solo un logger.warn server-side); ahora se le avisa al
                // almacenista en el momento en vez de que se entere después
                // mirando el Historial. La recepción ya quedó guardada local
                // de cualquier forma (nunca se revierte por esto).
                val failedNames = items.filter { it.qbSynced == 0 }.mapNotNull { it.productName }
                if (failedNames.isEmpty()) {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_receipt_saved, count), Snackbar.LENGTH_LONG).show()
                    finish()
                } else {
                    MaterialAlertDialogBuilder(this@ReceivingActivity)
                        .setTitle(getString(R.string.wh_receipt_qb_sync_warning_title))
                        .setMessage(getString(R.string.wh_receipt_qb_sync_warning_msg, count, failedNames.joinToString(", ")))
                        .setCancelable(false)
                        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ -> finish() }
                        .show()
                }
            }.onFailure { e ->
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_LONG).show()
                btnSaveReceipt.isEnabled = true
                btnSaveReceipt.text = getString(R.string.wh_btn_save_receipt)
            }
        }
    }

    // Backlog cliente (2026-09-22) — arma el mismo shape que devuelve el
    // servidor (ReceiptResultItem) pero con lo que ya se tipeó en pantalla,
    // para poder imprimir el ticket cuando la recepción quedó offline (sin
    // lot_id real todavía, sin confirmación de QBO — ninguno de los dos
    // existe hasta que SyncWorker la mande de verdad).
    private fun buildOfflineReceiptItems(): List<ReceiptResultItem> = lines.map {
        ReceiptResultItem(
            lotId = null, lotNumber = it.lotNumber, supplier = it.supplier, productId = null, productName = it.productName,
            barcode = it.barcode, unit = it.unit, quantity = it.qty, expirationDate = it.expirationDate,
            error = null, qbSynced = null
        )
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

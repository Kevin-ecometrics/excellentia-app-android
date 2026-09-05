package com.example.test

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.AddRouteItemRequest
import com.example.test.data.AddStopRequest
import com.example.test.data.AvailablePreOrder
import com.example.test.data.FifoAllocationDto
import com.example.test.data.ProductDto
import com.example.test.data.ReorderStopsRequest
import com.example.test.data.UpdateStopStatusRequest
import com.example.test.data.RouteDetailDto
import com.example.test.data.RouteItemDto
import com.example.test.data.RouteReturnExpectedDto
import com.example.test.data.RouteStopDto
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.example.test.data.scan.DataWedgeScanner
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Locale

class WarehouseRouteDetailActivity : BaseActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvRouteDriver: TextView
    private lateinit var tvRouteStatus: TextView
    private lateinit var tvRouteType: TextView
    private lateinit var tvReconciliationTitle: TextView
    private lateinit var layoutReconciliation: LinearLayout
    private lateinit var tvRouteDate: TextView
    private lateinit var tvRouteNotes: TextView
    private lateinit var layoutStops: LinearLayout
    private lateinit var tvNoStops: TextView
    private lateinit var btnAddStop: MaterialButton
    private lateinit var layoutItems: LinearLayout
    private lateinit var tvNoItems: TextView
    private lateinit var layoutAddingItem: View
    private lateinit var tvAddingItemLabel: TextView
    private lateinit var btnManualEntry: MaterialButton
    private lateinit var btnFromReceiving: MaterialButton
    private lateinit var btnReviewReturns: MaterialButton
    private lateinit var tvReturnsReviewedBanner: TextView
    private lateinit var securePrefs: SecurePreferences

    private var routeId: Int = -1
    private var detail: RouteDetailDto? = null
    // Fase 115.4 — elegido en showStopTypeChooser() antes de abrir el picker
    // de clientes; "CONSIGNMENT" salta el chequeo de pre-orden (nunca aplica).
    private var pendingStopType: String = "CUSTOMER"
    private lateinit var dwReceiver: BroadcastReceiver
    // Contador en vez de bool — un escaneo rápido seguido puede disparar
    // varios addRouteItem en simultáneo (loading solapado), el aviso se
    // esconde recién cuando termina el último.
    private var pendingItemLoads = 0

    // Fase 112 — si addRouteItem falla por "cero recibido", el modal manda a
    // Recepción con el barcode ya cargado; al volver con éxito se reintenta
    // agregar exactamente lo mismo que había fallado, sin pedirle al
    // almacenista que vuelva a escanear en esta pantalla.
    private var pendingRetryProduct: ProductDto? = null
    private var pendingRetryQuantity: Double = 1.0

    private val receivingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val product = pendingRetryProduct
        if (result.resultCode == Activity.RESULT_OK && product != null) {
            fetchFifoSuggestionThenAdd(product, pendingRetryQuantity)
        }
        pendingRetryProduct = null
    }

    // Fase 115.4 — antes de abrir el picker de clientes, decide si la parada
    // es una visita normal o de consignación (mismo picker de clientes para
    // las dos, solo cambia stopType en checkPreOrderThenAddStop).
    private fun showStopTypeChooser() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_add_stop))
            .setItems(arrayOf(getString(R.string.stop_type_customer), getString(R.string.stop_type_consignment))) { _, which ->
                pendingStopType = if (which == 1) "CONSIGNMENT" else "CUSTOMER"
                customerPickerLauncher.launch(Intent(this, CustomerPickerActivity::class.java))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private val customerPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getStringExtra("customer_id") ?: return@registerForActivityResult
            val name = result.data?.getStringExtra("customer_name") ?: return@registerForActivityResult
            checkPreOrderThenAddStop(id, name)
        }
    }

    // Antes de agregar la parada, chequea si este cliente ya tiene una
    // pre-orden confirmada pendiente y ofrece vincularla (opcional, no
    // bloqueante) — así la parada trae qué llevarle sin obligar a nada.
    // CONSIGNMENT nunca vincula pre-orden — es un concepto distinto (dejar
    // mercadería para vender in situ, no una venta ya armada de antemano).
    private fun checkPreOrderThenAddStop(customerId: String, customerName: String) {
        if (pendingStopType == "CONSIGNMENT") {
            addStop(AddStopRequest(stopType = "CONSIGNMENT", customerId = customerId, customerName = customerName))
            return
        }
        lifecycleScope.launch {
            var matched: AvailablePreOrder? = null
            try {
                val resp = RetrofitClient.getApi().listAvailableStops(null)
                if (resp.isSuccessful) {
                    matched = resp.body()?.preOrders?.firstOrNull { it.customerId == customerId }
                }
            } catch (_: Exception) { }

            val po = matched
            if (po == null) {
                addStop(AddStopRequest(stopType = "CUSTOMER", customerId = customerId, customerName = customerName))
                return@launch
            }
            MaterialAlertDialogBuilder(this@WarehouseRouteDetailActivity)
                .setTitle(getString(R.string.title_link_preorder))
                .setMessage(getString(R.string.msg_link_preorder, customerName, po.itemCount, po.total))
                .setPositiveButton(getString(R.string.btn_link)) { _, _ ->
                    addStop(AddStopRequest(stopType = "CUSTOMER", customerId = customerId, customerName = customerName, preOrderId = po.id))
                }
                .setNegativeButton(getString(R.string.btn_no_thanks)) { _, _ ->
                    addStop(AddStopRequest(stopType = "CUSTOMER", customerId = customerId, customerName = customerName))
                }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_warehouse_route_detail)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        routeId = intent.getIntExtra("route_id", -1)
        if (routeId == -1) { finish(); return }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        toolbar       = findViewById(R.id.toolbar)
        tvRouteDriver = findViewById(R.id.tvRouteDriver)
        tvRouteStatus = findViewById(R.id.tvRouteStatus)
        tvRouteType = findViewById(R.id.tvRouteType)
        tvRouteDate   = findViewById(R.id.tvRouteDate)
        tvRouteNotes  = findViewById(R.id.tvRouteNotes)
        layoutStops  = findViewById(R.id.layoutStops)
        tvNoStops    = findViewById(R.id.tvNoStops)
        btnAddStop   = findViewById(R.id.btnAddStop)
        layoutItems  = findViewById(R.id.layoutItems)
        tvNoItems    = findViewById(R.id.tvNoItems)
        layoutAddingItem = findViewById(R.id.layoutAddingItem)
        tvAddingItemLabel = findViewById(R.id.tvAddingItemLabel)
        btnManualEntry = findViewById(R.id.btnManualEntry)
        btnFromReceiving = findViewById(R.id.btnFromReceiving)
        btnReviewReturns = findViewById(R.id.btnReviewReturns)
        tvReturnsReviewedBanner = findViewById(R.id.tvReturnsReviewedBanner)
        tvReconciliationTitle = findViewById(R.id.tvReconciliationTitle)
        layoutReconciliation = findViewById(R.id.layoutReconciliation)

        toolbar.setNavigationOnClickListener { finish() }
        btnAddStop.setOnClickListener { showStopTypeChooser() }
        btnManualEntry.setOnClickListener { showManualEntryDialog() }
        btnFromReceiving.setOnClickListener { showAvailableProductsPicker() }
        btnReviewReturns.setOnClickListener {
            startActivity(Intent(this, RouteReturnsActivity::class.java).apply {
                putExtra("route_id", routeId)
            })
        }

        dwReceiver = DataWedgeScanner.createReceiver { barcode -> onBarcodeScanned(barcode) }

        loadDetail()
    }

    override fun onResume() {
        super.onResume()
        DataWedgeScanner.register(this, dwReceiver)
        // btnReviewReturns abre RouteReturnsActivity con un startActivity() simple
        // (no un ActivityResultContract) — sin este refresh, volver de guardar la
        // revisión dejaba esta pantalla con el estado viejo (botones todavía
        // habilitados) hasta salir y reentrar a mano.
        loadDetail()
    }

    // CANCELLED sigue bloqueando todo como antes; returnsReviewedAt != null es
    // el caso nuevo — una vez que el almacenista contó lo que volvió del
    // camión, la carga/paradas de esta ruta quedan congeladas (mismo criterio
    // que ya aplica el backend en addRouteItem/removeRouteItem/addStop/etc).
    private fun isLocked(d: RouteDetailDto?): Boolean =
        d != null && (d.status == "CANCELLED" || d.returnsReviewedAt != null)

    override fun onPause() {
        super.onPause()
        DataWedgeScanner.unregister(this, dwReceiver)
    }

    private fun loadDetail() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getRoute(routeId)
                if (resp.isSuccessful) {
                    resp.body()?.data?.let { bind(it) }
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
        loadReconciliation()
    }

    // Fase 115.2 — mismo endpoint que ya usa RouteReturnsActivity para
    // "esperado de vuelta" (getExpectedReturns), desglosado por condición
    // desde esta fase. Acá se usa para el panel de reconciliación del
    // detalle de ruta — informativo, no bloquea nada. Falla en silencio
    // (oculta el panel) si el endpoint no responde, no hace falta un
    // Snackbar aparte para algo que no es crítico para operar la ruta.
    private fun loadReconciliation() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getExpectedReturns(routeId)
                if (resp.isSuccessful) {
                    renderReconciliation(resp.body()?.data ?: emptyList())
                } else {
                    renderReconciliation(emptyList())
                }
            } catch (_: Exception) {
                renderReconciliation(emptyList())
            }
        }
    }

    private fun renderReconciliation(rows: List<RouteReturnExpectedDto>) {
        layoutReconciliation.removeAllViews()
        if (rows.isEmpty()) {
            tvReconciliationTitle.visibility = View.GONE
            layoutReconciliation.visibility = View.GONE
            return
        }
        tvReconciliationTitle.visibility = View.VISIBLE
        layoutReconciliation.visibility = View.VISIBLE

        val reviewed = detail?.returnsReviewedAt != null
        val inflater = LayoutInflater.from(this)
        for (row in rows) {
            val card = inflater.inflate(R.layout.item_reconciliation_row, layoutReconciliation, false)
            card.findViewById<TextView>(R.id.tvReconName).text = row.name
            card.findViewById<TextView>(R.id.tvReconMeta).text = getString(
                R.string.wh_reconciliation_meta,
                row.loadedQty, row.soldQty, row.returnedGoodQty,
                row.returnedDamagedQty, row.returnedExpiredQty, row.returnedTransporterDamageQty
            )
            val flag = reviewed && row.discrepancy != 0.0
            card.findViewById<TextView>(R.id.tvReconDiscrepancy).apply {
                // Fase 118 (fix) — era %+.0f, redondeaba a entero. Con Lbs
                // ahora cargando peso real, un sobrante/faltante chico (ej.
                // 0.3 lb) quedaba oculto como "+0" — mismo %.2f que ya usa
                // wh_reconciliation_meta arriba, para que las dos líneas
                // coincidan en precisión.
                text = String.format(Locale.US, "%+.2f", row.discrepancy)
                setTextColor(getColor(if (flag) R.color.red else R.color.text_secondary))
            }
            layoutReconciliation.addView(card)
        }
    }

    private fun bind(d: RouteDetailDto) {
        detail = d
        toolbar.title = d.name

        val statusLabel = when (d.status) {
            "PLANNED"     -> getString(R.string.route_status_planned)
            "IN_PROGRESS" -> getString(R.string.route_status_in_progress)
            "COMPLETED"   -> getString(R.string.route_status_completed)
            "CANCELLED"   -> getString(R.string.route_status_cancelled)
            else          -> d.status
        }
        val statusColor = when (d.status) {
            "COMPLETED" -> R.color.success
            "IN_PROGRESS" -> R.color.primary
            "CANCELLED" -> R.color.red
            else -> R.color.ex_warning
        }
        val statusBg = when (d.status) {
            "COMPLETED" -> R.drawable.bg_chip_sent
            "CANCELLED" -> R.drawable.bg_chip_failed
            else -> R.drawable.bg_chip_pending
        }
        tvRouteStatus.apply {
            text = statusLabel
            setBackgroundResource(statusBg)
            setTextColor(getColor(statusColor))
        }

        // Fase 115 — solo se marca DIRECT (la excepción); MULTI_STOP es el
        // flujo de siempre, sin chip.
        tvRouteType.apply {
            if (d.routeType == "DIRECT") {
                visibility = View.VISIBLE
                text = getString(R.string.route_type_direct)
                setBackgroundResource(R.drawable.bg_chip_pending)
                setTextColor(getColor(R.color.primary))
            } else {
                visibility = View.GONE
            }
        }

        tvRouteDriver.text = d.driverName ?: getString(R.string.label_no_driver)
        tvRouteDate.text = d.scheduledDate.take(10)

        if (!d.notes.isNullOrBlank()) {
            tvRouteNotes.text = d.notes
            tvRouteNotes.visibility = View.VISIBLE
        } else {
            tvRouteNotes.visibility = View.GONE
        }

        val reviewed = d.returnsReviewedAt != null
        val locked = isLocked(d)
        // Fase 115 — una ruta DIRECT admite un solo destino; con uno ya
        // cargado, el backend rechazaría una 2ª parada (addStop, 400) — se
        // deshabilita acá para no dejar que el almacenista llegue a ese error.
        val directFull = d.routeType == "DIRECT" && d.stops.isNotEmpty()
        tvReturnsReviewedBanner.visibility = if (reviewed) View.VISIBLE else View.GONE
        btnAddStop.isEnabled = !locked && !directFull
        btnManualEntry.isEnabled = !locked
        btnFromReceiving.isEnabled = !locked
        // Fase 112 — revisar devoluciones solo tiene sentido con el camión ya
        // de vuelta (COMPLETED); antes no hay nada físico que contar todavía.
        // Ya revisada: se deja visible pero deshabilitada (en vez de ocultarla)
        // para que quede claro que el paso ya se hizo, no solo que desapareció.
        btnReviewReturns.visibility = if (d.status == "COMPLETED") View.VISIBLE else View.GONE
        btnReviewReturns.isEnabled = !reviewed
        btnReviewReturns.text = if (reviewed)
            getString(R.string.wh_returns_reviewed_badge)
        else
            getString(R.string.wh_btn_review_returns)

        renderStops(d.stops)
        renderItems(d.items)
    }

    private fun renderStops(stops: List<RouteStopDto>) {
        layoutStops.removeAllViews()
        if (stops.isEmpty()) {
            tvNoStops.visibility = View.VISIBLE
            return
        }
        tvNoStops.visibility = View.GONE
        val locked = isLocked(detail)
        val sorted = stops.sortedBy { it.position }
        val inflater = LayoutInflater.from(this)
        for ((i, stop) in sorted.withIndex()) {
            val row = inflater.inflate(R.layout.item_route_stop, layoutStops, false)
            row.findViewById<TextView>(R.id.tvStopCustomer).text = "${i + 1}. ${stop.customerName ?: "—"}"
            val tvDetail = row.findViewById<TextView>(R.id.tvStopDetail)
            val itemsSummary = stop.preOrder?.items?.takeIf { it.isNotEmpty() }?.joinToString("\n") { item ->
                val qty = item.quantity?.let { String.format(Locale.US, "%.2f", it) } ?: "?"
                "• ${item.productName} — $qty ${item.unit ?: ""}".trimEnd()
            }
            when (stop.stopType) {
                "BATCH" -> {
                    val total = stop.batch?.total
                    val label = getString(R.string.wh_order_label)
                    tvDetail.text = if (total != null) "$label · ${String.format(Locale.US, "$%.2f", total)}" else label
                    tvDetail.visibility = View.VISIBLE
                }
                "PRE_ORDER" -> {
                    tvDetail.text = buildString {
                        append(getString(R.string.wh_preorder_label))
                        stop.preOrder?.id?.let { append(" #$it") }
                        itemsSummary?.let { append("\n"); append(it) }
                    }
                    tvDetail.visibility = View.VISIBLE
                }
                "CONSIGNMENT" -> {
                    tvDetail.text = getString(R.string.wh_consignment_label)
                    tvDetail.visibility = View.VISIBLE
                }
                else -> if (stop.preOrder != null) {
                    // CUSTOMER con pre-orden vinculada (opcional, ver checkPreOrderThenAddStop)
                    tvDetail.text = buildString {
                        append(getString(R.string.wh_preorder_label))
                        append(" #${stop.preOrder!!.id}")
                        itemsSummary?.let { append("\n"); append(it) }
                    }
                    tvDetail.visibility = View.VISIBLE
                } else {
                    tvDetail.visibility = View.GONE
                }
            }

            val btnUp = row.findViewById<View>(R.id.btnStopUp)
            val btnDown = row.findViewById<View>(R.id.btnStopDown)
            val btnRemove = row.findViewById<View>(R.id.btnRemoveStop)
            btnUp.isEnabled = !locked && i > 0
            btnUp.alpha = if (btnUp.isEnabled) 1f else 0.3f
            btnDown.isEnabled = !locked && i < sorted.size - 1
            btnDown.alpha = if (btnDown.isEnabled) 1f else 0.3f
            btnRemove.isEnabled = !locked
            btnRemove.alpha = if (!locked) 1f else 0.3f
            btnUp.setOnClickListener { moveStop(sorted, i, -1) }
            btnDown.setOnClickListener { moveStop(sorted, i, 1) }
            btnRemove.setOnClickListener { confirmRemoveStop(stop) }

            layoutStops.addView(row)
        }
    }

    private fun moveStop(sorted: List<RouteStopDto>, index: Int, direction: Int) {
        val target = index + direction
        if (target < 0 || target >= sorted.size) return
        val reordered = sorted.toMutableList()
        val tmp = reordered[index]
        reordered[index] = reordered[target]
        reordered[target] = tmp
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().reorderRouteStops(routeId, ReorderStopsRequest(reordered.map { it.id }))
                if (resp.isSuccessful) loadDetail()
                else Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmRemoveStop(stop: RouteStopDto) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_remove_stop))
            .setMessage(getString(R.string.msg_remove_stop_confirm, stop.customerName ?: "—"))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ -> removeStop(stop) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun removeStop(stop: RouteStopDto) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().removeRouteStop(routeId, stop.id)
                if (resp.isSuccessful) loadDetail()
                else Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderItems(items: List<RouteItemDto>) {
        layoutItems.removeAllViews()
        if (items.isEmpty()) {
            tvNoItems.visibility = View.VISIBLE
            return
        }
        tvNoItems.visibility = View.GONE
        val locked = isLocked(detail)
        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val row = inflater.inflate(R.layout.item_route_item, layoutItems, false)
            row.findViewById<TextView>(R.id.tvItemName).text = item.name
            val metaBase = (item.sku ?: item.barcode ?: "—") + (item.unit?.let { " · $it" } ?: "")
            // Fase 112 — fecha de expiración del lote (o de los lotes, si la
            // línea se partió entre varios) más próxima a vencer, y si algún
            // escaneo de esta línea pisó la sugerencia FIFO a mano.
            val expPart = item.minExpirationDate?.take(10)?.let { getString(R.string.wh_item_expiration_suffix, it) } ?: ""
            val overridePart = if (item.usedOverride == 1) " · ${getString(R.string.wh_override_badge)}" else ""
            row.findViewById<TextView>(R.id.tvItemMeta).text = metaBase + expPart + overridePart
            row.findViewById<TextView>(R.id.tvItemQty).text = com.example.test.data.formatQty(item.quantity)
            val btnRemoveItem = row.findViewById<View>(R.id.btnRemoveItem)
            btnRemoveItem.isEnabled = !locked
            btnRemoveItem.alpha = if (locked) 0.3f else 1f
            btnRemoveItem.setOnClickListener { confirmRemoveItem(item) }
            layoutItems.addView(row)
        }
    }

    // ── Escaneo → cargar producto a la ruta ──

    private fun onBarcodeScanned(barcode: String) {
        val d = detail ?: return
        if (isLocked(d)) return
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

    private fun showQuantityDialog(product: ProductDto) {
        // Decimal solo para Lbs (peso real) — Case/Unit y Bucket son
        // conteos enteros, mismo criterio que ReceivingActivity/
        // ConsignmentActivity (Fase 118 — antes esta pantalla forzaba
        // entero para todo, incluido Lbs, a diferencia del resto del
        // sistema, que ya trata Lbs como peso real desde varias fases).
        val isLbs = com.example.test.data.isLbsUnit(product.unit)
        val density = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
        }
        val etQty = EditText(this).apply {
            inputType = if (isLbs)
                android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            else
                android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
            selectAll()
        }
        // Alternativa a cargar de un lote (Recepción/FIFO): usar directo el
        // stock general del producto — para stock real que nunca pasó por
        // Recepción y por eso no tiene ningún lote que lo respalde.
        val cbUseStock = android.widget.CheckBox(this).apply {
            text = getString(R.string.wh_use_stock_checkbox, product.stock)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * density).toInt()
            }
        }
        layout.addView(etQty)
        layout.addView(cbUseStock)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_load_quantity))
            .setMessage(product.name)
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                // El modal se cierra al toque — el aviso de carga va en la
                // pantalla (junto a "Cargado en el camión"), no acá, para no
                // bloquear al almacenista si viene escaneando varios seguidos.
                val qty = etQty.text.toString().toDoubleOrNull()?.coerceAtLeast(if (isLbs) 0.01 else 1.0) ?: 1.0
                if (cbUseStock.isChecked) {
                    addRouteItem(product, qty, lotId = null, source = "STOCK")
                } else {
                    fetchFifoSuggestionThenAdd(product, qty)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // Fase 112 — antes de cargar de verdad, pregunta al backend qué lote(s)
    // usaría FIFO (vencimiento más próximo primero) y se lo muestra al
    // almacenista para que confirme o elija otro a mano (override, queda
    // registrado). Si la consulta falla o no hay lotes registrados para este
    // producto, se manda igual sin lot_id — el backend decide si hay stock.
    private fun fetchFifoSuggestionThenAdd(product: ProductDto, quantity: Double) {
        lifecycleScope.launch {
            var suggested: List<FifoAllocationDto> = emptyList()
            try {
                val resp = RetrofitClient.getApi().suggestLots(product.id, quantity)
                if (resp.isSuccessful) suggested = resp.body()?.data ?: emptyList()
            } catch (_: Exception) { }

            if (suggested.isEmpty()) {
                addRouteItem(product, quantity, null)
                return@launch
            }
            val summary = suggested.joinToString("\n") { a ->
                val exp = a.expirationDate?.take(10) ?: getString(R.string.wh_no_expiration)
                getString(R.string.wh_lot_summary_line, a.qty, exp)
            }
            MaterialAlertDialogBuilder(this@WarehouseRouteDetailActivity)
                .setTitle(getString(R.string.wh_fifo_suggestion_title))
                .setMessage(summary)
                .setPositiveButton(getString(R.string.btn_confirm)) { _, _ -> addRouteItem(product, quantity, null) }
                .setNeutralButton(getString(R.string.wh_btn_change_lot)) { _, _ -> showLotPicker(product, quantity) }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    private fun showLotPicker(product: ProductDto, quantity: Double) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().listLots(productId = product.id)
                val lots = if (resp.isSuccessful) resp.body()?.data ?: emptyList() else emptyList()
                if (lots.isEmpty()) {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.wh_no_lots_available), Snackbar.LENGTH_SHORT).show()
                    return@launch
                }
                val labels = lots.map { lot ->
                    val exp = lot.expirationDate?.take(10) ?: getString(R.string.wh_no_expiration)
                    getString(R.string.wh_lot_picker_line, exp, lot.remainingQty)
                }.toTypedArray()
                MaterialAlertDialogBuilder(this@WarehouseRouteDetailActivity)
                    .setTitle(getString(R.string.wh_choose_lot_title))
                    .setItems(labels) { _, which -> addRouteItem(product, quantity, lots[which].id) }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    // product.barcode puede ser null (ingreso manual de un producto sin
    // código de barras) — en ese caso se manda product_id, que el backend
    // acepta como alternativa (ver addRouteItem en routeController.ts).
    // lotId != null es el override manual de FIFO (showLotPicker); null deja
    // que el backend elija. source = "STOCK" salta lotes por completo y
    // descuenta products.stock directo (checkbox "Usar stock general" en
    // showQuantityDialog) — lotId se ignora en ese caso.
    private fun addRouteItem(product: ProductDto, quantity: Double, lotId: Int?, source: String? = null) {
        pendingItemLoads++
        tvAddingItemLabel.text = getString(R.string.label_adding_item)
        layoutAddingItem.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val request = if (product.barcode != null)
                    AddRouteItemRequest(barcode = product.barcode, quantity = quantity, lotId = lotId, source = source)
                else
                    AddRouteItemRequest(productId = product.id, quantity = quantity, lotId = lotId, source = source)
                val resp = RetrofitClient.getApi().addRouteItem(routeId, request)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    // El push a QBO ya pasó del lado del backend (recordMovement
                    // sincroniza QtyOnHand al toque) — el mensaje de éxito acá no
                    // depende de qbSynced, es solo informativo por si falla en
                    // silencio (no revierte lo local).
                    val msg = getString(R.string.msg_route_item_added, com.example.test.data.formatQty(quantity), product.name)
                    Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show()
                    // Se pinta al toque con lo que ya devuelve el POST, sin esperar
                    // el refetch — antes dependía solo de loadDetail() acá y el
                    // ítem no aparecía hasta salir y volver a entrar a la ruta.
                    body?.item?.let { applyItemLocally(it) }
                    loadDetail()
                } else if (resp.code() == 409) {
                    handleInsufficientStock(resp.errorBody()?.string(), product, quantity)
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            } finally {
                pendingItemLoads--
                if (pendingItemLoads <= 0) {
                    pendingItemLoads = 0
                    layoutAddingItem.visibility = View.GONE
                }
            }
        }
    }

    // Fase 112 — en vez de mostrar el 409 crudo, distingue "cero recibido"
    // (ofrece ir a Recepción con el barcode precargado y, al volver, reintenta
    // solo) de "hay pero no alcanza" (solo informa cuánto hay).
    private fun handleInsufficientStock(errorBodyRaw: String?, product: ProductDto, quantity: Double) {
        val body = try {
            errorBodyRaw?.let { com.google.gson.Gson().fromJson(it, com.example.test.data.InsufficientStockErrorBody::class.java) }
        } catch (_: Exception) { null }
        val available = body?.available

        if (available != null && available > 0.0) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.wh_not_enough_stock_title))
                .setMessage(getString(R.string.wh_not_enough_stock_message, product.name, available))
                .setPositiveButton(getString(R.string.btn_understood), null)
                .show()
            return
        }

        pendingRetryProduct = product
        pendingRetryQuantity = quantity
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.wh_product_not_received_title))
            .setMessage(getString(R.string.wh_product_not_received_message, product.name))
            .setPositiveButton(getString(R.string.wh_btn_create_product)) { _, _ ->
                receivingLauncher.launch(Intent(this, ReceivingActivity::class.java).apply {
                    putExtra("barcode", product.barcode)
                })
            }
            .setNegativeButton(getString(R.string.btn_close)) { _, _ -> pendingRetryProduct = null }
            .show()
    }

    private fun applyItemLocally(item: RouteItemDto) {
        val d = detail ?: return
        val updatedItems = d.items.filterNot { it.id == item.id } + item
        detail = d.copy(items = updatedItems)
        renderItems(updatedItems)
    }

    private fun removeItemLocally(itemId: Int) {
        val d = detail ?: return
        val updatedItems = d.items.filterNot { it.id == itemId }
        detail = d.copy(items = updatedItems)
        renderItems(updatedItems)
    }

    // Fase 112 — alternativa a escanear caja por caja: lista todo lo que hay
    // recibido y activo en el almacén (GET /lots/available-products, ya viene
    // agrupado por producto con la cantidad total). Tocar un producto pide la
    // cantidad (precargada con lo disponible, editable por si se quiere
    // cargar solo una parte y dejar algo para otra ruta) pero SIN pasar por
    // el modal de sugerencia FIFO — ese paso tiene sentido escaneando a
    // ciegas, es redundante si ya se eligió el producto de esta lista. El
    // backend igual asigna el lote correcto por FIFO puertas adentro.
    private fun showAvailableProductsPicker() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().listAvailableProducts()
                val products = if (resp.isSuccessful) resp.body()?.data ?: emptyList() else emptyList()
                if (products.isEmpty()) {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.wh_no_available_products), Snackbar.LENGTH_SHORT).show()
                    return@launch
                }
                val labels = products.map { p ->
                    getString(R.string.wh_available_product_line, p.name, p.availableQty ?: 0.0)
                }.toTypedArray()
                MaterialAlertDialogBuilder(this@WarehouseRouteDetailActivity)
                    .setTitle(getString(R.string.wh_from_receiving_title))
                    .setItems(labels) { _, which -> showQuantityDialogForAvailable(products[which]) }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showQuantityDialogForAvailable(product: ProductDto) {
        // Fase 118 — mismo criterio que showQuantityDialog(): decimal solo
        // para Lbs, disponible ya viene como peso real si aplica.
        val isLbs = com.example.test.data.isLbsUnit(product.unit)
        val defaultQty = (product.availableQty ?: 1.0).let {
            if (isLbs) it.coerceAtLeast(0.01) else kotlin.math.floor(it).coerceAtLeast(1.0)
        }
        val etQty = EditText(this).apply {
            inputType = if (isLbs)
                android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            else
                android.text.InputType.TYPE_CLASS_NUMBER
            setText(com.example.test.data.formatQty(defaultQty))
            selectAll()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_load_quantity))
            .setMessage(product.name)
            .setView(etQty)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                val qty = etQty.text.toString().toDoubleOrNull()?.coerceAtLeast(if (isLbs) 0.01 else 1.0) ?: defaultQty
                addRouteItem(product, qty, null)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // Mismo diálogo (layout + flujo de búsqueda en vivo) que
    // MainActivity.showManualEntryDialog(), para cuando el producto no tiene
    // código de barras o el TC22 no lo puede leer.
    private fun showManualEntryDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_entry, null)
        val etBarcode = dialogView.findViewById<EditText>(R.id.etBarcode)
        val lvSuggestions = dialogView.findViewById<android.widget.ListView>(R.id.lvSuggestions)

        val adapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvSuggestions.adapter = adapter
        var suggestions: List<ProductDto> = emptyList()

        fun labelFor(p: ProductDto): String {
            val priceStr = String.format(Locale.US, "$%.2f", p.price)
            val skuPart = p.sku?.let { "  ·  $it" } ?: ""
            return if (p.barcode != null) "${p.name}  ·  $priceStr$skuPart" else "${p.name}  ·  $priceStr$skuPart  ·  ${getString(R.string.no_barcode_label)}"
        }

        fun showSuggestions(items: List<ProductDto>) {
            suggestions = items
            adapter.clear()
            adapter.addAll(items.map { labelFor(it) })
            lvSuggestions.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            if (items.isNotEmpty()) lvSuggestions.post { lvSuggestions.setSelection(0) }
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
                    showSuggestions(emptyList())
                    return
                }
                lifecycleScope.launch {
                    try {
                        val resp = RetrofitClient.getApi().searchProducts(query)
                        if (resp.isSuccessful) {
                            showSuggestions(resp.body()?.data ?: emptyList())
                        }
                    } catch (_: Exception) { /* búsqueda silenciosa */ }
                }
            }
        })

        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun confirmRemoveItem(item: RouteItemDto) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_remove_route_item))
            .setMessage(getString(R.string.msg_remove_route_item_confirm, item.name))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ -> removeRouteItem(item) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun removeRouteItem(item: RouteItemDto) {
        // Mismo loader en pantalla que addRouteItem() (contador compartido
        // pendingItemLoads) — antes quitar un producto no mostraba ningún
        // aviso durante el round-trip ni se reflejaba hasta salir y volver a
        // entrar a la ruta.
        pendingItemLoads++
        tvAddingItemLabel.text = getString(R.string.label_removing_item)
        layoutAddingItem.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().removeRouteItem(routeId, item.id)
                if (resp.isSuccessful) {
                    removeItemLocally(item.id)
                    loadDetail()
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            } finally {
                pendingItemLoads--
                if (pendingItemLoads <= 0) {
                    pendingItemLoads = 0
                    layoutAddingItem.visibility = View.GONE
                }
            }
        }
    }

    // ── Agregar parada (cliente) ──
    // Por ahora la parada solo guarda el cliente, sin pedido/pre-orden
    // vinculado (a pedido del usuario — esa relación se agrega en una fase
    // futura). Mismo picker de clientes de QBO que ya usa el resto de la app
    // para vender (CustomerPickerActivity).

    private fun addStop(request: AddStopRequest) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().addRouteStop(routeId, request)
                if (resp.isSuccessful) {
                    loadDetail()
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
    }
}

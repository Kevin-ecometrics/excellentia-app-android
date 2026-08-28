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
import com.example.test.data.ProductDto
import com.example.test.data.ReorderStopsRequest
import com.example.test.data.UpdateStopStatusRequest
import com.example.test.data.RouteDetailDto
import com.example.test.data.RouteItemDto
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
    private lateinit var securePrefs: SecurePreferences

    private var routeId: Int = -1
    private var detail: RouteDetailDto? = null
    private lateinit var dwReceiver: BroadcastReceiver
    // Contador en vez de bool — un escaneo rápido seguido puede disparar
    // varios addRouteItem en simultáneo (loading solapado), el aviso se
    // esconde recién cuando termina el último.
    private var pendingItemLoads = 0

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
    private fun checkPreOrderThenAddStop(customerId: String, customerName: String) {
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

        toolbar.setNavigationOnClickListener { finish() }
        btnAddStop.setOnClickListener {
            customerPickerLauncher.launch(Intent(this, CustomerPickerActivity::class.java))
        }
        btnManualEntry.setOnClickListener { showManualEntryDialog() }

        dwReceiver = DataWedgeScanner.createReceiver { barcode -> onBarcodeScanned(barcode) }

        loadDetail()
    }

    override fun onResume() {
        super.onResume()
        DataWedgeScanner.register(this, dwReceiver)
    }

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

        tvRouteDriver.text = d.driverName ?: getString(R.string.label_no_driver)
        tvRouteDate.text = d.scheduledDate.take(10)

        if (!d.notes.isNullOrBlank()) {
            tvRouteNotes.text = d.notes
            tvRouteNotes.visibility = View.VISIBLE
        } else {
            tvRouteNotes.visibility = View.GONE
        }

        val locked = d.status == "CANCELLED"
        btnAddStop.isEnabled = !locked
        btnManualEntry.isEnabled = !locked

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
        val locked = detail?.status == "CANCELLED"
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
        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val row = inflater.inflate(R.layout.item_route_item, layoutItems, false)
            row.findViewById<TextView>(R.id.tvItemName).text = item.name
            row.findViewById<TextView>(R.id.tvItemMeta).text =
                (item.sku ?: item.barcode ?: "—") + (item.unit?.let { " · $it" } ?: "")
            row.findViewById<TextView>(R.id.tvItemQty).text = item.quantity.toString()
            row.findViewById<View>(R.id.btnRemoveItem).setOnClickListener { confirmRemoveItem(item) }
            layoutItems.addView(row)
        }
    }

    // ── Escaneo → cargar producto a la ruta ──

    private fun onBarcodeScanned(barcode: String) {
        val d = detail ?: return
        if (d.status == "CANCELLED") return
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
        val etQty = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
            selectAll()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_load_quantity))
            .setMessage(product.name)
            .setView(etQty)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                // El modal se cierra al toque — el aviso de carga va en la
                // pantalla (junto a "Cargado en el camión"), no acá, para no
                // bloquear al almacenista si viene escaneando varios seguidos.
                val qty = etQty.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
                addRouteItem(product, qty)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // product.barcode puede ser null (ingreso manual de un producto sin
    // código de barras) — en ese caso se manda product_id, que el backend
    // acepta como alternativa (ver addRouteItem en routeController.ts).
    private fun addRouteItem(product: ProductDto, quantity: Int) {
        pendingItemLoads++
        tvAddingItemLabel.text = getString(R.string.label_adding_item)
        layoutAddingItem.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val request = if (product.barcode != null)
                    AddRouteItemRequest(barcode = product.barcode, quantity = quantity)
                else
                    AddRouteItemRequest(productId = product.id, quantity = quantity)
                val resp = RetrofitClient.getApi().addRouteItem(routeId, request)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    val msg = if (body?.qbSynced == true)
                        getString(R.string.msg_route_item_added, quantity, product.name)
                    else
                        getString(R.string.msg_route_item_added_no_sync)
                    Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show()
                    // Se pinta al toque con lo que ya devuelve el POST, sin esperar
                    // el refetch — antes dependía solo de loadDetail() acá y el
                    // ítem no aparecía hasta salir y volver a entrar a la ruta.
                    body?.item?.let { applyItemLocally(it) }
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

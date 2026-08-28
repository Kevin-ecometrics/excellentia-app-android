package com.example.test

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.RouteDetailDto
import com.example.test.data.RouteItemDto
import com.example.test.data.RouteRequest
import com.example.test.data.RouteStopDto
import com.example.test.data.UpdateStopStatusRequest
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Locale

// Detalle de "mi ruta" para el repartidor (operator) — contraparte de solo
// avance de WarehouseRouteDetailActivity (esa es la del almacenista, arma la
// ruta; esta la ejecuta). Acá el repartidor: avanza el estado de la ruta
// (salir a reparto / terminar) y marca cada parada entregada o saltada. No
// puede agregar/quitar paradas ni tocar el manifiesto de carga.
class MyRouteDetailActivity : BaseActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvRouteDate: TextView
    private lateinit var tvRouteStatus: TextView
    private lateinit var tvRouteNotes: TextView
    private lateinit var btnRouteAction: MaterialButton
    private lateinit var layoutStops: LinearLayout
    private lateinit var tvNoStops: TextView
    private lateinit var layoutItems: LinearLayout
    private lateinit var tvNoItems: TextView
    private lateinit var securePrefs: SecurePreferences

    private var routeId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_my_route_detail)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        routeId = intent.getIntExtra("route_id", -1)
        if (routeId == -1) { finish(); return }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        toolbar        = findViewById(R.id.toolbar)
        tvRouteDate    = findViewById(R.id.tvRouteDate)
        tvRouteStatus  = findViewById(R.id.tvRouteStatus)
        tvRouteNotes   = findViewById(R.id.tvRouteNotes)
        btnRouteAction = findViewById(R.id.btnRouteAction)
        layoutStops    = findViewById(R.id.layoutStops)
        tvNoStops      = findViewById(R.id.tvNoStops)
        layoutItems    = findViewById(R.id.layoutItems)
        tvNoItems      = findViewById(R.id.tvNoItems)

        toolbar.setNavigationOnClickListener { finish() }

        loadDetail()
    }

    override fun onResume() {
        super.onResume()
        loadDetail()
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
        toolbar.title = d.name
        tvRouteDate.text = d.scheduledDate.take(10)

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

        if (!d.notes.isNullOrBlank()) {
            tvRouteNotes.text = d.notes
            tvRouteNotes.visibility = View.VISIBLE
        } else {
            tvRouteNotes.visibility = View.GONE
        }

        when (d.status) {
            "PLANNED" -> {
                btnRouteAction.visibility = View.VISIBLE
                btnRouteAction.text = getString(R.string.btn_start_route)
                btnRouteAction.setOnClickListener { confirmRouteTransition("IN_PROGRESS") }
            }
            "IN_PROGRESS" -> {
                btnRouteAction.visibility = View.VISIBLE
                btnRouteAction.text = getString(R.string.btn_finish_route)
                btnRouteAction.setOnClickListener { confirmRouteTransition("COMPLETED") }
            }
            else -> btnRouteAction.visibility = View.GONE
        }

        renderStops(d.stops, d.status)
        renderItems(d.items)
    }

    private fun confirmRouteTransition(newStatus: String) {
        val title = if (newStatus == "IN_PROGRESS") R.string.title_confirm_start_route else R.string.title_confirm_finish_route
        val msg = if (newStatus == "IN_PROGRESS") R.string.msg_confirm_start_route else R.string.msg_confirm_finish_route
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(title))
            .setMessage(getString(msg))
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ -> updateRouteStatus(newStatus) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun updateRouteStatus(newStatus: String) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().updateRoute(routeId, RouteRequest(status = newStatus))
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

    private fun renderStops(stops: List<RouteStopDto>, routeStatus: String) {
        layoutStops.removeAllViews()
        if (stops.isEmpty()) {
            tvNoStops.visibility = View.VISIBLE
            return
        }
        tvNoStops.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        for ((i, stop) in stops.sortedBy { it.position }.withIndex()) {
            val row = inflater.inflate(R.layout.item_route_stop_operator, layoutStops, false)
            row.findViewById<TextView>(R.id.tvStopCustomer).text = "${i + 1}. ${stop.customerName ?: "—"}"

            val tvDetail = row.findViewById<TextView>(R.id.tvStopDetail)
            // Qué contiene la pre-orden vinculada, en línea acá mismo — antes
            // había que tocar "Abrir pre-orden" para enterarte qué llevar.
            val itemsSummary = stop.preOrder?.items?.takeIf { it.isNotEmpty() }?.joinToString("\n") { item ->
                val qty = item.quantity?.let { String.format(Locale.US, "%.2f", it) } ?: "?"
                "• ${item.productName} — $qty ${item.unit ?: ""}".trimEnd()
            }
            val detailText = when (stop.stopType) {
                "BATCH" -> stop.batch?.total?.let { "${getString(R.string.wh_order_label)} · ${String.format(Locale.US, "$%.2f", it)}" } ?: getString(R.string.wh_order_label)
                "PRE_ORDER" -> buildString {
                    append(getString(R.string.wh_preorder_label))
                    stop.preOrder?.id?.let { append(" #$it") }
                    itemsSummary?.let { append("\n"); append(it) }
                }
                else -> stop.preOrder?.let { po ->
                    buildString {
                        append(getString(R.string.wh_preorder_label))
                        append(" #${po.id}")
                        itemsSummary?.let { append("\n"); append(it) }
                    }
                }
            }
            if (detailText != null) { tvDetail.text = detailText; tvDetail.visibility = View.VISIBLE }
            else tvDetail.visibility = View.GONE

            val tvStatus = row.findViewById<TextView>(R.id.tvStopStatus)
            val layoutActions = row.findViewById<View>(R.id.layoutStopActions)
            val btnAction = row.findViewById<MaterialButton>(R.id.btnStopAction)
            val resolved = stop.status == "DELIVERED" || stop.status == "SKIPPED"
            val canAct = routeStatus == "PLANNED" || routeStatus == "IN_PROGRESS"

            // Si la parada tiene venta asociada (pre-orden o cliente sin
            // nada armado), "Entregado" ya no es un botón manual — se marca
            // solo cuando el pedido se manda de verdad o la pre-orden se
            // convierte (ver activateCustomerAndSell / extras a
            // PreOrderDetailActivity). "Saltar" sigue siendo manual (el
            // cliente no compró). Un pedido BATCH ya está facturado — no hay
            // venta que disparar acá, sigue siendo 100% manual como antes.
            val hasSaleAction = stop.preOrder != null || stop.stopType == "CUSTOMER"
            if (!resolved && canAct) {
                when {
                    stop.preOrder != null -> {
                        btnAction.visibility = View.VISIBLE
                        btnAction.text = getString(R.string.btn_open_preorder)
                        btnAction.setOnClickListener {
                            startActivity(Intent(this, PreOrderDetailActivity::class.java).apply {
                                putExtra("pre_order_id", stop.preOrder!!.id)
                                putExtra("route_id", routeId)
                                putExtra("stop_id", stop.id)
                            })
                        }
                    }
                    stop.stopType == "CUSTOMER" -> {
                        btnAction.visibility = View.VISIBLE
                        btnAction.text = getString(R.string.btn_sell_to_customer)
                        btnAction.setOnClickListener { activateCustomerAndSell(stop.id, stop.customerId, stop.customerName) }
                    }
                    else -> btnAction.visibility = View.GONE
                }
            } else {
                btnAction.visibility = View.GONE
            }

            if (resolved || !canAct) {
                tvStatus.visibility = View.VISIBLE
                layoutActions.visibility = View.GONE
                when (stop.status) {
                    "DELIVERED" -> {
                        tvStatus.text = getString(R.string.stop_status_delivered)
                        tvStatus.setBackgroundResource(R.drawable.bg_chip_sent)
                        tvStatus.setTextColor(getColor(R.color.success))
                    }
                    "SKIPPED" -> {
                        tvStatus.text = getString(R.string.stop_status_skipped)
                        tvStatus.setBackgroundResource(R.drawable.bg_chip_failed)
                        tvStatus.setTextColor(getColor(R.color.red))
                    }
                    else -> {
                        tvStatus.text = getString(R.string.stop_status_pending)
                        tvStatus.setBackgroundResource(R.drawable.bg_chip_pending)
                        tvStatus.setTextColor(getColor(R.color.ex_warning))
                    }
                }
            } else {
                tvStatus.visibility = View.GONE
                layoutActions.visibility = View.VISIBLE
                val btnDelivered = row.findViewById<View>(R.id.btnMarkDelivered)
                btnDelivered.visibility = if (hasSaleAction) View.GONE else View.VISIBLE
                btnDelivered.setOnClickListener { markStop(stop.id, "DELIVERED") }
                row.findViewById<View>(R.id.btnMarkSkipped).setOnClickListener { confirmSkipStop(stop, hasSaleAction) }
            }

            layoutStops.addView(row)
        }
    }

    // route_stops no guarda la dirección del cliente (solo id/nombre) — se
    // consulta a QBO acá mismo, igual que ya hace CustomerPickerActivity al
    // elegirlo, para no perder la dirección en el ticket/factura al vender
    // desde una parada en vez de por el picker normal.
    private fun activateCustomerAndSell(stopId: Int, customerId: String?, customerName: String?) {
        if (customerId == null || customerName == null) return
        lifecycleScope.launch {
            var address: String? = null
            try {
                val resp = RetrofitClient.getApi().getCustomer(customerId)
                if (resp.isSuccessful) address = resp.body()?.fullAddress
            } catch (_: Exception) { }
            securePrefs.setActiveCustomer(customerId, customerName, address)
            // Se marca "Entregado" recién cuando el pedido se manda de
            // verdad — ver CurrentOrderActivity.printFirstTicketThenAskPayment().
            securePrefs.saveActiveRouteStop(routeId, stopId)
            startActivity(Intent(this@MyRouteDetailActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
    }

    // Antes "Saltar" marcaba SKIPPED al toque, sin avisar qué implica — para
    // una parada con venta asociada eso significa que la pre-orden queda sin
    // convertir / el cliente sin nada facturado, así que se explica antes de
    // confirmar.
    private fun confirmSkipStop(stop: RouteStopDto, hasSaleAction: Boolean) {
        val message = if (hasSaleAction)
            getString(R.string.msg_confirm_skip_with_sale, stop.customerName ?: "—")
        else
            getString(R.string.msg_confirm_skip_plain, stop.customerName ?: "—")
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_confirm_skip))
            .setMessage(message)
            .setPositiveButton(getString(R.string.btn_mark_skipped)) { _, _ -> markStop(stop.id, "SKIPPED") }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun markStop(stopId: Int, status: String) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().updateStopStatus(routeId, stopId, UpdateStopStatusRequest(status))
                if (resp.isSuccessful) {
                    // Si esta era la última parada pendiente, el backend ya
                    // cerró la ruta sola (COMPLETED si hubo alguna entrega,
                    // CANCELLED si se saltearon todas) — se avisa acá.
                    when (resp.body()?.routeStatus) {
                        "COMPLETED" -> Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_route_auto_completed), Snackbar.LENGTH_LONG).show()
                        "CANCELLED" -> Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_route_auto_cancelled), Snackbar.LENGTH_LONG).show()
                    }
                    loadDetail()
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
                }
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
            row.findViewById<View>(R.id.btnRemoveItem).visibility = View.GONE
            layoutItems.addView(row)
        }
    }
}

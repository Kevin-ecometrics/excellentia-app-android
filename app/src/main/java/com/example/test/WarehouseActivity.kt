package com.example.test

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.test.data.AddStopRequest
import com.example.test.data.AvailablePreOrder
import com.example.test.data.RouteDto
import com.example.test.data.RouteRequest
import com.example.test.data.UserBrief
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

// Módulo Almacén — "on hand" / manifiesto de carga (ver plan de la Fase de
// warehouse). El almacenista arma acá la ruta de entrega y, en el detalle
// (WarehouseRouteDetailActivity), escanea lo que carga al camión — eso es lo
// que decrementa products.stock y sincroniza QtyOnHand a QBO. La webapp
// (/warehouse) pasa a ser solo lectura + control de emergencia (cancelar
// ruta / reasignar repartidor) sobre lo que se arma acá.
class WarehouseActivity : BaseActivity() {

    private lateinit var layoutRoutes: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var btnDateFilter: MaterialButton
    private lateinit var btnClearDateFilter: TextView
    private lateinit var btnNewRoute: MaterialButton
    private lateinit var btnReceiving: MaterialButton
    private lateinit var btnMovements: MaterialButton
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var securePrefs: SecurePreferences

    private var dateFilter: String? = null
    private var drivers: List<UserBrief> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_warehouse)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.bottomNav)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, b.bottom)
            insets
        }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        layoutRoutes    = findViewById(R.id.layoutRoutes)
        tvEmpty         = findViewById(R.id.tvEmpty)
        swipeRefresh    = findViewById(R.id.swipeRefresh)
        btnDateFilter   = findViewById(R.id.btnDateFilter)
        btnClearDateFilter = findViewById(R.id.btnClearDateFilter)
        btnNewRoute     = findViewById(R.id.btnNewRoute)
        btnReceiving    = findViewById(R.id.btnReceiving)
        btnMovements    = findViewById(R.id.btnMovements)
        bottomNav       = findViewById(R.id.bottomNav)

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_warehouse -> true
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    false
                }
                else -> false
            }
        }

        swipeRefresh.setColorSchemeColors(getColor(R.color.primary))
        swipeRefresh.setOnRefreshListener { loadRoutes() }

        btnDateFilter.setOnClickListener { showDatePicker() }
        btnClearDateFilter.setOnClickListener {
            dateFilter = null
            btnDateFilter.text = getString(R.string.label_select_date)
            btnClearDateFilter.visibility = View.GONE
            loadRoutes()
        }
        btnNewRoute.setOnClickListener { showNewRouteChooser() }
        btnReceiving.setOnClickListener { startActivity(Intent(this, ReceivingActivity::class.java)) }
        btnMovements.setOnClickListener { startActivity(Intent(this, InventoryMovementsActivity::class.java)) }

        loadDrivers()
        loadRoutes()
    }

    override fun onResume() {
        super.onResume()
        bottomNav.selectedItemId = R.id.nav_warehouse
        loadRoutes()
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            dateFilter = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
            btnDateFilter.text = dateFilter
            btnClearDateFilter.visibility = View.VISIBLE
            loadRoutes()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun loadDrivers() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().listSalespersons()
                if (resp.isSuccessful) {
                    // Por ahora se incluye también admin (temporal, a pedido del usuario).
                    drivers = (resp.body()?.data ?: emptyList()).filter { it.role == "operator" || it.role == "admin" }
                }
            } catch (_: Exception) { }
        }
    }

    private fun loadRoutes() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().listRoutes(dateFilter)
                if (resp.isSuccessful) {
                    renderList(resp.body()?.data ?: emptyList())
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun renderList(routes: List<RouteDto>) {
        if (routes.isEmpty()) {
            layoutRoutes.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            return
        }
        layoutRoutes.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        layoutRoutes.removeAllViews()

        val inflater = LayoutInflater.from(this)
        for (route in routes) {
            val row = inflater.inflate(R.layout.item_route, layoutRoutes, false)
            bindRoute(row, route)
            layoutRoutes.addView(row)
        }
    }

    private fun bindRoute(view: View, route: RouteDto) {
        view.findViewById<TextView>(R.id.tvRouteName).text = route.name

        val statusLabel = when (route.status) {
            "PLANNED"     -> getString(R.string.route_status_planned)
            "IN_PROGRESS" -> getString(R.string.route_status_in_progress)
            "COMPLETED"   -> getString(R.string.route_status_completed)
            "CANCELLED"   -> getString(R.string.route_status_cancelled)
            else          -> route.status
        }
        val statusColor = when (route.status) {
            "COMPLETED" -> R.color.success
            "IN_PROGRESS" -> R.color.primary
            "CANCELLED" -> R.color.red
            else -> R.color.ex_warning
        }
        val statusBg = when (route.status) {
            "COMPLETED" -> R.drawable.bg_chip_sent
            "CANCELLED" -> R.drawable.bg_chip_failed
            else -> R.drawable.bg_chip_pending
        }
        view.findViewById<TextView>(R.id.tvRouteStatus).apply {
            text = statusLabel
            setBackgroundResource(statusBg)
            setTextColor(getColor(statusColor))
        }

        val driverLabel = route.driverName ?: getString(R.string.label_no_driver)
        view.findViewById<TextView>(R.id.tvRouteMeta).text =
            "${route.scheduledDate.take(10)}  ·  $driverLabel  ·  ${getString(R.string.label_stops_count, route.stopCount)}"

        // Solo tiene sentido para rutas COMPLETED — antes de eso no hay nada
        // físico que revisar todavía (mismo criterio que btnReviewReturns en
        // WarehouseRouteDetailActivity). Antes de este indicador, una ruta
        // COMPLETED sin revisar era indistinguible de una ya revisada en esta
        // lista — había que entrar a cada una para saberlo.
        val tvReturnsReview = view.findViewById<TextView>(R.id.tvReturnsReviewStatus)
        if (route.status == "COMPLETED") {
            val reviewed = route.returnsReviewedAt != null
            tvReturnsReview.visibility = View.VISIBLE
            tvReturnsReview.text = if (reviewed)
                getString(R.string.wh_returns_reviewed_badge)
            else
                getString(R.string.wh_returns_pending_review_badge)
            tvReturnsReview.setBackgroundResource(if (reviewed) R.drawable.bg_chip_sent else R.drawable.bg_chip_pending)
            tvReturnsReview.setTextColor(getColor(if (reviewed) R.color.success else R.color.ex_warning))
        } else {
            tvReturnsReview.visibility = View.GONE
        }

        view.setOnClickListener {
            startActivity(Intent(this, WarehouseRouteDetailActivity::class.java).apply {
                putExtra("route_id", route.id)
            })
        }
    }

    private fun showNewRouteChooser() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_new_route))
            .setItems(arrayOf(getString(R.string.btn_route_from_scratch), getString(R.string.btn_route_from_preorders))) { _, which ->
                if (which == 1) checkPreOrdersThenPick() else showCreateRouteDialog(emptyList())
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // Elige las pre-órdenes ANTES de pedir fecha/repartidor — así se puede
    // precargar esos dos campos con lo que ya trae la pre-orden en vez de
    // preguntarlos de nuevo (a pedido del usuario). Si no hay ninguna
    // disponible, ni se llega a mostrar el formulario.
    private fun checkPreOrdersThenPick() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().listAvailableStops(null)
                val preOrders = if (resp.isSuccessful) resp.body()?.preOrders ?: emptyList() else emptyList()
                if (preOrders.isEmpty()) {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.label_no_available_stops), Snackbar.LENGTH_SHORT).show()
                    return@launch
                }
                showPreOrderPicker(preOrders)
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPreOrderPicker(preOrders: List<AvailablePreOrder>) {
        val labels = preOrders.map {
            "${it.customerName ?: "—"}  ·  #${it.id}  ·  \$${String.format(Locale.US, "%.2f", it.total)}"
        }.toTypedArray()
        val checked = BooleanArray(preOrders.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.label_available_preorders))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                val selected = preOrders.filterIndexed { i, _ -> checked[i] }
                if (selected.isEmpty()) return@setPositiveButton
                showCreateRouteDialog(selected)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showCreateRouteDialog(preOrders: List<AvailablePreOrder>) {
        val density = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
        }
        val etName = EditText(this).apply { hint = getString(R.string.hint_route_name) }

        // Si viene de pre-órdenes y todas comparten la misma fecha/vendedor
        // asignado, se precarga acá — el usuario igual puede cambiarlo antes
        // de crear la ruta, no queda fijo.
        val commonDate = preOrders.map { it.scheduledDate?.take(10) }.distinct().singleOrNull()
        val commonDriverId = preOrders.map { it.assignedUserId }.distinct().singleOrNull()
            ?.takeIf { id -> drivers.any { it.id == id } }

        var selectedDate: String? = commonDate
        val btnDate = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = commonDate ?: getString(R.string.label_select_date)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * density).toInt()
            }
        }
        var selectedDriverId: Int? = commonDriverId
        val btnDriver = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = commonDriverId?.let { id -> drivers.firstOrNull { it.id == id }?.name } ?: getString(R.string.label_route_driver)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * density).toInt()
            }
        }
        val etNotes = EditText(this).apply {
            hint = getString(R.string.hint_route_notes)
            minLines = 2
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * density).toInt()
            }
        }

        btnDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                selectedDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                btnDate.text = selectedDate
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        btnDriver.setOnClickListener {
            val names = (listOf(getString(R.string.label_no_driver)) + drivers.map { it.name ?: "—" }).toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.label_route_driver))
                .setItems(names) { _, which ->
                    if (which == 0) {
                        selectedDriverId = null
                        btnDriver.text = getString(R.string.label_route_driver)
                    } else {
                        val driver = drivers[which - 1]
                        selectedDriverId = driver.id
                        btnDriver.text = driver.name
                    }
                }
                .show()
        }

        layout.addView(etName)
        layout.addView(btnDate)
        layout.addView(btnDriver)
        layout.addView(etNotes)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_new_route))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_create_route)) { _, _ ->
                val name = etName.text.toString().trim()
                val date = selectedDate
                if (name.isEmpty() || date == null) {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_route_name_date_required), Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                createRoute(name, date, selectedDriverId, etNotes.text.toString().trim().ifEmpty { null }, preOrders)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun createRoute(name: String, date: String, driverId: Int?, notes: String?, preOrders: List<AvailablePreOrder>) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().createRoute(
                    RouteRequest(name = name, scheduledDate = date, driverUserId = driverId, notes = notes)
                )
                if (resp.isSuccessful) {
                    val newRouteId = resp.body()?.id
                    loadRoutes()
                    if (preOrders.isNotEmpty() && newRouteId != null) {
                        addSelectedPreOrders(newRouteId, preOrders)
                    }
                } else {
                    val msg = try {
                        resp.errorBody()?.string()?.let { com.google.gson.Gson().fromJson(it, com.example.test.data.ApiErrorBody::class.java)?.error }
                    } catch (_: Exception) { null }
                    Snackbar.make(findViewById(android.R.id.content), msg ?: getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    // Arma las paradas de la ruta recién creada con las pre-órdenes ya
    // elegidas en showPreOrderPicker (antes de crear la ruta), de una sola vez.
    private fun addSelectedPreOrders(routeId: Int, preOrders: List<AvailablePreOrder>) {
        if (preOrders.isEmpty()) return
        lifecycleScope.launch {
            var added = 0
            for (po in preOrders) {
                try {
                    val resp = RetrofitClient.getApi().addRouteStop(
                        routeId, AddStopRequest(stopType = "PRE_ORDER", preOrderId = po.id)
                    )
                    if (resp.isSuccessful) added++
                } catch (_: Exception) { }
            }
            loadRoutes()
            Snackbar.make(
                findViewById(android.R.id.content),
                getString(R.string.msg_preorders_added, added),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }
}

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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.test.data.RouteDto
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

// Vista del repartidor (operator) — rutas que le asignó el almacenista. Es de
// solo lectura + avanzar estado (WarehouseActivity/WarehouseRouteDetailActivity
// son la contraparte de armado, exclusiva de almacenista). El backend ya
// filtra a driver_user_id = el usuario logueado cuando el rol es operator
// (ver listRoutes en routeController.ts) — acá igual se manda explícito por
// si el usuario es admin actuando de repartidor (rol excepcional, temporal).
class MyRoutesActivity : BaseActivity() {

    private lateinit var layoutRoutes: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var securePrefs: SecurePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_my_routes)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        layoutRoutes = findViewById(R.id.layoutRoutes)
        tvEmpty      = findViewById(R.id.tvEmpty)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        swipeRefresh.setColorSchemeColors(getColor(R.color.primary))
        swipeRefresh.setOnRefreshListener { loadRoutes() }

        loadRoutes()
    }

    override fun onResume() {
        super.onResume()
        loadRoutes()
    }

    private fun loadRoutes() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().listRoutes(driverUserId = securePrefs.getUserId())
                if (resp.isSuccessful) {
                    renderList((resp.body()?.data ?: emptyList()).filter { it.status != "CANCELLED" })
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
            else          -> route.status
        }
        val statusColor = when (route.status) {
            "COMPLETED" -> R.color.success
            "IN_PROGRESS" -> R.color.primary
            else -> R.color.ex_warning
        }
        val statusBg = when (route.status) {
            "COMPLETED" -> R.drawable.bg_chip_sent
            else -> R.drawable.bg_chip_pending
        }
        view.findViewById<TextView>(R.id.tvRouteStatus).apply {
            text = statusLabel
            setBackgroundResource(statusBg)
            setTextColor(getColor(statusColor))
        }

        view.findViewById<TextView>(R.id.tvRouteMeta).text =
            "${route.scheduledDate.take(10)}  ·  ${getString(R.string.label_stops_count, route.stopCount)}"

        view.setOnClickListener {
            startActivity(Intent(this, MyRouteDetailActivity::class.java).apply {
                putExtra("route_id", route.id)
            })
        }
    }
}

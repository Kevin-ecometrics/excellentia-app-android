package com.example.test

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.test.data.PreOrderDto
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PreOrderListActivity : BaseActivity() {

    private lateinit var chipGroup: ChipGroup
    private lateinit var layoutEntries: LinearLayout
    private lateinit var layoutEmpty: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var fabNewPreOrder: ExtendedFloatingActionButton
    private lateinit var securePrefs: SecurePreferences

    // null = activas (DRAFT + CONFIRMED), "ALL" = todas, "DRAFT"/"CONFIRMED"/"CONVERTED" = específico
    private var currentFilter: String? = null

    private val createPreOrderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) loadPreOrders()
    }

    private val detailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) loadPreOrders()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_pre_order_list)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        chipGroup     = findViewById(R.id.chipGroup)
        layoutEntries = findViewById(R.id.layoutEntries)
        layoutEmpty   = findViewById(R.id.layoutEmpty)
        swipeRefresh  = findViewById(R.id.swipeRefresh)
        fabNewPreOrder = findViewById(R.id.fabNewPreOrder)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        swipeRefresh.setColorSchemeColors(getColor(R.color.primary))
        swipeRefresh.setOnRefreshListener { loadPreOrders() }

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when {
                checkedIds.contains(R.id.chipConfirmed)  -> "CONFIRMED"
                checkedIds.contains(R.id.chipConverted)  -> "CONVERTED"
                checkedIds.contains(R.id.chipCancelled)  -> "CANCELLED"
                checkedIds.contains(R.id.chipAll)        -> "ALL"
                else                                     -> null  // Pendientes (DRAFT)
            }
            loadPreOrders()
        }

        fabNewPreOrder.setOnClickListener {
            createPreOrderLauncher.launch(Intent(this, CreatePreOrderActivity::class.java))
        }

        loadPreOrders()
    }

    override fun onResume() {
        super.onResume()
        loadPreOrders()
    }

    private fun loadPreOrders() {
        lifecycleScope.launch {
            try {
                val apiStatus = when (currentFilter) {
                    "CONFIRMED"  -> "CONFIRMED"
                    "CONVERTED"  -> "CONVERTED"
                    "CANCELLED"  -> "CANCELLED"
                    "ALL"        -> null
                    else         -> null   // Pendientes: traemos todo y filtramos DRAFT
                }
                val resp = RetrofitClient.getApi().listPreOrders(status = apiStatus)
                if (resp.isSuccessful) {
                    val all  = resp.body()?.data ?: emptyList()
                    val list = when (currentFilter) {
                        null -> all.filter { it.status == "DRAFT" }  // solo pendientes/borrador
                        else -> all
                    }
                    renderList(list)
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    e.localizedMessage ?: getString(R.string.error_connection),
                    Snackbar.LENGTH_SHORT
                ).show()
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun renderList(list: List<PreOrderDto>) {
        if (list.isEmpty()) {
            layoutEntries.visibility = View.GONE
            layoutEmpty.visibility   = View.VISIBLE
            layoutEmpty.findViewById<TextView>(R.id.tvEmptyLabel)?.text = when (currentFilter) {
                "CONFIRMED"  -> getString(R.string.empty_pre_orders_confirmed)
                "CONVERTED"  -> getString(R.string.empty_pre_orders_converted)
                "CANCELLED"  -> getString(R.string.empty_pre_orders_cancelled)
                "ALL"        -> getString(R.string.empty_pre_orders_all)
                else         -> getString(R.string.empty_pre_orders_pending)
            }
            return
        }
        layoutEntries.visibility = View.VISIBLE
        layoutEmpty.visibility   = View.GONE
        layoutEntries.removeAllViews()

        val inflater = LayoutInflater.from(this)
        for (po in list) {
            val itemView = inflater.inflate(R.layout.item_pre_order, layoutEntries, false)
            bindPreOrder(itemView, po)
            layoutEntries.addView(itemView)
        }
    }

    private fun bindPreOrder(view: View, po: PreOrderDto) {
        view.findViewById<TextView>(R.id.tvPreOrderCustomer).text = po.customerName

        val statusLabel = when (po.status) {
            "DRAFT"     -> getString(R.string.status_draft)
            "CONFIRMED" -> getString(R.string.status_confirmed)
            "CONVERTED" -> getString(R.string.status_converted)
            "CANCELLED" -> getString(R.string.status_cancelled)
            else        -> po.status
        }
        val statusColor = when (po.status) {
            "CONVERTED" -> R.color.success
            "CONFIRMED" -> R.color.primary
            "CANCELLED" -> R.color.red
            else        -> R.color.ex_warning
        }
        val statusBg = when (po.status) {
            "CONVERTED" -> R.drawable.bg_chip_sent
            "CANCELLED" -> R.drawable.bg_chip_failed
            else        -> R.drawable.bg_chip_pending
        }
        view.findViewById<TextView>(R.id.tvPreOrderStatus).apply {
            text = statusLabel
            setBackgroundResource(statusBg)
            setTextColor(ContextCompat.getColor(this@PreOrderListActivity, statusColor))
        }

        val dateStr = po.scheduledDate?.let { getString(R.string.label_delivery_prefix, formatDate(it, "MMM dd, yyyy")) }
            ?: po.createdAt?.let { getString(R.string.label_created_prefix, formatDate(it, "MMM dd, yyyy")) }
            ?: ""
        view.findViewById<TextView>(R.id.tvPreOrderDate).text = dateStr
        view.findViewById<TextView>(R.id.tvPreOrderItems).text = getString(R.string.label_products_count, po.itemCount)

        val tvNotes = view.findViewById<TextView>(R.id.tvPreOrderNotes)
        if (!po.notes.isNullOrBlank()) {
            tvNotes.text = po.notes
            tvNotes.visibility = View.VISIBLE
        } else {
            tvNotes.visibility = View.GONE
        }

        val tvSalesperson = view.findViewById<TextView>(R.id.tvPreOrderSalesperson)
        if (!po.salespersonName.isNullOrBlank()) {
            tvSalesperson.text = "Vendedor: ${po.salespersonName}"
            tvSalesperson.visibility = View.VISIBLE
        } else {
            tvSalesperson.visibility = View.GONE
        }

        // DRAFT/CONFIRMED/CANCELLED no tienen precio real todavía — el detalle
        // (peso/case/precio) se captura recién al convertir (Fase 87). Mostrar
        // "$0.00" ahí se lee como un error; solo CONVERTED tiene un total de verdad
        // persistido en pre_order_items.
        view.findViewById<TextView>(R.id.tvPreOrderTotal).text = if (po.status == "CONVERTED")
            String.format(Locale.US, "$%.2f", po.total)
        else
            getString(R.string.label_pre_order_not_priced)

        view.setOnClickListener {
            detailLauncher.launch(Intent(this, PreOrderDetailActivity::class.java).apply {
                putExtra("pre_order_id", po.id)
            })
        }
    }

    private fun formatDate(raw: String, pattern: String): String {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd"
        )
        val display = SimpleDateFormat(pattern, Locale.US)
        for (fmt in formats) {
            try {
                val parser = SimpleDateFormat(fmt, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                return display.format(parser.parse(raw)!!)
            } catch (_: Exception) {}
        }
        return raw
    }
}

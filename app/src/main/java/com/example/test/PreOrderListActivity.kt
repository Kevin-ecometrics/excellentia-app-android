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
import androidx.appcompat.app.AppCompatActivity
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

class PreOrderListActivity : AppCompatActivity() {

    private lateinit var chipGroup: ChipGroup
    private lateinit var layoutEntries: LinearLayout
    private lateinit var layoutEmpty: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var fabNewPreOrder: ExtendedFloatingActionButton
    private lateinit var securePrefs: SecurePreferences

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
                checkedIds.contains(R.id.chipDraft)     -> "DRAFT"
                checkedIds.contains(R.id.chipConfirmed) -> "CONFIRMED"
                checkedIds.contains(R.id.chipConverted) -> "CONVERTED"
                else                                    -> null
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
                val resp = RetrofitClient.getApi().listPreOrders(status = currentFilter)
                if (resp.isSuccessful) {
                    val list = resp.body()?.data ?: emptyList()
                    renderList(list)
                } else {
                    Snackbar.make(findViewById(android.R.id.content), "Error ${resp.code()}", Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    e.localizedMessage ?: "Error de conexión",
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
            "DRAFT"     -> "BORRADOR"
            "CONFIRMED" -> "CONFIRMADA"
            "CONVERTED" -> "CONVERTIDA"
            "CANCELLED" -> "CANCELADA"
            else        -> po.status
        }
        val statusColor = when (po.status) {
            "CONVERTED" -> R.color.success
            "CONFIRMED" -> R.color.primary
            "CANCELLED" -> R.color.red
            else        -> R.color.warning
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

        val dateStr = po.scheduledDate?.let { "Entrega: $it" }
            ?: po.createdAt?.let {
                try {
                    val p = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    val d = SimpleDateFormat("dd MMM yyyy", Locale.US)
                    "Creado: ${d.format(p.parse(it)!!)}"
                } catch (_: Exception) { "" }
            } ?: ""
        view.findViewById<TextView>(R.id.tvPreOrderDate).text = dateStr
        view.findViewById<TextView>(R.id.tvPreOrderItems).text = "${po.itemCount} producto(s)"

        val tvNotes = view.findViewById<TextView>(R.id.tvPreOrderNotes)
        if (!po.notes.isNullOrBlank()) {
            tvNotes.text = po.notes
            tvNotes.visibility = View.VISIBLE
        } else {
            tvNotes.visibility = View.GONE
        }

        view.findViewById<TextView>(R.id.tvPreOrderTotal).text =
            String.format(Locale.US, "$%.2f", po.total)

        view.setOnClickListener {
            detailLauncher.launch(Intent(this, PreOrderDetailActivity::class.java).apply {
                putExtra("pre_order_id", po.id)
            })
        }
    }
}

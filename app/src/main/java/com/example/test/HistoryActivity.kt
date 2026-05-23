package com.example.test

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.appbar.MaterialToolbar
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.BatchItem
import com.example.test.data.OrderDto
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.local.entities.PendingOrderEntity
import com.example.test.data.repository.OrderRepository
import com.example.test.data.ScanEntry
import com.example.test.data.SyncStatus
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.ChipGroup
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class HistoryActivity : AppCompatActivity() {

    private lateinit var chipGroupDate: ChipGroup
    private lateinit var chipGroup: ChipGroup
    private lateinit var layoutEntries: LinearLayout
    private lateinit var layoutEmpty: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var btnLoadMore: com.google.android.material.button.MaterialButton
    private lateinit var orderRepository: OrderRepository
    private var currentFilter = "ALL"
    private var currentDateFilter = "TODAY"
    private var currentPage = 1
    private var hasMorePages = false
    private val pageSize = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_history)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        val db = AppDatabase.getInstance(this)
        val securePrefs = SecurePreferences(this)
        orderRepository = OrderRepository(db, securePrefs)

        chipGroupDate = findViewById(R.id.chipGroupDate)
        chipGroup = findViewById(R.id.chipGroup)
        layoutEntries = findViewById(R.id.layoutEntries)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        btnLoadMore = findViewById(R.id.btnLoadMore)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeColors(getColor(R.color.primary))
        swipeRefresh.setOnRefreshListener { loadHistory() }

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        chipGroupDate.setOnCheckedStateChangeListener { _, checkedIds ->
            currentDateFilter = when {
                checkedIds.contains(R.id.chipAllDates) -> "ALL"
                else -> "TODAY"
            }
            loadHistory()
        }

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when {
                checkedIds.contains(R.id.chipSent)    -> "SENT"
                checkedIds.contains(R.id.chipPending) -> "PENDING"
                checkedIds.contains(R.id.chipFailed)  -> "FAILED"
                else -> "ALL"
            }
            currentPage = 1
            loadHistory()
        }

        btnLoadMore.setOnClickListener {
            currentPage++
            loadHistory(append = true)
        }

        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private fun loadHistory(append: Boolean = false) {
        if (!append) { currentPage = 1; btnLoadMore.visibility = android.view.View.GONE }
        lifecycleScope.launch {
            try {
                val localAll = orderRepository.getPendingOrders()
                val remoteResult = orderRepository.getRemoteOrders(page = currentPage, limit = pageSize)

                val allRemote = mutableListOf<OrderDto>()
                remoteResult.onSuccess { resp ->
                    allRemote.addAll(resp.data ?: emptyList())
                    val total = resp.meta?.total ?: 0
                    hasMorePages = (currentPage * pageSize) < total
                }

                // Aplicar filtro de fecha
                val localFiltered = if (currentDateFilter == "TODAY")
                    localAll.filter { isToday(it.createdAt) }
                else
                    localAll

                val allBatches = allRemote.groupBy { it.batchId ?: "batch_${it.id}" }
                val batchesFiltered = if (currentDateFilter == "TODAY")
                    allBatches.filter { (_, orders) ->
                        orders.any { it.createdAt?.let { d -> isTodayFromIso(d) } == true }
                    }
                else
                    allBatches

                if (!append) layoutEntries.removeAllViews()
                val inflater = LayoutInflater.from(this@HistoryActivity)

                // Órdenes locales
                if (currentFilter != "SENT") {
                    for (order in localFiltered) {
                        val isFailed = order.retryCount == -1
                        if (currentFilter == "PENDING" && isFailed)  continue
                        if (currentFilter == "FAILED"  && !isFailed) continue

                        val itemView = inflater.inflate(R.layout.item_scan_entry, layoutEntries, false)
                        bindEntry(itemView, ScanEntry(
                            barcode = order.barcode,
                            productName = order.productName,
                            price = order.price,
                            quantity = order.quantity,
                            timestamp = order.createdAt,
                            status = if (isFailed) SyncStatus.FAILED else SyncStatus.PENDING
                        ))
                        bindRetry(itemView, order)
                        layoutEntries.addView(itemView)
                    }
                }

                // Batches remotos
                for ((batchId, orders) in batchesFiltered) {
                    val allSent   = orders.all { it.status == "SENT" }
                    val hasFailed = orders.any { it.status == "FAILED" }
                    if (currentFilter == "PENDING" && allSent)   continue
                    if (currentFilter == "SENT"    && !allSent)  continue
                    if (currentFilter == "FAILED"  && !hasFailed) continue

                    val headerView = inflater.inflate(R.layout.item_batch_header, layoutEntries, false)
                    bindBatchHeader(headerView, batchId, orders, orders.firstOrNull()?.qbInvoiceId)
                    layoutEntries.addView(headerView)
                }

                // Mostrar empty state si no hay nada visible tras aplicar filtros
                if (!append && layoutEntries.childCount == 0) {
                    layoutEntries.visibility = View.GONE
                    layoutEmpty.visibility   = View.VISIBLE
                    btnLoadMore.visibility   = View.GONE
                    val tvEmpty = layoutEmpty.findViewById<android.widget.TextView>(R.id.tvEmptyMessage)
                    tvEmpty?.text = when (currentFilter) {
                        "SENT"    -> "Sin pedidos enviados"
                        "PENDING" -> "Sin pedidos pendientes"
                        "FAILED"  -> "Sin pedidos fallidos"
                        else      -> "Sin pedidos registrados"
                    }
                } else {
                    layoutEntries.visibility = View.VISIBLE
                    layoutEmpty.visibility   = View.GONE
                    btnLoadMore.visibility   = if (hasMorePages) View.VISIBLE else View.GONE
                }
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun isToday(timestampMillis: Long): Boolean {
        val today = Calendar.getInstance()
        val target = Calendar.getInstance().also { it.timeInMillis = timestampMillis }
        return today.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
               today.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    }

    private fun isTodayFromIso(isoDate: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            isToday(sdf.parse(isoDate)!!.time)
        } catch (_: Exception) { false }
    }

    private fun bindEntry(view: View, entry: ScanEntry) {
        view.findViewById<TextView>(R.id.tvEntryName).text = entry.productName
        view.findViewById<TextView>(R.id.tvEntryBarcode).text = entry.barcode
        view.findViewById<TextView>(R.id.tvEntryDate).text =
            "${entry.formattedDate}  ${entry.formattedTime}"
        view.findViewById<TextView>(R.id.tvEntryTotal).text =
            String.format(Locale.US, "$%.2f", entry.price * entry.quantity)
        view.findViewById<TextView>(R.id.tvEntryQty).text = "${entry.quantity} lb"

        val tvStatus = view.findViewById<TextView>(R.id.tvEntryStatus)
        when (entry.status) {
            SyncStatus.SENT -> {
                tvStatus.text = "ENVIADO"
                tvStatus.setBackgroundResource(R.drawable.bg_chip_sent)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            }
            SyncStatus.PENDING -> {
                tvStatus.text = "PENDIENTE"
                tvStatus.setBackgroundResource(R.drawable.bg_chip_pending)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.warning))
            }
            SyncStatus.FAILED -> {
                tvStatus.text = "FALLIDO"
                tvStatus.setBackgroundResource(R.drawable.bg_chip_failed)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
            }
        }
    }

    private fun bindRetry(view: View, order: PendingOrderEntity) {
        val btn = view.findViewById<MaterialButton>(R.id.btnRetryEntry)
        btn.visibility = View.VISIBLE
        btn.text = if (order.retryCount == -1) "Reintentar envío" else "Enviar ahora"
        btn.setOnClickListener {
            btn.isEnabled = false
            btn.text = "Enviando..."
            lifecycleScope.launch {
                val item = BatchItem(
                    barcode = order.barcode,
                    productName = order.productName,
                    price = order.price,
                    quantity = order.quantity,
                    total = order.price * order.quantity
                )
                val result = orderRepository.sendBatch(
                    items = listOf(item),
                    customerId = order.customerId,
                    customerName = order.customerName
                )
                result.onSuccess {
                    orderRepository.deletePendingOrder(order.id)
                    loadHistory()
                }
                result.onFailure { e ->
                    btn.isEnabled = true
                    btn.text = if (order.retryCount == -1) "Reintentar envío" else "Enviar ahora"
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        e.localizedMessage ?: "Error de conexión",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun bindBatchHeader(view: View, batchId: String, orders: List<OrderDto>, invoiceId: String?) {
        view.findViewById<TextView>(R.id.tvBatchId).text =
            "Pedido #${batchId.takeLast(6)}  ·  ${orders.size} producto(s)"

        val dateStr = orders.firstOrNull()?.createdAt?.let {
            try {
                val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                val display = java.text.SimpleDateFormat("dd MMM yyyy  HH:mm", java.util.Locale.US)
                display.format(parser.parse(it)!!)
            } catch (_: Exception) { "" }
        } ?: ""
        view.findViewById<TextView>(R.id.tvBatchDate).text = dateStr

        val customerName = orders.firstOrNull()?.customerName
        val tvCustomer = view.findViewById<TextView>(R.id.tvBatchCustomer)
        if (!customerName.isNullOrBlank()) {
            tvCustomer.visibility = View.VISIBLE
            tvCustomer.text = customerName
        } else {
            tvCustomer.visibility = View.GONE
        }

        val total = orders.sumOf { it.total }
        view.findViewById<TextView>(R.id.tvBatchTotal).text =
            String.format(Locale.US, "$%.2f", total)

        val allSent = orders.all { it.status == "SENT" }
        view.findViewById<TextView>(R.id.tvBatchStatus).apply {
            text = if (allSent) "COMPLETADO" else "PENDIENTE"
            setBackgroundResource(if (allSent) R.drawable.bg_chip_sent else R.drawable.bg_chip_pending)
            setTextColor(ContextCompat.getColor(this@HistoryActivity, if (allSent) R.color.success else R.color.warning))
        }

        view.setOnClickListener {
            startActivity(Intent(this, TicketDetailActivity::class.java).apply {
                putExtra("batch_id", batchId)
                putExtra("invoice_id", invoiceId ?: "")
                putExtra("orders_json", com.google.gson.Gson().toJson(orders))
                putExtra("customer_name", customerName ?: "")
            })
        }
    }
}

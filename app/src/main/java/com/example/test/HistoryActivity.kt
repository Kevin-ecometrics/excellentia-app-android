package com.example.test

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.OrderDto
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.repository.OrderRepository
import com.example.test.data.ScanEntry
import com.example.test.data.SyncStatus
import com.google.android.material.chip.ChipGroup
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var chipGroup: ChipGroup
    private lateinit var layoutEntries: LinearLayout
    private lateinit var layoutEmpty: View
    private lateinit var orderRepository: OrderRepository
    private var currentFilter = "ALL"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_history)
        val pad = resources.getDimensionPixelSize(R.dimen.padding_screen)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad)
            insets
        }

        val db = AppDatabase.getInstance(this)
        val securePrefs = SecurePreferences(this)
        orderRepository = OrderRepository(db, securePrefs)

        chipGroup = findViewById(R.id.chipGroup)
        layoutEntries = findViewById(R.id.layoutEntries)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when {
                checkedIds.contains(R.id.chipSent) -> "SENT"
                checkedIds.contains(R.id.chipPending) -> "PENDING"
                else -> "ALL"
            }
            loadHistory()
        }

        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private var lastOrders: List<OrderDto> = emptyList()

    private fun loadHistory() {
        lifecycleScope.launch {
            val localPending = orderRepository.getPendingOrders()
            val remoteResult = orderRepository.getRemoteOrders()

            // Group remote orders by batch_id
            val allRemote = mutableListOf<OrderDto>()
            remoteResult.onSuccess { allRemote.addAll(it.data ?: emptyList()) }

            val batches = allRemote.groupBy { it.batchId ?: "batch_${it.id}" }

            layoutEntries.removeAllViews()

            if (localPending.isEmpty() && batches.isEmpty()) {
                layoutEntries.visibility = View.GONE
                layoutEmpty.visibility = View.VISIBLE
                return@launch
            }

            layoutEntries.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE

            val inflater = LayoutInflater.from(this@HistoryActivity)

            // Local pending orders (without batch)
            if (currentFilter != "SENT") {
                for (order in localPending) {
                    val itemView = inflater.inflate(R.layout.item_scan_entry, layoutEntries, false)
                    bindEntry(itemView, ScanEntry(
                        barcode = order.barcode,
                        productName = order.productName,
                        price = order.price,
                        quantity = order.quantity,
                        timestamp = order.createdAt,
                        status = SyncStatus.PENDING
                    ))
                    layoutEntries.addView(itemView)
                }
            }

            // Remote orders grouped by batch
            for ((batchId, orders) in batches) {
                val allSent = orders.all { it.status == "SENT" }
                if (currentFilter == "PENDING" && allSent) continue
                if (currentFilter == "SENT" && !allSent) continue

                // Card del batch — click abre TicketDetailActivity con ítems individuales
                val headerView = inflater.inflate(R.layout.item_batch_header, layoutEntries, false)
                bindBatchHeader(headerView, batchId, orders, orders.firstOrNull()?.qbInvoiceId)
                layoutEntries.addView(headerView)
            }
        }
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
        }
    }

    private fun bindBatchHeader(view: View, batchId: String, orders: List<OrderDto>, invoiceId: String?) {
        view.findViewById<TextView>(R.id.tvBatchId).text =
            "Pedido #${batchId.takeLast(6)}  ·  ${orders.size} producto(s)"

        val dateStr = orders.firstOrNull()?.createdAt?.let {
            try {
                val sdf = java.text.SimpleDateFormat("dd MMM yyyy  HH:mm", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf.format(
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .parse(it)!!
                )
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

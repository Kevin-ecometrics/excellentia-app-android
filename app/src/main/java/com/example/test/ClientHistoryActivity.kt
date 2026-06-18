package com.example.test

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge

import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.test.data.CustomerBatchSummary
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ClientHistoryActivity : BaseActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvCustomerHeader: TextView
    private lateinit var tvSummary: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutEntries: LinearLayout
    private lateinit var layoutEmpty: View
    private lateinit var btnLoadMore: MaterialButton
    private lateinit var securePrefs: SecurePreferences

    private var customerId = ""
    private var customerName = ""
    private var currentPage = 1
    private val pageSize = 20
    private var hasMorePages = false
    private val allBatches = mutableListOf<CustomerBatchSummary>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_client_history)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        customerId   = intent.getStringExtra("customer_id")   ?: ""
        customerName = intent.getStringExtra("customer_name") ?: getString(R.string.default_customer_name)

        toolbar            = findViewById(R.id.toolbar)
        tvCustomerHeader   = findViewById(R.id.tvCustomerHeader)
        tvSummary          = findViewById(R.id.tvSummary)
        swipeRefresh       = findViewById(R.id.swipeRefresh)
        layoutEntries      = findViewById(R.id.layoutEntries)
        layoutEmpty        = findViewById(R.id.layoutEmpty)
        btnLoadMore        = findViewById(R.id.btnLoadMore)

        toolbar.title = customerName
        toolbar.setNavigationOnClickListener { finish() }
        tvCustomerHeader.text = customerName

        swipeRefresh.setColorSchemeColors(getColor(R.color.primary))
        swipeRefresh.setOnRefreshListener { refresh() }

        btnLoadMore.setOnClickListener {
            currentPage++
            loadPage(append = true)
        }

        refresh()
    }

    private fun refresh() {
        currentPage = 1
        allBatches.clear()
        loadPage(append = false)
    }

    private fun loadPage(append: Boolean) {
        if (!append) btnLoadMore.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getCustomerOrders(
                    customerId = customerId,
                    page = currentPage,
                    limit = pageSize
                )
                if (resp.isSuccessful) {
                    val body = resp.body()
                    val newBatches = body?.data ?: emptyList()
                    val meta = body?.meta

                    if (!append) allBatches.clear()
                    allBatches.addAll(newBatches)
                    hasMorePages = (meta?.let { currentPage * pageSize < it.total } ?: false)

                    updateSummary()
                    renderBatches()
                    btnLoadMore.visibility = if (hasMorePages) View.VISIBLE else View.GONE
                } else {
                    showError(getString(R.string.msg_server_error, resp.code().toString()))
                }
            } catch (e: Exception) {
                showError(e.localizedMessage ?: getString(R.string.error_no_connection))
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun updateSummary() {
        val total = allBatches.sumOf { it.total }
        tvSummary.text = getString(R.string.summary_orders_total, allBatches.size, String.format(Locale.US, "%.2f", total))
    }

    private fun renderBatches() {
        if (allBatches.isEmpty()) {
            layoutEntries.visibility = View.GONE
            layoutEmpty.visibility   = View.VISIBLE
            return
        }
        layoutEntries.visibility = View.VISIBLE
        layoutEmpty.visibility   = View.GONE

        layoutEntries.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (batch in allBatches) {
            val card = inflater.inflate(R.layout.item_batch_header, layoutEntries, false)
            bindBatch(card, batch)
            layoutEntries.addView(card)
        }
    }

    private fun bindBatch(view: View, batch: CustomerBatchSummary) {
        view.findViewById<TextView>(R.id.tvBatchId).text =
            getString(R.string.batch_summary, batch.batchId.takeLast(6), batch.itemCount)

        val dateStr = batch.createdAt?.let {
            try {
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                val display = SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.US)
                display.format(parser.parse(it)!!)
            } catch (_: Exception) { "" }
        } ?: ""
        view.findViewById<TextView>(R.id.tvBatchDate).text = dateStr

        view.findViewById<TextView>(R.id.tvBatchCustomer).visibility = View.GONE

        view.findViewById<TextView>(R.id.tvBatchTotal).text =
            String.format(Locale.US, "$%.2f", batch.total)

        val isSent = batch.status == "SENT"
        view.findViewById<TextView>(R.id.tvBatchStatus).apply {
            text = if (isSent) getString(R.string.label_completed) else getString(R.string.label_pending_status)
            setBackgroundResource(if (isSent) R.drawable.bg_chip_sent else R.drawable.bg_chip_pending)
            setTextColor(ContextCompat.getColor(this@ClientHistoryActivity, if (isSent) R.color.success else R.color.warning))
        }

        view.setOnClickListener {
            loadBatchDetail(batch.batchId, batch.qbInvoiceId, batch.customerName)
        }
    }

    private fun loadBatchDetail(batchId: String, invoiceId: String?, customerName: String) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().listOrders(
                    customerId = customerId,
                    limit = 100
                )
                if (resp.isSuccessful) {
                    val orders = resp.body()?.data
                        ?.filter { it.batchId == batchId }
                        ?: emptyList()
                    startActivity(Intent(this@ClientHistoryActivity, TicketDetailActivity::class.java).apply {
                        putExtra("batch_id", batchId)
                        putExtra("invoice_id", invoiceId ?: "")
                        putExtra("orders_json", Gson().toJson(orders))
                        putExtra("customer_name", customerName)
                    })
                }
            } catch (_: Exception) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.error_load_batch_detail),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showError(msg: String) {
        swipeRefresh.isRefreshing = false
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show()
    }
}

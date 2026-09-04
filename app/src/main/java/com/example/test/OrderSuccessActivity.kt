package com.example.test

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.test.data.DamageItem
import com.example.test.data.OrderDto
import com.example.test.data.creditsTotalOf
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale

class OrderSuccessActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_success)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        val batchId          = intent.getStringExtra("batch_id") ?: ""
        val invoiceId        = intent.getStringExtra("invoice_id") ?: ""
        val invoiceNumber    = intent.getIntExtra("invoice_number", 0).takeIf { it > 0 }
        val isOfflinePending = intent.getBooleanExtra("offline_pending", false)
        val customerName    = intent.getStringExtra("customer_name")
        val customerAddress = intent.getStringExtra("customer_address")
        val signature          = intent.getStringExtra("signature")
        val damageItemsJson    = intent.getStringExtra("damage_items_json")
        val total      = intent.getDoubleExtra("total", 0.0)
        val itemCount  = intent.getIntExtra("item_count", 0)
        val ordersJson = intent.getStringExtra("orders_json") ?: "[]"
        val creditsTotalExtra = intent.getDoubleExtra("credits_total", -1.0).takeIf { it >= 0.0 }
        val creditApplied     = intent.getDoubleExtra("credit_applied", 0.0)
        val damageItems: List<DamageItem> = try {
            val type = object : TypeToken<List<DamageItem>>() {}.type
            Gson().fromJson(damageItemsJson ?: "[]", type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
        val credits = creditsTotalOf(damageItems, authoritative = creditsTotalExtra)

        findViewById<View>(R.id.layoutPendingSync).visibility =
            if (isOfflinePending) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.tvSuccessBatch).text =
            if (batchId.isNotBlank()) "#$batchId" else "—"

        val invoiceDisplay = invoiceNumber?.toString() ?: invoiceId.takeIf { it.isNotBlank() }
        findViewById<TextView>(R.id.tvSuccessInvoice).text =
            if (invoiceDisplay != null) "#$invoiceDisplay" else "—"

        val rowCustomer  = findViewById<View>(R.id.rowCustomer)
        val divCustomer  = findViewById<View>(R.id.dividerCustomer)
        val tvCustomer   = findViewById<TextView>(R.id.tvSuccessCustomer)
        if (!customerName.isNullOrBlank()) {
            rowCustomer.visibility = View.VISIBLE
            divCustomer.visibility = View.VISIBLE
            tvCustomer.text = customerName
        }

        findViewById<TextView>(R.id.tvSuccessItems).text =
            getString(R.string.label_products_count, itemCount)

        val orders: List<OrderDto> = try {
            val type = object : TypeToken<List<OrderDto>>() {}.type
            Gson().fromJson(ordersJson, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
        val paymentMethod = orders.firstOrNull()?.paymentMethod
        if (!paymentMethod.isNullOrBlank()) {
            findViewById<View>(R.id.rowPayment).visibility = View.VISIBLE
            findViewById<View>(R.id.dividerPayment).visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvSuccessPayment).text = paymentMethod
        }

        val totalCredits = credits + creditApplied
        if (totalCredits > 0) {
            findViewById<View>(R.id.rowCredits).visibility = View.VISIBLE
            findViewById<View>(R.id.dividerCredits).visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvSuccessCredits).text =
                String.format(Locale.US, "-$%.2f", totalCredits)
        }

        // Fase 115.5 — mismo criterio que Credits: se resta del total mostrado
        // acá (necesita `orders`, recién parseado arriba, para saber qué
        // ítems son cortesía).
        val courtesyTotal = orders.filter { it.isCourtesy }.sumOf { it.total }
        if (courtesyTotal > 0) {
            findViewById<View>(R.id.rowCourtesy).visibility = View.VISIBLE
            findViewById<View>(R.id.dividerCourtesy).visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvSuccessCourtesy).text =
                String.format(Locale.US, "-$%.2f", courtesyTotal)
        }
        val displayTotal = total - courtesyTotal - credits - creditApplied

        findViewById<TextView>(R.id.tvSuccessTotal).text =
            String.format(Locale.US, "$%.2f", displayTotal)

        findViewById<MaterialButton>(R.id.btnViewTicket).setOnClickListener {
            val intent = Intent(this, TicketDetailActivity::class.java).apply {
                putExtra("batch_id", batchId)
                putExtra("invoice_id", invoiceId)
                putExtra("invoice_number", invoiceNumber ?: 0)
                putExtra("orders_json", ordersJson)
                putExtra("customer_name", customerName)
                putExtra("customer_address", customerAddress)
                putExtra("signature", signature)
                putExtra("damage_items_json", damageItemsJson)
            }
            startActivity(intent)
        }

        findViewById<MaterialButton>(R.id.btnNewOrder).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }
}

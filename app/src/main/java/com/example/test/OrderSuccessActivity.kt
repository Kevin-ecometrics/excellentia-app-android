package com.example.test

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.test.data.OrderDto
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

        val batchId         = intent.getStringExtra("batch_id") ?: ""
        val invoiceId       = intent.getStringExtra("invoice_id") ?: ""
        val isOfflinePending = intent.getBooleanExtra("offline_pending", false)
        val customerName    = intent.getStringExtra("customer_name")
        val customerAddress = intent.getStringExtra("customer_address")
        val signature          = intent.getStringExtra("signature")
        val damageItemsJson    = intent.getStringExtra("damage_items_json")
        val total      = intent.getDoubleExtra("total", 0.0)
        val itemCount  = intent.getIntExtra("item_count", 0)
        val ordersJson = intent.getStringExtra("orders_json") ?: "[]"

        if (isOfflinePending) {
            com.google.android.material.snackbar.Snackbar.make(
                findViewById(R.id.main),
                getString(R.string.msg_order_saved_offline),
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            ).show()
        }

        findViewById<TextView>(R.id.tvSuccessBatch).text =
            if (batchId.isNotBlank()) "#$batchId" else "—"

        findViewById<TextView>(R.id.tvSuccessInvoice).text =
            if (invoiceId.isNotBlank()) invoiceId else "—"

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

        findViewById<TextView>(R.id.tvSuccessTotal).text =
            String.format(Locale.US, "$%.2f", total)

        findViewById<MaterialButton>(R.id.btnViewTicket).setOnClickListener {
            val intent = Intent(this, TicketDetailActivity::class.java).apply {
                putExtra("batch_id", batchId)
                putExtra("invoice_id", invoiceId)
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

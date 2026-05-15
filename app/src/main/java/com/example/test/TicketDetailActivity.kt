package com.example.test

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.BatchItem
import com.example.test.data.OrderDto
import com.example.test.data.local.SecurePreferences
import com.example.test.data.print.PrintService
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TicketDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_ticket_detail)
        val pad = resources.getDimensionPixelSize(R.dimen.padding_screen)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad)
            insets
        }

        val ordersJson = intent.getStringExtra("orders_json") ?: "[]"
        val orders: List<OrderDto> = Gson().fromJson(ordersJson, Array<OrderDto>::class.java).toList()
        val invoiceId = intent.getStringExtra("invoice_id") ?: ""
        val batchId = intent.getStringExtra("batch_id") ?: ""

        // Batch / invoice
        findViewById<TextView>(R.id.tvTicketBatch).text =
            if (batchId.isNotBlank()) "#${batchId.takeLast(8)}" else "PENDIENTE"
        findViewById<TextView>(R.id.tvTicketInvoice).text =
            if (invoiceId.isNotBlank()) "#$invoiceId" else "—"

        // Date from first order or now
        val rawDate = orders.firstOrNull()?.createdAt
        findViewById<TextView>(R.id.tvTicketDate).text = formatDate(rawDate)

        // Customer name
        val customerName = intent.getStringExtra("customer_name")
            ?: orders.firstOrNull()?.customerName
        val cardCustomer = findViewById<View>(R.id.cardCustomer)
        if (!customerName.isNullOrBlank()) {
            cardCustomer.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvTicketCustomer).text = customerName
        } else {
            cardCustomer.visibility = View.GONE
        }

        // Items — cada orden como ítem individual con todos sus detalles
        val container = findViewById<LinearLayout>(R.id.layoutTicketItems)
        val inflater = LayoutInflater.from(this)
        for (order in orders) {
            val row = inflater.inflate(R.layout.item_order_product, container, false)
            row.findViewById<TextView>(R.id.tvProductName).text = order.productName
            row.findViewById<TextView>(R.id.tvProductBarcode).text =
                "${order.barcode}  ·  ${String.format(Locale.US, "$%.2f/lb", order.price)}"
            row.findViewById<TextView>(R.id.tvProductQty).text =
                String.format(Locale.US, "%.2f lb", order.quantity)
            row.findViewById<TextView>(R.id.tvProductTotal).text =
                String.format(Locale.US, "$%.2f", order.total)
            container.addView(row)
        }

        // Total
        val grandTotal = orders.sumOf { it.total }
        findViewById<TextView>(R.id.tvTicketTotal).text =
            String.format(Locale.US, "$%.2f", grandTotal)

        // Item count
        val totalQty = orders.sumOf { it.quantity }
        findViewById<TextView>(R.id.tvTicketItemCount).text =
            String.format(Locale.US, "%d producto(s) · %.2f lb", orders.size, totalQty)

        // Status chip
        val allSent = orders.all { it.status == "SENT" }
        val hasPending = orders.any { it.status == "PENDING" }
        val tvStatus = findViewById<TextView>(R.id.tvTicketStatus)
        when {
            allSent -> {
                tvStatus.text = "ENVIADO"
                tvStatus.setBackgroundResource(R.drawable.bg_chip_sent)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            }
            hasPending -> {
                tvStatus.text = "PENDIENTE"
                tvStatus.setBackgroundResource(R.drawable.bg_chip_pending)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.warning))
            }
            else -> tvStatus.visibility = View.GONE
        }

        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Botón reimprimir — visible solo si hay impresora configurada
        val btnReprint = findViewById<MaterialButton>(R.id.btnReprint)
        val printerAddress = SecurePreferences(this).getPrinterAddress()
        if (!printerAddress.isNullOrBlank()) {
            btnReprint.visibility = View.VISIBLE
            btnReprint.setOnClickListener {
                btnReprint.isEnabled = false
                btnReprint.text = "Imprimiendo…"
                val items = orders.map { o ->
                    BatchItem(
                        barcode = o.barcode,
                        productName = o.productName,
                        price = o.price,
                        quantity = o.quantity,
                        total = o.total
                    )
                }
                lifecycleScope.launch {
                    val result = PrintService.printTicket(
                        context = this@TicketDetailActivity,
                        deviceAddress = printerAddress,
                        items = items,
                        customerName = customerName,
                        batchId = batchId,
                        invoiceId = invoiceId
                    )
                    result.onSuccess {
                        Toast.makeText(this@TicketDetailActivity, "Ticket enviado a la impresora", Toast.LENGTH_SHORT).show()
                    }.onFailure { e ->
                        Toast.makeText(this@TicketDetailActivity, "Error al imprimir: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                    btnReprint.isEnabled = true
                    btnReprint.text = "Reimprimir ticket"
                }
            }
        }
    }

    private fun formatDate(rawDate: String?): String {
        if (rawDate.isNullOrBlank()) {
            return SimpleDateFormat("dd MMM yyyy · hh:mm a", Locale.getDefault()).format(Date())
        }
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val display = SimpleDateFormat("dd MMM yyyy · hh:mm a", Locale.getDefault())
            display.format(parser.parse(rawDate)!!)
        } catch (_: Exception) { rawDate }
    }
}

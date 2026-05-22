package com.example.test

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.ConvertPreOrderRequest
import com.example.test.data.PreOrderDto
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PreOrderDetailActivity : AppCompatActivity() {

    private lateinit var tvDetailCustomer: TextView
    private lateinit var tvDetailStatus: TextView
    private lateinit var tvDetailDate: TextView
    private lateinit var tvDetailNotes: TextView
    private lateinit var layoutDetailItems: LinearLayout
    private lateinit var tvDetailTotal: TextView
    private lateinit var btnConvert: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var securePrefs: SecurePreferences

    private var preOrderId = 0
    private var currentPreOrder: PreOrderDto? = null
    private var pendingSignature: String? = null

    private val signatureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingSignature = result.data?.getStringExtra("signature_base64")
            askPaymentMethodAndConvert()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_pre_order_detail)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        preOrderId = intent.getIntExtra("pre_order_id", 0)

        tvDetailCustomer  = findViewById(R.id.tvDetailCustomer)
        tvDetailStatus    = findViewById(R.id.tvDetailStatus)
        tvDetailDate      = findViewById(R.id.tvDetailDate)
        tvDetailNotes     = findViewById(R.id.tvDetailNotes)
        layoutDetailItems = findViewById(R.id.layoutDetailItems)
        tvDetailTotal     = findViewById(R.id.tvDetailTotal)
        btnConvert        = findViewById(R.id.btnConvert)
        btnCancel         = findViewById(R.id.btnCancel)
        progressBar       = findViewById(R.id.progressBar)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        btnConvert.setOnClickListener { startConversionFlow() }
        btnCancel.setOnClickListener  { confirmCancel() }

        loadPreOrder()
    }

    private fun loadPreOrder() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getPreOrder(preOrderId)
                if (resp.isSuccessful) {
                    val po = resp.body()?.data
                    if (po != null) {
                        currentPreOrder = po
                        renderPreOrder(po)
                    }
                } else {
                    showError("Error ${resp.code()}")
                }
            } catch (e: Exception) {
                showError(e.localizedMessage ?: "Error de conexión")
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun renderPreOrder(po: PreOrderDto) {
        tvDetailCustomer.text = po.customerName

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
        tvDetailStatus.text = statusLabel
        tvDetailStatus.setTextColor(ContextCompat.getColor(this, statusColor))

        val dateStr = buildString {
            po.scheduledDate?.let { append("Entrega programada: $it") }
            po.createdAt?.let {
                try {
                    val p = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    val d = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.US)
                    if (isNotEmpty()) append("\n")
                    append("Creada: ${d.format(p.parse(it)!!)}")
                } catch (_: Exception) {}
            }
        }
        tvDetailDate.text = dateStr

        if (!po.notes.isNullOrBlank()) {
            tvDetailNotes.text = "Notas: ${po.notes}"
            tvDetailNotes.visibility = View.VISIBLE
        } else {
            tvDetailNotes.visibility = View.GONE
        }

        layoutDetailItems.removeAllViews()
        var runningTotal = 0.0
        for (item in po.items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dp }
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "${item.productName}\n${String.format(Locale.US, "%.2f lb × $%.2f/lb", item.quantity, item.price)}"
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
            }
            val tvItemTotal = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                text = String.format(Locale.US, "$%.2f", item.total)
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
            }
            row.addView(tvName)
            row.addView(tvItemTotal)
            layoutDetailItems.addView(row)
            runningTotal += item.total
        }
        tvDetailTotal.text = String.format(Locale.US, "$%.2f", runningTotal)

        val canConvert = po.status == "DRAFT" || po.status == "CONFIRMED"
        btnConvert.isEnabled = canConvert
        btnConvert.alpha = if (canConvert) 1f else 0.4f
        btnCancel.isEnabled = po.status != "CANCELLED" && po.status != "CONVERTED"
        btnCancel.alpha = if (btnCancel.isEnabled) 1f else 0.4f
    }

    private fun startConversionFlow() {
        val po = currentPreOrder ?: return
        signatureLauncher.launch(Intent(this, SignatureActivity::class.java).apply {
            putExtra("customer_name", po.customerName)
        })
    }

    private fun askPaymentMethodAndConvert() {
        val options = arrayOf("Efectivo", "Cheque", "Omitir")
        AlertDialog.Builder(this)
            .setTitle("Método de pago")
            .setItems(options) { _, i ->
                val pm = when (i) { 0 -> "Cash"; 1 -> "Check"; else -> null }
                doConvert(pm)
            }
            .show()
    }

    private fun doConvert(paymentMethod: String?) {
        val po = currentPreOrder ?: return
        btnConvert.isEnabled = false
        btnConvert.text = "Convirtiendo..."
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().convertPreOrder(
                    id = preOrderId,
                    request = ConvertPreOrderRequest(
                        signature = pendingSignature,
                        paymentMethod = paymentMethod
                    )
                )
                if (resp.isSuccessful) {
                    val body = resp.body()
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Pre-orden convertida. Batch: ${body?.batchId?.takeLast(6)}",
                        Snackbar.LENGTH_LONG
                    ).show()
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    val msg = "Error ${resp.code()}"
                    Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show()
                    btnConvert.isEnabled = true
                    btnConvert.text = "Convertir a pedido"
                }
            } catch (e: Exception) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    e.localizedMessage ?: "Error de conexión",
                    Snackbar.LENGTH_LONG
                ).show()
                btnConvert.isEnabled = true
                btnConvert.text = "Convertir a pedido"
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun confirmCancel() {
        AlertDialog.Builder(this)
            .setTitle("Cancelar pre-orden")
            .setMessage("¿Deseas cancelar esta pre-orden? Esta acción no se puede deshacer.")
            .setPositiveButton("Sí, cancelar") { _, _ -> cancelPreOrder() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun cancelPreOrder() {
        lifecycleScope.launch {
            try {
                RetrofitClient.getApi().deletePreOrder(preOrderId)
                setResult(Activity.RESULT_OK)
                finish()
            } catch (e: Exception) {
                showError(e.localizedMessage ?: "Error al cancelar")
            }
        }
    }

    private fun showError(msg: String) {
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

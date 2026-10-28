package com.example.test

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.test.data.CreditMovementDto
import com.example.test.data.CustomerCreditsResponse
import com.example.test.data.formatDamageQty
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Créditos de un cliente (ticket 2, 2026-10-27): emitidos y aplicados (más
// nuevo primero) con productos, motivo y venta donde se aplicó. Los créditos
// 'cr…' ("Agregar crédito") tienen botón para reimprimir la nota de crédito.
class CustomerCreditsActivity : BaseActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutEntries: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvTotalEarned: TextView
    private lateinit var tvTotalUsed: TextView
    private lateinit var tvBalance: TextView

    private var customerId = ""
    private var statement: CustomerCreditsResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_customer_credits)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        val securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        customerId = intent.getStringExtra("customer_id") ?: ""
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = intent.getStringExtra("customer_name") ?: getString(R.string.title_customer_credits)
        toolbar.setNavigationOnClickListener { finish() }

        swipeRefresh  = findViewById(R.id.swipeRefresh)
        layoutEntries = findViewById(R.id.layoutEntries)
        tvEmpty       = findViewById(R.id.tvEmpty)
        tvTotalEarned = findViewById(R.id.tvTotalEarned)
        tvTotalUsed   = findViewById(R.id.tvTotalUsed)
        tvBalance     = findViewById(R.id.tvBalance)

        swipeRefresh.setColorSchemeColors(getColor(R.color.primary))
        swipeRefresh.setOnRefreshListener { load() }
        load()
    }

    private fun load() {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getCustomerCredits(customerId)
                if (resp.isSuccessful) {
                    statement = resp.body()
                    render()
                } else {
                    showMessage(getString(R.string.msg_server_error, resp.code().toString()))
                }
            } catch (e: Exception) {
                showMessage(e.localizedMessage ?: getString(R.string.error_no_connection))
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun render() {
        val s = statement ?: return
        tvTotalEarned.text = getString(R.string.label_credit_total_earned, money(s.summary.totalEarned))
        tvTotalUsed.text   = getString(R.string.label_credit_total_used, money(s.summary.totalUsed))
        tvBalance.text     = getString(R.string.label_credit_balance, money(s.summary.balance))

        layoutEntries.removeAllViews()
        tvEmpty.visibility = if (s.movements.isEmpty()) View.VISIBLE else View.GONE
        for (m in s.movements) layoutEntries.addView(buildCard(m))
    }

    private fun buildCard(m: CreditMovementDto): View {
        val earned = m.type == "EARNED"
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp }
            radius = 0f
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.white))
            strokeColor = getColor(R.color.ex_line)
            strokeWidth = 1.dp
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        }

        // Tipo + monto
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = getString(if (earned) R.string.label_credit_type_earned else R.string.label_credit_type_used)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.ex_ink))
        })
        header.addView(TextView(this).apply {
            text = (if (earned) "+" else "−") + money(m.amount)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(if (earned) R.color.ex_danger_deep else R.color.success))
        })
        body.addView(header)
        body.addView(smallText(formatDate(m.saleDate ?: m.createdAt)))

        // De dónde salió / dónde se aplicó
        val origin = when {
            m.standalone -> getString(R.string.label_credit_origin_standalone) +
                (m.issuedBy?.let { " · " + getString(R.string.label_credit_issued_by, it) } ?: "")
            earned -> getString(R.string.label_credit_origin_sale) + invoiceSuffix(m)
            m.batchId != null -> getString(R.string.label_credit_applied_in_sale) + invoiceSuffix(m)
            else -> null
        }
        origin?.let { body.addView(smallText(it)) }
        m.note?.takeIf { it.isNotBlank() }?.let { body.addView(smallText(it, italic = true)) }

        for (item in m.items) {
            body.addView(TextView(this).apply {
                text = "${item.productName} · ${formatDamageQty(item.qty, item.unit)} — ${money(item.amount)}"
                textSize = 13f
                setTextColor(getColor(R.color.ex_ink))
                setPadding(0, 6.dp, 0, 0)
            })
            val reason = listOfNotNull(item.reason, item.reasonNote?.takeIf { it.isNotBlank() }).joinToString(" — ")
            if (reason.isNotEmpty()) body.addView(smallText(getString(R.string.label_credit_reason, reason)))
        }

        if (m.standalone && m.batchId != null) {
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8.dp }
            }
            actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8.dp }
                text = getString(R.string.btn_print_credit_note)
                cornerRadius = 0
                setOnClickListener { printNote(m.batchId, this) }
            })
            // Ticket 3 (2026-10-27) — la misma nota por correo, siempre a mano.
            actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = getString(R.string.btn_email_credit_note)
                cornerRadius = 0
                setOnClickListener { askEmailThenSend(m.batchId, this) }
            })
            body.addView(actions)
        }

        card.addView(body)
        return card
    }

    private fun printNote(batchId: String, button: MaterialButton) {
        val s = statement ?: return
        button.isEnabled = false
        showMessage(getString(R.string.msg_printing_credit_note))
        lifecycleScope.launch {
            val error = CreditNotePrinter.print(this@CustomerCreditsActivity, s, batchId)
            showMessage(error ?: getString(R.string.msg_credit_note_printed))
            button.isEnabled = true
        }
    }

    // Diálogo compartido con la pre-orden (EmailSendDialog).
    private fun askEmailThenSend(batchId: String, button: MaterialButton) {
        EmailSendDialog.show(this, customerId, R.string.title_email_credit_note, button) { email ->
            RetrofitClient.getApi().sendCreditNoteEmail(batchId, com.example.test.data.SendEmailRequest(email))
        }
    }

    private fun invoiceSuffix(m: CreditMovementDto): String =
        m.invoiceNumber?.let { " · " + getString(R.string.label_credit_invoice, it.toString()) } ?: ""

    private fun smallText(value: String, italic: Boolean = false) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(getColor(R.color.ex_ink_soft))
        if (italic) setTypeface(typeface, android.graphics.Typeface.ITALIC)
        setPadding(0, 2.dp, 0, 0)
    }

    // Fechas de mysql2 en ISO UTC → hora del equipo.
    private fun formatDate(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.US).format(parser.parse(iso)!!)
        } catch (_: Exception) { "" }
    }

    private fun money(v: Double) = String.format(Locale.US, "$%.2f", v)

    private fun showMessage(msg: String) {
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

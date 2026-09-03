package com.example.test

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.CreateReturnsRequest
import com.example.test.data.RouteReturnExpectedDto
import com.example.test.data.RouteReturnItemRequest
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Locale

// Fase 112 — Revisión de devoluciones: el almacén cuenta lo que realmente
// volvió de la ruta (ya COMPLETED, ver botón en WarehouseRouteDetailActivity)
// y le asigna condición a cada producto. "Esperado" (GET .../returns/expected)
// es solo referencia — no bloquea si el conteo real no coincide, el almacén
// audita lo físico, no re-valida lo que se vendió.
//
// Un mismo producto puede volver parte bueno y parte dañado/vencido — cada
// fila tiene 4 campos de cantidad (uno por condición) en vez de un único
// campo + selector, para poder partirlo. La confirmación de salida
// (loaded_at/loaded_by_name, ver getExpectedReturns) se muestra como línea de
// base: como solo se puede cargar stock ACTIVE, todo lo que sale, sale bien —
// cualquier condición distinta de GOOD acá implica que pasó durante la ruta.
// TRANSPORTER_DAMAGE (Fase 116) distingue "se rompió en el camino" de
// DAMAGED genérico ("ya estaba mal") — mismo tratamiento en todo lo demás
// (notas obligatorias, no restituye stock).
class RouteReturnsActivity : BaseActivity() {

    private lateinit var layoutItems: LinearLayout
    private lateinit var tvNoItems: TextView
    private lateinit var btnSaveReturns: MaterialButton
    private lateinit var securePrefs: SecurePreferences

    private var routeId: Int = -1

    private data class ReturnRow(
        val expected: RouteReturnExpectedDto,
        val etQtyGood: EditText,
        val etQtyDamaged: EditText,
        val etQtyExpired: EditText,
        val etQtyTransporterDamage: EditText,
        val etNotes: EditText
    )

    private val rows = mutableListOf<ReturnRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_route_returns)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        routeId = intent.getIntExtra("route_id", -1)
        if (routeId == -1) { finish(); return }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        layoutItems    = findViewById(R.id.layoutItems)
        tvNoItems      = findViewById(R.id.tvNoItems)
        btnSaveReturns = findViewById(R.id.btnSaveReturns)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        btnSaveReturns.setOnClickListener { saveReturns() }

        loadExpected()
    }

    private fun loadExpected() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getExpectedReturns(routeId)
                if (resp.isSuccessful) {
                    renderRows(resp.body()?.data ?: emptyList())
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderRows(expected: List<RouteReturnExpectedDto>) {
        layoutItems.removeAllViews()
        rows.clear()
        tvNoItems.visibility = if (expected.isEmpty()) View.VISIBLE else View.GONE
        btnSaveReturns.visibility = if (expected.isEmpty()) View.GONE else View.VISIBLE

        for (exp in expected) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 10.dp
                }
                setCardBackgroundColor(getColor(R.color.surface))
                radius = 0f
                cardElevation = 2f
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            }

            val tvName = TextView(this).apply {
                text = exp.name
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val tvMeta = TextView(this).apply {
                text = getString(R.string.wh_expected_label, exp.loadedQty, exp.soldQty, exp.expectedReturnQty)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 2.dp, 0, 0)
            }
            content.addView(tvName)
            content.addView(tvMeta)

            // Línea de base: como addRouteItem solo carga stock de lotes
            // ACTIVE, esta fila ya es la confirmación de que salió bien —
            // cualquier condición distinta de GOOD abajo implica que pasó en
            // la ruta.
            if (exp.loadedAt != null) {
                val when_ = exp.loadedAt.take(16).replace("T", " ")
                val tvDeparted = TextView(this).apply {
                    text = if (!exp.loadedByName.isNullOrBlank())
                        getString(R.string.wh_departed_confirmation_with_driver, when_, exp.loadedByName)
                    else
                        getString(R.string.wh_departed_confirmation_no_driver, when_)
                    textSize = 11f
                    setTextColor(getColor(R.color.success))
                    setPadding(0, 4.dp, 0, 8.dp)
                }
                content.addView(tvDeparted)
            }

            fun qtyField(prefillGood: Boolean): EditText = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(String.format(Locale.US, "%.2f", if (prefillGood) exp.expectedReturnQty else 0.0))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            fun conditionRow(label: String, field: EditText): LinearLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4.dp, 0, 0)
                addView(TextView(this@RouteReturnsActivity).apply {
                    text = label
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(70.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                addView(field)
            }

            val etQtyGood = qtyField(prefillGood = true)
            val etQtyDamaged = qtyField(prefillGood = false)
            val etQtyExpired = qtyField(prefillGood = false)
            val etQtyTransporterDamage = qtyField(prefillGood = false)
            content.addView(conditionRow(getString(R.string.wh_condition_good), etQtyGood))
            content.addView(conditionRow(getString(R.string.wh_condition_damaged), etQtyDamaged))
            content.addView(conditionRow(getString(R.string.wh_condition_expired), etQtyExpired))
            content.addView(conditionRow(getString(R.string.wh_condition_transporter_damage), etQtyTransporterDamage))

            val etNotes = EditText(this).apply {
                hint = getString(R.string.wh_returns_notes_hint)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 6.dp
                }
            }
            content.addView(etNotes)

            // Atajo por producto: pone las 3 cantidades en 0 de un toque, en
            // vez de tener que borrar/tipear "0" a mano — el caso común
            // cuando se cargaron varias unidades de un producto y TODAS se
            // vendieron (nada volvió, en ninguna condición).
            val btnSoldOut = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.wh_btn_sold_out)
                textSize = 11f
                isAllCaps = false
                setTextColor(getColor(R.color.primary))
                strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.primary))
                cornerRadius = 0
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 34.dp).apply {
                    topMargin = 8.dp
                }
                setPadding(12.dp, 0, 12.dp, 0)
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    etQtyGood.setText(String.format(Locale.US, "%.2f", 0.0))
                    etQtyDamaged.setText(String.format(Locale.US, "%.2f", 0.0))
                    etQtyExpired.setText(String.format(Locale.US, "%.2f", 0.0))
                    etQtyTransporterDamage.setText(String.format(Locale.US, "%.2f", 0.0))
                }
            }
            content.addView(btnSoldOut)

            card.addView(content)
            layoutItems.addView(card)

            rows.add(ReturnRow(exp, etQtyGood, etQtyDamaged, etQtyExpired, etQtyTransporterDamage, etNotes))
        }
    }

    private fun qty(field: EditText): Double = field.text.toString().toDoubleOrNull() ?: 0.0

    private fun enteredItems(): List<RouteReturnItemRequest> = rows.flatMap { r ->
        val notes = r.etNotes.text.toString().trim().ifEmpty { null }
        val items = mutableListOf<RouteReturnItemRequest>()
        val good = qty(r.etQtyGood)
        val damaged = qty(r.etQtyDamaged)
        val expired = qty(r.etQtyExpired)
        val transporterDamage = qty(r.etQtyTransporterDamage)
        if (good > 0) items.add(RouteReturnItemRequest(productId = r.expected.productId, quantity = good, conditionStatus = "GOOD"))
        if (damaged > 0) items.add(RouteReturnItemRequest(productId = r.expected.productId, quantity = damaged, conditionStatus = "DAMAGED", notes = notes))
        if (expired > 0) items.add(RouteReturnItemRequest(productId = r.expected.productId, quantity = expired, conditionStatus = "EXPIRED", notes = notes))
        if (transporterDamage > 0) items.add(RouteReturnItemRequest(productId = r.expected.productId, quantity = transporterDamage, conditionStatus = "TRANSPORTER_DAMAGE", notes = notes))
        items
    }

    // Productos con Dañado/Vencido/Dañado en tránsito > 0 pero sin motivo —
    // el backend igual lo rechaza (red de seguridad), pero avisar acá evita
    // el viaje redondo.
    private fun rowsMissingNotes(): List<String> = rows.filter { r ->
        (qty(r.etQtyDamaged) > 0 || qty(r.etQtyExpired) > 0 || qty(r.etQtyTransporterDamage) > 0) && r.etNotes.text.toString().trim().isEmpty()
    }.map { it.expected.name }

    // Un envío vacío es válido a propósito — es como el almacenista confirma
    // "ya revisé esta ruta y no hay nada que devolver" (ej. se vendió todo).
    // Antes esto se bloqueaba, lo que hacía imposible marcar como revisada
    // una ruta 100% vendida. Igual se pide una confirmación explícita en ese
    // caso — un envío vacío también podría ser un olvido de completar los
    // campos, no solo "no hay nada que devolver".
    private fun saveReturns() {
        val missingNotes = rowsMissingNotes()
        if (missingNotes.isNotEmpty()) {
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.wh_returns_notes_required, missingNotes.joinToString(", ")), Snackbar.LENGTH_LONG).show()
            return
        }
        val items = enteredItems()
        if (items.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.wh_confirm_no_returns_title))
                .setMessage(getString(R.string.wh_confirm_no_returns_message))
                .setPositiveButton(getString(R.string.btn_confirm)) { _, _ -> submitReturns(items) }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
            return
        }
        submitReturns(items)
    }

    private fun submitReturns(items: List<RouteReturnItemRequest>) {
        btnSaveReturns.isEnabled = false
        btnSaveReturns.text = getString(R.string.btn_saving)

        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().createReturns(routeId, CreateReturnsRequest(items))
                if (resp.isSuccessful) {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_returns_saved), Snackbar.LENGTH_LONG).show()
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_returns_failed, resp.code().toString()), Snackbar.LENGTH_LONG).show()
                    btnSaveReturns.isEnabled = true
                    btnSaveReturns.text = getString(R.string.wh_btn_save_returns)
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_LONG).show()
                btnSaveReturns.isEnabled = true
                btnSaveReturns.text = getString(R.string.wh_btn_save_returns)
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

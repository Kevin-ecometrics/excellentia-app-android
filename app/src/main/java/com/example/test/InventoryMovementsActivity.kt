package com.example.test

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.test.data.InventoryMovementDto
import com.example.test.data.UpdateLotRequest
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

// Fase 112 — Sub-inventario: log auditable de los movimientos de inventario
// (recepción, carga de ruta, devolución, daño/ajuste) — de solo lectura para
// la mayoría de las filas, sin gráficos ni totales. La vista de reporte más
// completa queda para el Dashboard web (ver plan de la Fase 112).
//
// Excepción: las líneas RECEIPT con el lote todavía ACTIVE se pueden editar
// (cantidad/expiración) — pedido explícito del usuario para poder corregir un
// error de tipeo en la recepción sin tener que hacerlo a mano en la base.
class InventoryMovementsActivity : BaseActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutMovements: LinearLayout
    private lateinit var tvNoMovements: TextView
    private lateinit var securePrefs: SecurePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_inventory_movements)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        swipeRefresh    = findViewById(R.id.swipeRefresh)
        layoutMovements = findViewById(R.id.layoutMovements)
        tvNoMovements   = findViewById(R.id.tvNoMovements)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        swipeRefresh.setColorSchemeColors(getColor(R.color.primary))
        swipeRefresh.setOnRefreshListener { loadMovements() }

        loadMovements()
    }

    private fun loadMovements() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().listMovements()
                if (resp.isSuccessful) {
                    render(resp.body()?.data ?: emptyList())
                } else {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun movementTypeLabel(type: String): String = when (type) {
        "RECEIPT" -> getString(R.string.wh_movement_type_receipt)
        "ROUTE_LOAD" -> getString(R.string.wh_movement_type_route_load)
        "RETURN" -> getString(R.string.wh_movement_type_return)
        "DAMAGE" -> getString(R.string.wh_movement_type_damage)
        "ADJUSTMENT" -> getString(R.string.wh_movement_type_adjustment)
        else -> type
    }

    private fun render(movements: List<InventoryMovementDto>) {
        layoutMovements.removeAllViews()
        tvNoMovements.visibility = if (movements.isEmpty()) View.VISIBLE else View.GONE

        for (m in movements) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 6.dp
                }
                setCardBackgroundColor(getColor(R.color.surface))
                radius = 0f
                cardElevation = 1f
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp, 10.dp, 16.dp, 10.dp)
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvTitle = TextView(this).apply {
                val sign = if (m.quantity >= 0) "+" else ""
                text = "${m.productName ?: m.sku ?: "#${m.productId}"}  ·  ${movementTypeLabel(m.movementType)}  ·  $sign${String.format(Locale.US, "%.2f", m.quantity)}"
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
            }
            val tvMeta = TextView(this).apply {
                val settled = if (m.settlementId != null) getString(R.string.wh_settlement_status_confirmed) else getString(R.string.wh_settlement_status_draft)
                val expPart = if (m.movementType == "RECEIPT" && m.lotExpirationDate != null)
                    getString(R.string.wh_item_expiration_suffix, m.lotExpirationDate.take(10))
                else ""
                text = "${m.createdAt?.take(16)?.replace("T", " ") ?: ""}  ·  $settled$expPart"
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 2.dp, 0, 0)
            }
            textCol.addView(tvTitle)
            textCol.addView(tvMeta)
            row.addView(textCol)

            // Solo recepciones con el lote todavía ACTIVE se pueden corregir —
            // una vez dañado/vencido o consumido del todo, ya no tiene sentido.
            if (m.movementType == "RECEIPT" && m.lotId != null && m.lotStatus == "ACTIVE") {
                val btnEdit = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                    layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp).apply { marginStart = 8.dp }
                    setIconResource(R.drawable.ic_edit)
                    setOnClickListener { showEditLotDialog(m) }
                }
                row.addView(btnEdit)
            }

            card.addView(row)
            layoutMovements.addView(card)
        }
    }

    private fun showEditLotDialog(movement: InventoryMovementDto) {
        val lotId = movement.lotId ?: return
        val density = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
        }
        val etQty = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.2f", movement.lotReceivedQty ?: movement.quantity))
            selectAll()
        }
        var chosenDate: String? = movement.lotExpirationDate?.take(10)
        val btnDate = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = chosenDate ?: getString(R.string.wh_btn_pick_expiration)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * density).toInt()
            }
        }
        btnDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, y, mo, d ->
                chosenDate = String.format(Locale.US, "%04d-%02d-%02d", y, mo + 1, d)
                btnDate.text = chosenDate
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        layout.addView(etQty)
        layout.addView(btnDate)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.wh_edit_lot_title))
            .setMessage(movement.productName)
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                val qty = etQty.text.toString().toDoubleOrNull()
                updateLot(lotId, qty, chosenDate)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun updateLot(lotId: Int, quantity: Double?, expirationDate: String?) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().updateLot(lotId, UpdateLotRequest(quantity, expirationDate))
                if (resp.isSuccessful) {
                    Snackbar.make(findViewById(android.R.id.content), getString(R.string.wh_lot_updated), Snackbar.LENGTH_SHORT).show()
                    loadMovements()
                } else {
                    val msg = try {
                        resp.errorBody()?.string()?.let { com.google.gson.Gson().fromJson(it, com.example.test.data.ApiErrorBody::class.java)?.error }
                    } catch (_: Exception) { null }
                    Snackbar.make(findViewById(android.R.id.content), msg ?: getString(R.string.msg_server_error, resp.code().toString()), Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_connection), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

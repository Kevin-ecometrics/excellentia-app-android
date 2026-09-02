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
import com.example.test.data.ProductLotDto
import com.example.test.data.UpdateLotRequest
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// Fase 112 — Sub-inventario: dos vistas, "Disponible" (stock ACTIVE agrupado
// por producto, de un vistazo) e "Historial" (log auditable de todos los
// movimientos — recepción, carga de ruta, devolución, daño/ajuste). Editar
// cantidad/expiración de un lote (corregir un error de tipeo en la recepción)
// solo tiene sentido sobre stock que sigue disponible — se ofrece únicamente
// en "Disponible", no en el historial (a pedido del usuario).
class InventoryMovementsActivity : BaseActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var chipGroupView: ChipGroup
    private lateinit var sectionAvailable: LinearLayout
    private lateinit var layoutAvailable: LinearLayout
    private lateinit var tvNoAvailable: TextView
    private lateinit var sectionHistory: LinearLayout
    private lateinit var btnMovementDateFilter: MaterialButton
    private lateinit var btnMovementClearDate: TextView
    private lateinit var chipGroupType: ChipGroup
    private lateinit var layoutMovements: LinearLayout
    private lateinit var tvNoMovements: TextView
    private lateinit var securePrefs: SecurePreferences

    // Todo lo que trajo el backend para el día filtrado (o los últimos 500 sin
    // filtro), sin filtrar por tipo — ese filtro se aplica en memoria
    // (renderFiltered()), sin pedir de nuevo al servidor.
    private var allMovements: List<InventoryMovementDto> = emptyList()
    private var typeFilter: String? = null
    private var movementDateFilter: String? = null

    // Lotes ACTIVE tal cual los devuelve el backend (uno por escaneo de
    // recepción) — se agrupan por producto recién al renderizar (groupAvailableLots).
    // También se usa para el badge "Disponible" del historial (availableLotIds).
    private var allLots: List<ProductLotDto> = emptyList()

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

        swipeRefresh          = findViewById(R.id.swipeRefresh)
        chipGroupView         = findViewById(R.id.chipGroupView)
        sectionAvailable      = findViewById(R.id.sectionAvailable)
        layoutAvailable       = findViewById(R.id.layoutAvailable)
        tvNoAvailable         = findViewById(R.id.tvNoAvailable)
        sectionHistory        = findViewById(R.id.sectionHistory)
        btnMovementDateFilter = findViewById(R.id.btnMovementDateFilter)
        btnMovementClearDate  = findViewById(R.id.btnMovementClearDate)
        chipGroupType         = findViewById(R.id.chipGroupType)
        layoutMovements       = findViewById(R.id.layoutMovements)
        tvNoMovements         = findViewById(R.id.tvNoMovements)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        swipeRefresh.setColorSchemeColors(getColor(R.color.primary))
        swipeRefresh.setOnRefreshListener { loadAvailable(); loadMovements() }

        // Disponible es la pestaña por default — a pedido del usuario, antes
        // no había forma de ver de un vistazo qué había disponible ahora
        // mismo, solo se notaba mirando de a un ícono de editar por vez en el
        // historial de movimientos.
        chipGroupView.setOnCheckedStateChangeListener { _, checkedIds ->
            val showHistory = checkedIds.contains(R.id.chipViewHistory)
            sectionAvailable.visibility = if (showHistory) View.GONE else View.VISIBLE
            sectionHistory.visibility = if (showHistory) View.VISIBLE else View.GONE
        }

        // Filtro por fecha — el backend ya soportaba ?date= (listMovements)
        // pero Android nunca lo usaba: el historial se corta en las últimas
        // 500 filas (ORDER BY created_at DESC LIMIT 500) sin ninguna forma de
        // ir a un día anterior a esas 500.
        btnMovementDateFilter.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, y, mo, d ->
                movementDateFilter = String.format(Locale.US, "%04d-%02d-%02d", y, mo + 1, d)
                btnMovementDateFilter.text = movementDateFilter
                btnMovementClearDate.visibility = View.VISIBLE
                loadMovements()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        btnMovementClearDate.setOnClickListener {
            movementDateFilter = null
            btnMovementDateFilter.text = getString(R.string.label_select_date)
            btnMovementClearDate.visibility = View.GONE
            loadMovements()
        }

        // Antes todos los tipos de movimiento (recibido, cargado en ruta,
        // devuelto, dañado, ajuste) aparecían mezclados en una sola lista
        // plana — a pedido del usuario, se puede filtrar por tipo para
        // entender de un vistazo qué pasó con cada producto.
        chipGroupType.setOnCheckedStateChangeListener { _, checkedIds ->
            typeFilter = when {
                checkedIds.contains(R.id.chipTypeReceipt)    -> "RECEIPT"
                checkedIds.contains(R.id.chipTypeRouteLoad)  -> "ROUTE_LOAD"
                checkedIds.contains(R.id.chipTypeReturn)     -> "RETURN"
                checkedIds.contains(R.id.chipTypeDamage)     -> "DAMAGE"
                checkedIds.contains(R.id.chipTypeAdjustment) -> "ADJUSTMENT"
                else -> null
            }
            renderFiltered()
        }

        loadAvailable()
        loadMovements()
    }

    override fun onResume() {
        super.onResume()
        // Volver acá después de recibir mercadería, cargar una ruta o revisar
        // devoluciones (todas pantallas separadas) dejaba esta vista con datos
        // viejos hasta hacer swipe-to-refresh a mano.
        loadAvailable()
        loadMovements()
    }

    private fun loadMovements() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().listMovements(date = movementDateFilter)
                if (resp.isSuccessful) {
                    allMovements = resp.body()?.data ?: emptyList()
                    renderFiltered()
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

    private fun renderFiltered() {
        val filter = typeFilter
        render(if (filter == null) allMovements else allMovements.filter { it.movementType == filter })
    }

    // ── Disponible: stock ACTIVE agrupado por producto (Fase 112 cont.) ──
    // Antes la única forma de saber "qué tengo disponible ahora" en esta
    // pantalla era mirar de a un ícono de editar por vez en el historial de
    // movimientos (solo visible en líneas RECEIPT con lote ACTIVE) — no había
    // ninguna vista que sumara lo disponible de verdad.

    private fun loadAvailable() {
        lifecycleScope.launch {
            try {
                // status = "ACTIVE" explícito: sin este filtro el backend igual
                // default-ea a ACTIVE, pero no incluye remaining_qty > 0 — un
                // lote ACTIVE ya consumido del todo (remaining_qty = 0, ej. todo
                // cargado a una ruta) sigue viniendo, se descarta acá en
                // groupAvailableLots().
                val resp = RetrofitClient.getApi().listLots(status = "ACTIVE")
                if (resp.isSuccessful) {
                    allLots = resp.body()?.data ?: emptyList()
                    renderAvailable()
                    // El historial usa allLots para el badge "Disponible" — si
                    // ya se había pintado antes de que esta llamada volviera,
                    // hay que repintarlo con el dato fresco.
                    renderFiltered()
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

    private data class AvailableGroup(
        val productName: String,
        val totalQty: Double,
        val lots: List<ProductLotDto>
    )

    private fun groupAvailableLots(lots: List<ProductLotDto>): List<AvailableGroup> =
        lots.filter { it.remainingQty > 0 }
            .groupBy { it.productId }
            .map { (id, group) ->
                AvailableGroup(
                    productName = group.first().productName ?: group.first().sku ?: "#$id",
                    totalQty = group.sumOf { it.remainingQty },
                    // Mismo orden FIFO que usa el backend al cargar una ruta
                    // (vencimiento más próximo primero, sin fecha al final) —
                    // así queda claro qué lote hay que despachar antes.
                    lots = group.sortedWith(compareBy({ it.expirationDate == null }, { it.expirationDate }))
                )
            }
            .sortedBy { it.productName }

    private fun renderAvailable() {
        layoutAvailable.removeAllViews()
        val groups = groupAvailableLots(allLots)
        tvNoAvailable.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE

        for (g in groups) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 6.dp
                }
                setCardBackgroundColor(getColor(R.color.surface))
                radius = 0f
                cardElevation = 1f
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp, 10.dp, 16.dp, 10.dp)
            }
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tvName = TextView(this).apply {
                text = g.productName
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvTotal = TextView(this).apply {
                text = getString(R.string.wh_available_qty_badge, g.totalQty)
                textSize = 10f
                setTextColor(getColor(R.color.success))
                setBackgroundResource(R.drawable.bg_chip_sent)
                setPadding(8.dp, 3.dp, 8.dp, 3.dp)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            headerRow.addView(tvName)
            headerRow.addView(tvTotal)
            col.addView(headerRow)

            for (lot in g.lots) {
                val lotRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val exp = lot.expirationDate?.take(10)
                val expiringSoon = exp != null && isExpiringSoon(exp)
                val tvLot = TextView(this).apply {
                    text = "•  " + getString(R.string.wh_lot_picker_line, exp ?: getString(R.string.wh_no_expiration), lot.remainingQty)
                    textSize = 11f
                    setTextColor(getColor(if (expiringSoon) R.color.amber_dark else R.color.text_secondary))
                    setPadding(0, 3.dp, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                // Editar solo tiene sentido acá — sobre stock que sigue
                // disponible de verdad (ACTIVE + remaining_qty > 0, que es
                // justo lo que entra a este grupo). Antes vivía en el
                // historial (líneas RECEIPT), donde se podía "corregir" un
                // lote ya consumido del todo, algo que no tiene sentido
                // práctico y confundía más de lo que ayudaba.
                val btnEdit = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                    layoutParams = LinearLayout.LayoutParams(32.dp, 32.dp)
                    setIconResource(R.drawable.ic_edit)
                    setIconSize(16.dp)
                    setOnClickListener { showEditLotDialog(lot) }
                }
                // "Devolver" — para stock que se recibió pero no hacía falta.
                // A diferencia de Editar (corrige un error de tipeo), esto es
                // una acción directa de un tap con su propia confirmación,
                // mismo mecanismo que Editar por abajo (updateLot) pero
                // siempre baja a 0 lo que queda sin usar del lote.
                val btnReturn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                    layoutParams = LinearLayout.LayoutParams(32.dp, 32.dp).apply { marginStart = 2.dp }
                    setIconResource(R.drawable.ic_delete)
                    setIconSize(16.dp)
                    setIconTintResource(R.color.red)
                    setOnClickListener { confirmReturnLot(lot) }
                }
                lotRow.addView(tvLot)
                lotRow.addView(btnEdit)
                lotRow.addView(btnReturn)
                col.addView(lotRow)
            }

            card.addView(col)
            layoutAvailable.addView(card)
        }
    }

    // ≤7 días para vencer — mismo umbral en "Disponible" y en el badge de
    // expiración del historial, para no tener dos criterios de "pronto a
    // vencer" distintos en la misma pantalla.
    private fun isExpiringSoon(dateStr: String): Boolean {
        val days = try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val target = fmt.parse(dateStr) ?: return false
            val today = fmt.parse(fmt.format(java.util.Date())) ?: return false
            (target.time - today.time) / (1000 * 60 * 60 * 24)
        } catch (e: Exception) { return false }
        return days in 0..7
    }

    // "Hoy" / "Ayer" / fecha cruda — mismos encabezados de sección que separan
    // el historial en bloques por día, en vez de una lista plana donde había
    // que leer la fecha de cada fila una por una para saber dónde empezaba
    // otro día.
    private fun dayLabel(dateKey: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = fmt.format(java.util.Date())
        if (dateKey == today) return getString(R.string.chip_today)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        if (dateKey == fmt.format(cal.time)) return getString(R.string.wh_yesterday)
        return dateKey
    }

    // Lotes con remaining_qty > 0 ahora mismo — mismo dato que alimenta la
    // pestaña "Disponible" (allLots), reusado acá para marcar en el
    // historial qué movimientos todavía tienen stock vivo detrás.
    private fun availableLotIds(): Set<Int> =
        allLots.filter { it.remainingQty > 0 }.map { it.id }.toSet()

    private fun movementTypeLabel(type: String): String = when (type) {
        "RECEIPT" -> getString(R.string.wh_movement_type_receipt)
        "ROUTE_LOAD" -> getString(R.string.wh_movement_type_route_load)
        "RETURN" -> getString(R.string.wh_movement_type_return)
        "DAMAGE" -> getString(R.string.wh_movement_type_damage)
        "ADJUSTMENT" -> getString(R.string.wh_movement_type_adjustment)
        else -> type
    }

    // Color por tipo de movimiento — mismo criterio en toda la pantalla:
    // verde = entra stock al almacén (recepción/devolución), índigo = sale
    // stock de forma normal (carga de ruta, no es una pérdida), rojo = baja
    // por daño/vencimiento, ámbar = ajuste manual (corrección, merece
    // atención). Antes todos los tipos se veían exactamente igual (texto
    // plano sin ningún color), lo que hacía difícil distinguirlos de un
    // vistazo en una lista larga.
    private fun movementTypeStyle(type: String): Pair<Int, Int> = when (type) {
        "RECEIPT", "RETURN" -> R.drawable.bg_chip_sent to R.color.success
        "ROUTE_LOAD" -> R.drawable.bg_chip_indigo to R.color.primary_light
        "DAMAGE" -> R.drawable.bg_chip_failed to R.color.red
        "ADJUSTMENT" -> R.drawable.bg_chip_pending to R.color.amber_dark
        else -> R.drawable.bg_chip_pending to R.color.text_secondary
    }

    private fun render(movements: List<InventoryMovementDto>) {
        layoutMovements.removeAllViews()
        tvNoMovements.text = if (typeFilter != null) getString(R.string.wh_no_movements_for_filter) else getString(R.string.wh_no_movements)
        tvNoMovements.visibility = if (movements.isEmpty()) View.VISIBLE else View.GONE

        val availableLots = availableLotIds()
        // Ordenados DESC por el backend — se agrupan por día insertando un
        // encabezado cada vez que cambia la fecha, sin reordenar nada acá.
        var lastDateKey: String? = null

        for (m in movements) {
            val dateKey = m.createdAt?.take(10)
            if (dateKey != null && dateKey != lastDateKey) {
                val header = TextView(this).apply {
                    text = dayLabel(dateKey)
                    textSize = 10f
                    letterSpacing = 0.06f
                    isAllCaps = true
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(0, if (lastDateKey == null) 0 else 14.dp, 0, 6.dp)
                }
                layoutMovements.addView(header)
                lastDateKey = dateKey
            }

            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 6.dp
                }
                setCardBackgroundColor(getColor(R.color.surface))
                radius = 0f
                cardElevation = 1f
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp, 10.dp, 16.dp, 10.dp)
            }
            val tvName = TextView(this).apply {
                text = m.productName ?: m.sku ?: "#${m.productId}"
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            // Fila con el tipo como badge de color (en vez de texto plano
            // mezclado con el nombre), la cantidad con su propio color según
            // el signo, y — si el lote de esta línea todavía tiene stock — un
            // badge "Disponible" (mismo criterio que la pestaña Disponible,
            // ver availableLotIds()) para no tener que ir a buscarlo ahí.
            val badgeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 5.dp, 0, 0)
            }
            val (badgeBg, badgeColor) = movementTypeStyle(m.movementType)
            val tvType = TextView(this).apply {
                text = movementTypeLabel(m.movementType)
                textSize = 10f
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setTextColor(getColor(badgeColor))
                setBackgroundResource(badgeBg)
                setPadding(8.dp, 3.dp, 8.dp, 3.dp)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val tvQty = TextView(this).apply {
                val sign = if (m.quantity >= 0) "+" else ""
                text = "$sign${String.format(Locale.US, "%.2f", m.quantity)}"
                textSize = 12f
                setTextColor(getColor(if (m.quantity >= 0) R.color.success else R.color.red))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(8.dp, 0, 0, 0)
            }
            badgeRow.addView(tvType)
            badgeRow.addView(tvQty)
            if (m.lotId != null && availableLots.contains(m.lotId)) {
                val tvAvailable = TextView(this).apply {
                    text = getString(R.string.wh_lot_available_badge)
                    textSize = 10f
                    setTextColor(getColor(R.color.success))
                    setBackgroundResource(R.drawable.bg_chip_sent)
                    setPadding(8.dp, 3.dp, 8.dp, 3.dp)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 6.dp }
                badgeRow.addView(tvAvailable, lp)
            }
            val tvMeta = TextView(this).apply {
                val exp = m.lotExpirationDate?.take(10)
                val expPart = if (exp != null) getString(R.string.wh_item_expiration_suffix, exp) else ""
                val routePart = m.routeId?.let { "  ·  " + getString(R.string.wh_route_ref, it) } ?: ""
                text = "${m.createdAt?.take(16)?.replace("T", " ") ?: ""}$expPart$routePart"
                textSize = 11f
                val expiringSoon = exp != null && isExpiringSoon(exp)
                setTextColor(getColor(if (expiringSoon) R.color.amber_dark else R.color.text_secondary))
                setPadding(0, 5.dp, 0, 0)
            }
            textCol.addView(tvName)
            textCol.addView(badgeRow)
            textCol.addView(tvMeta)
            card.addView(textCol)
            layoutMovements.addView(card)
        }
    }

    // Devolver = bajar a 0 lo que queda sin usar de este lote (nunca lo que
    // ya se cargó a una ruta). updateLot rechaza si quantity < consumed
    // (received_qty - remaining_qty) — mandar exactamente ese "consumed" en
    // vez de 0 deja remaining_qty en 0 sin tocar lo ya asignado, así que el
    // 409 de "ya se asignó a una ruta" no debería poder dispararse desde acá.
    private fun confirmReturnLot(lot: ProductLotDto) {
        val consumed = lot.receivedQty - lot.remainingQty
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.wh_return_lot_title))
            .setMessage(getString(R.string.wh_return_lot_confirm, lot.remainingQty, lot.productName ?: lot.sku ?: "#${lot.productId}"))
            .setPositiveButton(getString(R.string.wh_btn_return_lot)) { _, _ ->
                updateLot(lot.id, consumed, lot.expirationDate?.take(10))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showEditLotDialog(lot: ProductLotDto) {
        val density = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
        }
        val etQty = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.2f", lot.receivedQty))
            selectAll()
        }
        var chosenDate: String? = lot.expirationDate?.take(10)
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
            .setMessage(lot.productName)
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                val qty = etQty.text.toString().toDoubleOrNull()
                updateLot(lot.id, qty, chosenDate)
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
                    loadAvailable()
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

package com.example.test

import android.app.Activity
import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.PreOrderItem
import com.example.test.data.PreOrderRequest
import com.example.test.data.UserBrief
import com.example.test.data.seedQuantityForStepper
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.example.test.data.repository.PreOrderRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class CreatePreOrderActivity : BaseActivity() {

    private lateinit var cardCustomer: MaterialCardView
    private lateinit var tvSelectedCustomer: TextView
    private lateinit var cardSalesperson: MaterialCardView
    private lateinit var tvSalesperson: TextView
    private lateinit var btnPickDate: MaterialButton
    private lateinit var tvSelectedDate: TextView
    private lateinit var etNotes: EditText
    private lateinit var layoutItems: LinearLayout
    private lateinit var tvNoItems: TextView
    private lateinit var btnSearchProduct: MaterialButton
    private lateinit var btnSavePreOrder: MaterialButton
    private lateinit var securePrefs: SecurePreferences
    private val preOrderRepository by lazy { PreOrderRepository(AppDatabase.getInstance(this)) }

    private var selectedCustomerId: String? = null
    private var selectedCustomerName: String? = null
    private var selectedSalespersonName: String? = null
    // El vendedor elegido en el picker de abajo no es solo texto para el ticket — su
    // id determina quién puede ver esta pre-orden (junto con los admins, ver
    // canAccessPreOrder en el backend). No hay un campo separado de "asignar".
    private var selectedSalespersonUserId: Int? = null
    private var selectedDate: String? = null
    private val items = mutableListOf<PreOrderItem>()
    // Caja (product_lots.id) elegida para el item que se está agregando ahora
    // mismo — la lee addItemLauncher al volver del stepper.
    private var pendingLotId: Int? = null
    private val salespersons = mutableListOf<UserBrief>()

    private companion object {
        const val DW_RESULT_ACTION = "com.symbol.datawedge.datawedge.ACTION_RESULT"
        const val DW_EXTRA_DATA    = "com.symbol.datawedge.data_string"
    }

    private val dwReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DW_RESULT_ACTION) {
                val data = intent.getStringExtra(DW_EXTRA_DATA) ?: return
                if (data.isNotBlank()) onBarcodeScanned(data)
            }
        }
    }

    private val customerPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedCustomerId   = result.data?.getStringExtra("customer_id")
            selectedCustomerName = result.data?.getStringExtra("customer_name")
            tvSelectedCustomer.text = selectedCustomerName ?: getString(R.string.label_customer_selected)
            tvSelectedCustomer.setTextColor(getColor(R.color.text_primary))
        }
    }

    // Captura cantidad/unidad al agregar el producto (mismo stepper que usa
    // PreOrderDetailActivity.finalizeItem() para detallar al convertir) — el
    // precio que trae acá es solo un preview, se vuelve a consultar fresco al
    // convertir (ver PreOrderDetailActivity.quickFinalizeItem()).
    private val addItemLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val itemsJson = result.data?.getStringExtra(ProductDetailActivity.RESULT_ITEMS_JSON)
            if (itemsJson != null) {
                try {
                    val type = object : TypeToken<List<PreOrderItem>>() {}.type
                    val newItems: List<PreOrderItem> = Gson().fromJson(itemsJson, type)
                    addItems(newItems.map { it.copy(lotId = pendingLotId) })
                } catch (_: Exception) {
                    Snackbar.make(findViewById(android.R.id.content),
                        getString(R.string.error_finalizing_item), Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_create_pre_order)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        securePrefs = SecurePreferences(this)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)

        cardCustomer      = findViewById(R.id.cardCustomer)
        tvSelectedCustomer = findViewById(R.id.tvSelectedCustomer)
        cardSalesperson   = findViewById(R.id.cardSalesperson)
        tvSalesperson     = findViewById(R.id.tvSalesperson)
        btnPickDate       = findViewById(R.id.btnPickDate)
        tvSelectedDate    = findViewById(R.id.tvSelectedDate)
        etNotes           = findViewById(R.id.etNotes)
        layoutItems       = findViewById(R.id.layoutItems)
        tvNoItems         = findViewById(R.id.tvNoItems)
        btnSearchProduct  = findViewById(R.id.btnSearchProduct)
        btnSavePreOrder   = findViewById(R.id.btnSavePreOrder)

        // Pre-fill active customer if any
        securePrefs.getActiveCustomerId()?.let { id ->
            selectedCustomerId   = id
            selectedCustomerName = securePrefs.getActiveCustomerName()
            tvSelectedCustomer.text = selectedCustomerName ?: ""
            tvSelectedCustomer.setTextColor(getColor(R.color.text_primary))
        }

        loadSalespersons()

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        cardCustomer.setOnClickListener {
            customerPickerLauncher.launch(Intent(this, CustomerPickerActivity::class.java))
        }

        cardSalesperson.setOnClickListener { showSalespersonPicker() }
        btnPickDate.setOnClickListener { showDatePicker() }
        btnSearchProduct.setOnClickListener { showProductSearchDialog() }
        btnSavePreOrder.setOnClickListener { savePreOrder() }

        registerDwReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(dwReceiver) } catch (_: Exception) {}
    }

    private fun registerDwReceiver() {
        val filter = IntentFilter(DW_RESULT_ACTION).apply {
            addCategory(android.content.Intent.CATEGORY_DEFAULT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dwReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(dwReceiver, filter)
        }
    }

    private fun loadSalespersons() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().listSalespersons()
                if (resp.isSuccessful) {
                    val list = resp.body()?.data ?: emptyList()
                    salespersons.clear()
                    salespersons.addAll(list)
                }
            } catch (_: Exception) {}
        }
    }

    // El vendedor elegido acá es tanto el texto que va al ticket (salesperson_name)
    // como el usuario real cuya cuenta queda habilitada para ver esta pre-orden
    // (selectedSalespersonUserId → assigned_user_id en el backend) — un solo picker,
    // sin un campo de "asignar" separado.
    private fun showSalespersonPicker() {
        if (salespersons.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content), "No hay vendedores disponibles", Snackbar.LENGTH_SHORT).show()
            return
        }
        val names = salespersons.map { it.name ?: "—" }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Seleccionar vendedor")
            .setItems(names) { _, index ->
                val sp = salespersons[index]
                selectedSalespersonName = sp.name
                selectedSalespersonUserId = sp.id
                tvSalesperson.text = sp.name ?: "—"
                tvSalesperson.setTextColor(getColor(R.color.text_primary))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            selectedDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
            tvSelectedDate.text = getString(R.string.label_delivery_date, selectedDate)
            tvSelectedDate.visibility = View.VISIBLE
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).also {
            it.datePicker.minDate = cal.timeInMillis
        }.show()
    }

    private fun onBarcodeScanned(barcode: String) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getProductByBarcode(barcode)
                if (resp.isSuccessful) {
                    val product = resp.body()?.data ?: return@launch
                    openAddItemStepper(barcode, product)
                } else {
                    Snackbar.make(findViewById(android.R.id.content),
                        getString(R.string.error_product_not_found_barcode, barcode), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(android.R.id.content),
                    getString(R.string.error_searching_product), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showProductSearchDialog() {
        val ctx = this
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val etSearch = android.widget.EditText(ctx).apply {
            hint = getString(R.string.hint_product_name_search)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val tvStatus = android.widget.TextView(ctx).apply {
            textSize = 13f
            setPadding(0, 12, 0, 0)
            setTextColor(getColor(R.color.text_secondary))
            text = getString(R.string.label_type_to_search)
        }
        val listHeightPx = (240 * resources.displayMetrics.density).toInt()
        val lvResults = android.widget.ListView(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, listHeightPx
            )
            visibility = android.view.View.GONE
            divider = android.graphics.drawable.ColorDrawable(getColor(R.color.text_secondary))
            dividerHeight = 1
            clipToPadding = false
            setPadding(0, 0, 0, 24)
        }
        layout.addView(etSearch)
        layout.addView(tvStatus)
        layout.addView(lvResults)

        var foundProducts: List<com.example.test.data.ProductDto> = emptyList()
        val resultsAdapter = android.widget.ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, mutableListOf())
        lvResults.adapter = resultsAdapter

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.title_search_product_by_name))
            .setView(layout)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()

        lvResults.setOnItemClickListener { _, _, idx, _ ->
            foundProducts.getOrNull(idx)?.let { p ->
                dialog.dismiss()
                openAddItemStepper(p.barcode ?: "", p)
            }
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: return
                if (query.length < 2) {
                    tvStatus.text = getString(R.string.label_type_at_least_2)
                    lvResults.visibility = android.view.View.GONE
                    return
                }
                tvStatus.text = getString(R.string.label_searching)
                lvResults.visibility = android.view.View.GONE
                lifecycleScope.launch {
                    try {
                        val resp = RetrofitClient.getApi().searchProducts(query)
                        if (resp.isSuccessful) {
                            foundProducts = resp.body()?.data ?: emptyList()
                            if (foundProducts.isEmpty()) {
                                tvStatus.text = getString(R.string.label_no_results, query)
                                lvResults.visibility = android.view.View.GONE
                            } else {
                                tvStatus.text = ""
                                resultsAdapter.clear()
                                foundProducts.forEach { p ->
                                    resultsAdapter.add("${p.name}  ·  $${String.format(java.util.Locale.US, "%.2f", p.price)}/${p.unit ?: "lb"}  ·  ${p.barcode ?: getString(R.string.no_barcode_label)}")
                                }
                                lvResults.visibility = android.view.View.VISIBLE
                                lvResults.post { lvResults.setSelection(0) }
                            }
                        }
                    } catch (_: Exception) {
                        tvStatus.text = getString(R.string.label_search_error)
                        lvResults.visibility = android.view.View.GONE
                    }
                }
            }
        })

        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    // Abre el mismo stepper que PreOrderDetailActivity.finalizeItem() usa para
    // detallar al convertir — ahora también se usa acá para capturar cantidad/
    // unidad ya al crear la pre-orden. El precio que devuelve es solo un
    // preview (se recalcula fresco al convertir), pero cantidad/unidad sí
    // quedan guardados desde este momento.
    private fun openAddItemStepper(barcode: String, product: com.example.test.data.ProductDto) {
        if (barcode.isBlank() || barcode == "unknown") {
            Snackbar.make(findViewById(android.R.id.content),
                getString(R.string.error_no_barcode_preorder), Snackbar.LENGTH_SHORT).show()
            return
        }
        if (items.any { it.barcode == barcode }) {
            Snackbar.make(findViewById(android.R.id.content),
                getString(R.string.error_already_in_preorder), Snackbar.LENGTH_SHORT).show()
            return
        }
        // Igual que PreOrderDetailActivity.finalizeItem(): QUANTITY acá es la
        // semilla del stepper (peso nominal / tamaño de caja / 1 fijo para
        // Bucket), no una cantidad previamente elegida — es la primera vez que
        // se agrega este producto. Ver seedQuantityForStepper().
        // Backlog cliente #2 (2026-09-28) — peso variable por caja: para Lbs se
        // ofrece elegir una caja puntual del Sub-inventario (peso real). Es
        // solo informativo (no reserva la caja): queda en pre_order_items.lot_id.
        if (!com.example.test.data.isLbsUnit(product.unit)) {
            launchAddItemStepper(barcode, product, null)
            return
        }
        lifecycleScope.launch {
            val lots = try {
                val resp = RetrofitClient.getApi().listLots(productId = product.id)
                if (resp.isSuccessful) (resp.body()?.data ?: emptyList()).filter { it.remainingQty > 0 } else emptyList()
            } catch (_: Exception) { emptyList() }
            if (lots.isEmpty()) { launchAddItemStepper(barcode, product, null); return@launch }
            val labels = lots.map { lot ->
                val exp = lot.expirationDate?.take(10) ?: getString(R.string.wh_no_expiration)
                // Paso 4 (opción A): avisa si otra pre-orden ya eligió esta caja,
                // sin bloquear — elegirla sigue siendo permitido.
                val claims = lot.claimedBy.orEmpty()
                val claimPart = claims.firstOrNull()?.let { c ->
                    getString(R.string.wh_box_claimed_suffix, c.preOrderId, c.customerName ?: "—") +
                        (if (claims.size > 1) getString(R.string.wh_box_claimed_more, claims.size - 1) else "")
                } ?: ""
                getString(R.string.wh_box_picker_line, com.example.test.data.formatQty(lot.remainingQty), exp, lot.lotNumber ?: "-") + claimPart
            }.toTypedArray()
            MaterialAlertDialogBuilder(this@CreatePreOrderActivity)
                .setTitle(getString(R.string.preorder_pick_box_title))
                .setItems(labels) { _, which -> launchAddItemStepper(barcode, product, lots[which]) }
                .setNeutralButton(getString(R.string.preorder_no_box)) { _, _ -> launchAddItemStepper(barcode, product, null) }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    // lot != null → la cantidad inicial es el peso real de esa caja y el item
    // resultante queda vinculado a ella (pendingLotId, ver addItemLauncher).
    private fun launchAddItemStepper(barcode: String, product: com.example.test.data.ProductDto, lot: com.example.test.data.ProductLotDto?) {
        pendingLotId = lot?.id
        val initialQty = lot?.remainingQty ?: seedQuantityForStepper(product.qty, product.weightPerUnit, product.unit)
        addItemLauncher.launch(Intent(this, ProductDetailActivity::class.java).apply {
            putExtra("BARCODE", barcode)
            putExtra("PRODUCT_NAME", product.name)
            putExtra("SHORT_NAME", product.shortName)
            putExtra("PRODUCT_PRICE", product.price)
            putExtra("QUANTITY", initialQty)
            putExtra("STOCK", product.stock)
            putExtra("CUSTOMER_ID", selectedCustomerId)
            putExtra("CUSTOMER_NAME", selectedCustomerName)
            putExtra("UNIT", product.unit)
            putExtra("CASE_QTY", product.caseQty ?: 0)
            putExtra(ProductDetailActivity.PRE_ORDER_MODE, true)
        })
    }

    private fun addItems(newItems: List<PreOrderItem>) {
        items.addAll(newItems)
        tvNoItems.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rebuildItemsList()
    }

    private fun rebuildItemsList() {
        layoutItems.removeAllViews()
        for ((idx, item) in items.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dp }
                gravity = Gravity.CENTER_VERTICAL
            }

            val unitLabel = item.unit?.let { if (it.isBlank() || it == "Lbs") "lb" else it } ?: "lb"
            val tvItem = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "${item.productName}\n" + String.format(
                    Locale.US, "%.2f %s · \$%.2f", item.quantity ?: 0.0, unitLabel, item.total ?: 0.0
                )
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
            }

            val btnRemove = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp)
                setIconResource(R.drawable.ic_remove)
                iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.red))
                setOnClickListener {
                    items.removeAt(idx)
                    tvNoItems.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    rebuildItemsList()
                }
            }

            row.addView(tvItem)
            row.addView(btnRemove)
            layoutItems.addView(row)
        }
    }

    private fun savePreOrder() {
        if (selectedCustomerId == null || selectedCustomerName == null) {
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_select_customer_first), Snackbar.LENGTH_SHORT).show()
            return
        }
        if (selectedDate == null) {
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_select_date), Snackbar.LENGTH_SHORT).show()
            return
        }
        if (items.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_add_at_least_one_product), Snackbar.LENGTH_SHORT).show()
            return
        }

        btnSavePreOrder.isEnabled = false
        btnSavePreOrder.text = getString(R.string.btn_saving)

        val request = PreOrderRequest(
            customerId      = selectedCustomerId!!,
            customerName    = selectedCustomerName!!,
            salespersonName = selectedSalespersonName,
            scheduledDate   = selectedDate,
            notes           = etNotes.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            items           = items.toList(),
            assignedUserId  = selectedSalespersonUserId
        )

        lifecycleScope.launch {
            val result = preOrderRepository.createPreOrder(request)
            result.onSuccess { response ->
                if (response.status == "OFFLINE_PENDING") {
                    android.widget.Toast.makeText(
                        this@CreatePreOrderActivity,
                        getString(R.string.success_pre_order_saved_offline),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                setResult(Activity.RESULT_OK)
                // Backlog cliente #5 (2026-09-23) — aviso de stock bajo/agotado,
                // no bloqueante: la pre-orden ya se creó (o encoló offline) de
                // cualquier forma, esto es puramente informativo para que el
                // vendedor sepa que puede faltar stock cuando la vaya a
                // convertir. `stockWarnings` llega null en el camino offline
                // (no hay forma de calcularlo sin conexión).
                val warnings = response.stockWarnings
                if (!warnings.isNullOrEmpty()) {
                    val lines = warnings.joinToString("\n") { w ->
                        if (w.stock <= 0) getString(R.string.wh_stock_warning_zero, w.productName)
                        else getString(R.string.wh_stock_warning_low, w.productName, w.stock)
                    }
                    MaterialAlertDialogBuilder(this@CreatePreOrderActivity)
                        .setTitle(getString(R.string.wh_stock_warning_title))
                        .setMessage(lines)
                        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                } else {
                    finish()
                }
            }.onFailure { e ->
                Snackbar.make(findViewById(android.R.id.content), e.localizedMessage ?: getString(R.string.error_no_connection), Snackbar.LENGTH_LONG).show()
                btnSavePreOrder.isEnabled = true
                btnSavePreOrder.text = getString(R.string.btn_save_pre_order)
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

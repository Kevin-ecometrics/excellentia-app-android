package com.example.test

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.local.dao.ProductDao
import com.example.test.data.network.RetrofitClient
import com.example.test.data.repository.ProductRepository
import com.example.test.data.repository.OrderRepository
import com.example.test.data.sync.SyncWorker
import com.example.test.data.sync.OrderStatusWorker
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {

    companion object {
        private const val DW_RESULT_ACTION = "com.symbol.datawedge.datawedge.ACTION_RESULT"
        private const val DW_EXTRA_DATA = "com.symbol.datawedge.data_string"
        private const val DW_EXTRA_DATA_ALT = "com.motorolasolutions.emdk.datawedge.data_string"
        private const val DW_EXTRA_LABEL_TYPE = "com.symbol.datawedge.label_type"
        private const val DW_EXTRA_LABEL_TYPE_ALT = "com.motorolasolutions.emdk.datawedge.label_type"
        private const val DW_API_ACTION = "com.symbol.datawedge.api.ACTION"
        private const val DW_PACKAGE = "com.symbol.datawedge"
        private const val DW_CATEGORY = "android.intent.category.DEFAULT"
        private const val DW_RESULT = "com.symbol.datawedge.api.RESULT_ACTION"
        private const val DW_CREATE = "com.symbol.datawedge.api.EXTRA_CREATE_PROFILE"
        private const val DW_CONFIGURE = "com.symbol.datawedge.api.EXTRA_SET_DEFAULT_CONFIG"
        private const val DW_PROFILE = "TestScannerProfile"
    }

    private lateinit var viewStatusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var bannerOffline: View
    private lateinit var tvScanPrompt: TextView
    private lateinit var tvLastBarcode: TextView
    private lateinit var tvLastProduct: TextView
    private lateinit var tvLastTime: TextView
    private lateinit var tvHoldState: TextView
    private lateinit var tvCustomerName: TextView
    private lateinit var btnScan: View
    private lateinit var btnHoldScan: View
    private lateinit var btnManualEntry: View
    private lateinit var btnSelectCustomer: MaterialButton
    private lateinit var btnChangeCustomer: MaterialButton
    private lateinit var btnViewClientHistory: MaterialButton
    private lateinit var btnPreOrders: MaterialButton
    private lateinit var layoutLastScan: View
    private lateinit var layoutCustomerCard: MaterialCardView
    private lateinit var layoutSelectCustomer: MaterialCardView
    private lateinit var bottomNav: BottomNavigationView

    private var isScanning = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanTimeout = Runnable { forceStopScan() }

    private lateinit var securePrefs: SecurePreferences
    private lateinit var productRepository: ProductRepository
    private lateinit var orderRepository: OrderRepository

    private val customerPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val customerId = result.data?.getStringExtra("customer_id") ?: return@registerForActivityResult
            val customerName = result.data?.getStringExtra("customer_name") ?: return@registerForActivityResult
            val customerAddress = result.data?.getStringExtra("customer_address")
            securePrefs.setActiveCustomer(customerId, customerName, customerAddress)
            updateCustomerUi()
        }
    }

    private val sessionExpiredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == RetrofitClient.ACTION_SESSION_EXPIRED) {
                securePrefs.clearAll()
                startActivity(Intent(this@MainActivity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val dwReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                DW_RESULT_ACTION -> {
                    var data = intent.getStringExtra(DW_EXTRA_DATA)
                    if (data.isNullOrBlank()) data = intent.getStringExtra(DW_EXTRA_DATA_ALT) ?: ""
                    var labelType = intent.getStringExtra(DW_EXTRA_LABEL_TYPE)
                    if (labelType.isNullOrBlank()) labelType = intent.getStringExtra(DW_EXTRA_LABEL_TYPE_ALT) ?: ""
                    onBarcode(data ?: "", labelType ?: "")
                }
                DW_RESULT -> {
                    val result = intent.getStringExtra("RESULT") ?: ""
                    onDwResult(result)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        securePrefs = SecurePreferences(this)
        if (securePrefs.getToken() == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.bottomNav)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, b.bottom)
            insets
        }

        val db = AppDatabase.getInstance(this)
        productRepository = ProductRepository(db)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)
        orderRepository = OrderRepository(db, securePrefs)
        // Limpiar cache de productos con más de 7 días
        ProductDao(db).deleteOldCache(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)

        SyncWorker.enqueue(this)
        OrderStatusWorker.enqueue(this)
        requestNotificationPermission()
        requestBluetoothPermission()

        registerSessionExpiredReceiver()
        initViews()
        registerReceiver()
        setupDataWedge()
        updateLastScan()
        updateCustomerUi()
    }

    override fun onResume() {
        super.onResume()
        if (securePrefs.getToken() == null) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }
        updateBadge()
        resetScanState()
        updateCustomerUi()
        bottomNav.selectedItemId = R.id.nav_scanner
        bannerOffline.visibility = if (securePrefs.isOfflineMode()) View.VISIBLE else View.GONE
        refreshCompanySettings()
    }

    private fun refreshCompanySettings() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getCompanySettings()
                if (resp.isSuccessful) {
                    resp.body()?.data?.let { d ->
                        securePrefs.saveCompanySettings(d.companyName, d.subtitle, d.address, d.phone, d.city)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanHandler.removeCallbacks(scanTimeout)
        try { unregisterReceiver(dwReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(sessionExpiredReceiver) } catch (_: Exception) {}
    }

    private fun registerSessionExpiredReceiver() {
        val filter = IntentFilter(RetrofitClient.ACTION_SESSION_EXPIRED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionExpiredReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(sessionExpiredReceiver, filter)
        }
    }

    private fun updateBadge() {
        val count = orderRepository.getPendingCount()
        val badge = bottomNav.getOrCreateBadge(R.id.nav_order)
        if (count > 0) {
            badge.isVisible = true
            badge.number = count
        } else {
            badge.isVisible = false
        }
    }

    private fun updateCustomerUi() {
        val customerId = securePrefs.getActiveCustomerId()
        val customerName = securePrefs.getActiveCustomerName()

        if (customerId != null && customerName != null) {
            layoutCustomerCard.visibility = View.VISIBLE
            layoutSelectCustomer.visibility = View.GONE
            tvCustomerName.text = customerName
            enableScanning(true)
        } else {
            layoutCustomerCard.visibility = View.GONE
            layoutSelectCustomer.visibility = View.VISIBLE
            enableScanning(false)
        }
    }

    private fun enableScanning(enabled: Boolean) {
        btnHoldScan.isEnabled = enabled
        btnHoldScan.alpha = if (enabled) 1f else 0.4f
        btnManualEntry.isEnabled = enabled
        btnManualEntry.alpha = if (enabled) 1f else 0.4f
        tvScanPrompt.text = if (enabled) getString(R.string.label_ready_to_scan) else getString(R.string.label_select_customer_first)
    }

    private fun initViews() {
        viewStatusDot   = findViewById(R.id.viewStatusDot)
        tvStatus        = findViewById(R.id.tvStatus)
        bannerOffline   = findViewById(R.id.bannerOffline)
        tvScanPrompt    = findViewById(R.id.tvScanPrompt)
        tvLastBarcode   = findViewById(R.id.tvLastBarcode)
        tvLastProduct   = findViewById(R.id.tvLastProduct)
        tvLastTime      = findViewById(R.id.tvLastTime)
        tvHoldState     = findViewById(R.id.tvHoldState)
        tvCustomerName  = findViewById(R.id.tvCustomerName)
        btnScan         = findViewById(R.id.btnScan)
        btnHoldScan     = findViewById(R.id.btnHoldScan)
        btnManualEntry  = findViewById(R.id.btnManualEntry)
        layoutLastScan  = findViewById(R.id.layoutLastScan)
        bottomNav       = findViewById(R.id.bottomNav)
        layoutCustomerCard = findViewById(R.id.layoutCustomerCard)
        layoutSelectCustomer = findViewById(R.id.layoutSelectCustomer)
        btnSelectCustomer    = findViewById(R.id.btnSelectCustomer)
        btnChangeCustomer    = findViewById(R.id.btnChangeCustomer)
        btnViewClientHistory = findViewById(R.id.btnViewClientHistory)
        btnPreOrders         = findViewById(R.id.btnPreOrders)

        btnHoldScan.setOnClickListener { toggleScan() }
        btnScan.setOnClickListener { showGuide() }
        btnManualEntry.setOnClickListener { showManualEntryDialog() }

        // Búsqueda por nombre (long press en manual entry)
        btnManualEntry.setOnLongClickListener {
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.label_searching_by_name), Snackbar.LENGTH_SHORT).show()
            showProductSearchDialog()
            true
        }

        // Resumen del día (long press en último escaneo)
        layoutLastScan.setOnLongClickListener {
            showDailySummary()
            true
        }

        // Mostrar tips de long press una sola vez
        val prefs = getSharedPreferences("tips", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("shown_longpress_tips", false)) {
            prefs.edit().putBoolean("shown_longpress_tips", true).apply()
            com.google.android.material.snackbar.Snackbar.make(
                findViewById(android.R.id.content),
                getString(R.string.tip_longpress),
                com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
            ).setAction(getString(R.string.btn_understood)) {}.show()
        }

        btnSelectCustomer.setOnClickListener {
            customerPickerLauncher.launch(Intent(this, CustomerPickerActivity::class.java))
        }
        btnChangeCustomer.setOnClickListener {
            customerPickerLauncher.launch(Intent(this, CustomerPickerActivity::class.java))
        }
        btnViewClientHistory.setOnClickListener {
            val id   = securePrefs.getActiveCustomerId() ?: return@setOnClickListener
            val name = securePrefs.getActiveCustomerName() ?: return@setOnClickListener
            startActivity(Intent(this, ClientHistoryActivity::class.java).apply {
                putExtra("customer_id", id)
                putExtra("customer_name", name)
            })
        }
        btnPreOrders.setOnClickListener {
            startActivity(Intent(this, PreOrderListActivity::class.java))
        }

        layoutLastScan.setOnClickListener {
            val lastBarcode = tvLastBarcode.text.toString()
            if (lastBarcode.isNotEmpty()) openDetail(lastBarcode)
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_scanner -> true
                R.id.nav_order -> {
                    startActivity(Intent(this, CurrentOrderActivity::class.java))
                    false
                }
                R.id.nav_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    false
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    false
                }
                else -> false
            }
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(DW_RESULT_ACTION)
            addAction(DW_RESULT)
            addCategory(DW_CATEGORY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dwReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(dwReceiver, filter)
        }
    }

    private fun setupDataWedge() {
        val installed = try {
            packageManager.getPackageInfo(DW_PACKAGE, 0); true
        } catch (_: PackageManager.NameNotFoundException) { false }

        if (installed) {
            sendDw(DW_CREATE, Bundle().apply {
                putString("PROFILE_NAME", DW_PROFILE)
                putString("PROFILE_ENABLED", "true")
                putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")
            })
            sendDw(DW_CONFIGURE, Bundle().apply {
                putString("PROFILE_NAME", DW_PROFILE)
                putString("PROFILE_ENABLED", "true")
                putString("CONFIG_MODE", "UPDATE")
                putParcelableArray("APP_LIST", arrayOf(Bundle().apply {
                    putString("PACKAGE_NAME", packageName)
                    putStringArray("ACTIVITY_LIST", arrayOf("*"))
                }))
                putParcelableArray("PLUGIN_CONFIG", arrayOf(
                    Bundle().apply {
                        putString("PLUGIN_NAME", "BARCODE")
                        putString("RESET_CONFIG", "true")
                        putBundle("PARAM_LIST", Bundle().apply {
                            putString("scanner_selection", "auto")
                            putString("decoder_ean8", "true")
                            putString("decoder_ean13", "true")
                            putString("decoder_code128", "true")
                            putString("decoder_code39", "true")
                        })
                    },
                    Bundle().apply {
                        putString("PLUGIN_NAME", "INTENT")
                        putString("RESET_CONFIG", "true")
                        putBundle("PARAM_LIST", Bundle().apply {
                            putString("intent_output_enabled", "true")
                            putString("intent_action", DW_RESULT_ACTION)
                            putString("intent_delivery", "2")
                        })
                    }
                ))
            })
            setStatus(true, getString(R.string.status_online))
        } else {
            setStatus(false, getString(R.string.status_offline_dw))
        }
    }

    private fun toggleScan() {
        isScanning = !isScanning
        scanHandler.removeCallbacks(scanTimeout)
        if (isScanning) {
            tvHoldState.visibility = View.VISIBLE
            btnHoldScan.setBackgroundResource(R.drawable.bg_scan_ring)
            btnHoldScan.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            softScanStart()
            scanHandler.postDelayed(scanTimeout, 7000)
        } else {
            tvHoldState.visibility = View.GONE
            btnHoldScan.setBackgroundResource(R.drawable.bg_scan_circle)
            softScanStop()
        }
    }

    private fun forceStopScan() {
        if (isScanning) {
            isScanning = false
            tvHoldState.visibility = View.GONE
            btnHoldScan.setBackgroundResource(R.drawable.bg_scan_circle)
            softScanStop()
        }
    }

    private fun resetScanState() {
        scanHandler.removeCallbacks(scanTimeout)
        if (isScanning) {
            isScanning = false
            tvHoldState.visibility = View.GONE
            btnHoldScan.setBackgroundResource(R.drawable.bg_scan_circle)
            softScanStop()
        }
    }

    private fun softScanStart() {
        sendBroadcast(Intent(DW_API_ACTION).apply {
            putExtra("com.symbol.datawedge.api.SOFT_SCAN_TRIGGER", "START_SCANNING")
            setPackage(DW_PACKAGE)
        })
    }

    private fun softScanStop() {
        sendBroadcast(Intent(DW_API_ACTION).apply {
            putExtra("com.symbol.datawedge.api.SOFT_SCAN_TRIGGER", "STOP_SCANNING")
            setPackage(DW_PACKAGE)
        })
    }

    private fun sendDw(extra: String, value: Bundle) {
        sendBroadcast(Intent(DW_API_ACTION).apply {
            putExtra(extra, value)
            setPackage(DW_PACKAGE)
        })
    }

    private fun setStatus(connected: Boolean, label: String) {
        tvStatus.text = label
        tvStatus.setTextColor(getColor(if (connected) R.color.success else R.color.red))
        viewStatusDot.setBackgroundResource(
            if (connected) R.drawable.circle_green else R.drawable.circle_gray
        )
    }

    private fun showGuide() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_configure_datawedge))
            .setMessage(getString(R.string.msg_configure_datawedge, DW_PROFILE, DW_RESULT_ACTION))
            .setPositiveButton(getString(R.string.btn_understood), null)
            .show()
    }

    private fun onBarcode(data: String, labelType: String) {
        runOnUiThread {
            if (data.isBlank() || data.equals("null", true)) return@runOnUiThread
            resetScanState()
            openDetail(data)
        }
    }

    private fun openDetail(barcode: String, quantity: Double = 1.0) {
        lifecycleScope.launch {
            val product = productRepository.findByBarcode(barcode)
            if (product == null) {
                runOnUiThread { showProductNotFound(barcode) }
                return@launch
            }
            val initialQty = product.weightPerUnit?.takeIf { it > 0 } ?: quantity
            securePrefs.saveLastScan(barcode, product.name)
            updateLastScan()
            startActivity(
                Intent(this@MainActivity, ProductDetailActivity::class.java).apply {
                    putExtra("BARCODE", barcode)
                    putExtra("PRODUCT_NAME", product.name)
                    putExtra("PRODUCT_PRICE", product.price)
                    putExtra("QUANTITY", initialQty)
                    putExtra("STOCK", product.stock)
                    putExtra("CUSTOMER_ID", securePrefs.getActiveCustomerId())
                    putExtra("CUSTOMER_NAME", securePrefs.getActiveCustomerName())
                }
            )
        }
    }

    private fun showProductNotFound(barcode: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_product_not_found))
            .setMessage(getString(R.string.msg_product_not_found, barcode))
            .setPositiveButton(getString(R.string.btn_retry)) { _, _ -> showManualEntryDialog() }
            .setNegativeButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun onDwResult(result: String) {
        runOnUiThread {
            setStatus(result == "TRUE", if (result == "TRUE") getString(R.string.status_online) else getString(R.string.status_dw_error))
        }
    }

    private fun showManualEntryDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_entry, null)
        val etBarcode = dialogView.findViewById<android.widget.EditText>(R.id.etBarcode)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_manual_entry))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.btn_search)) { _, _ ->
                val barcode = etBarcode.text.toString().trim()
                if (barcode.isNotEmpty()) openDetail(barcode)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    fun showProductSearchDialog() {
        val ctx = this
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val etSearch = android.widget.EditText(ctx).apply {
            hint = getString(R.string.hint_product_name_search)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val tvResults = android.widget.TextView(ctx).apply {
            textSize = 13f
            setPadding(0, 12, 0, 0)
            setTextColor(getColor(R.color.text_secondary))
            text = getString(R.string.label_type_to_search)
        }
        layout.addView(etSearch)
        layout.addView(tvResults)

        var foundProducts: List<com.example.test.data.ProductDto> = emptyList()

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.title_search_product_by_name))
            .setView(layout)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: return
                if (query.length < 2) { tvResults.text = getString(R.string.label_type_at_least_2); return }
                tvResults.text = getString(R.string.label_searching)
                lifecycleScope.launch {
                    try {
                        val resp = RetrofitClient.getApi().searchProducts(query)
                        if (resp.isSuccessful) {
                            foundProducts = resp.body()?.data ?: emptyList()
                            if (foundProducts.isEmpty()) {
                                tvResults.text = getString(R.string.label_no_results, query)
                            } else {
                                tvResults.text = foundProducts.joinToString("\n\n") { p ->
                                    "▸ ${p.name}\n   ${"$"}${String.format(java.util.Locale.US, "%.2f", p.price)}/lb   ${p.barcode ?: getString(R.string.no_barcode_label)}"
                                }
                            }
                        }
                    } catch (_: Exception) {
                        tvResults.text = getString(R.string.label_search_error)
                    }
                }
            }
        })

        tvResults.setOnClickListener {
            if (foundProducts.size == 1) {
                foundProducts[0].barcode?.let { barcode ->
                    dialog.dismiss()
                    openDetail(barcode)
                }
            } else if (foundProducts.isNotEmpty()) {
                val names = foundProducts.map { "${it.name}  ($${String.format(java.util.Locale.US,"%.2f",it.price)}/lb)" }.toTypedArray()
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle(getString(R.string.title_select_product))
                    .setItems(names) { _, i ->
                        dialog.dismiss()
                        foundProducts[i].barcode?.let { barcode -> openDetail(barcode) }
                    }
                    .show()
            }
        }
        dialog.show()
    }

    fun showDailySummary() {
        val today = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US).format(java.util.Date())
        val pendingCount = orderRepository.getPendingCount()

        lifecycleScope.launch {
            var sentToday = 0
            var revenueToday = 0.0
            try {
                val resp = RetrofitClient.getApi().getStats()
                if (resp.isSuccessful) {
                    resp.body()?.kpis?.let { k ->
                        sentToday   = k.ordersToday
                        revenueToday = k.revenueToday
                    }
                }
            } catch (_: Exception) {}

            val msg = getString(
                R.string.msg_daily_summary,
                today,
                sentToday,
                String.format(java.util.Locale.US, "%.2f", revenueToday),
                pendingCount
            )

            runOnUiThread {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(getString(R.string.title_daily_summary))
                    .setMessage(msg)
                    .setPositiveButton(getString(R.string.btn_close), null)
                    .show()
            }
        }
    }

    private fun updateLastScan() {
        val barcode = securePrefs.getLastScanBarcode()
        val name    = securePrefs.getLastScanName()
        val time    = securePrefs.getLastScanTime()
        if (barcode.isNullOrBlank() || name.isNullOrBlank()) {
            layoutLastScan.visibility = View.GONE
            return
        }
        tvLastBarcode.text = barcode
        tvLastProduct.text = name
        tvLastTime.text    = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(time))
        layoutLastScan.visibility = View.VISIBLE
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }
}
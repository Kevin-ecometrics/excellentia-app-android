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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.local.AppDatabase
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.example.test.data.repository.ProductRepository
import com.example.test.data.repository.OrderRepository
import com.example.test.data.sync.SyncWorker
import com.example.test.data.sync.OrderStatusWorker
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

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
            securePrefs.setActiveCustomer(customerId, customerName)
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
        val pad = resources.getDimensionPixelSize(R.dimen.padding_screen)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left + pad, b.top, b.right + pad, b.bottom)
            insets
        }

        val db = AppDatabase.getInstance(this)
        productRepository = ProductRepository(db)
        RetrofitClient.initialize(securePrefs.getBackendUrl(), securePrefs, this)
        orderRepository = OrderRepository(db, securePrefs)

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
        tvScanPrompt.text = if (enabled) "Listo para escanear" else "Selecciona un cliente primero"
    }

    private fun initViews() {
        viewStatusDot   = findViewById(R.id.viewStatusDot)
        tvStatus        = findViewById(R.id.tvStatus)
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
        btnSelectCustomer = findViewById(R.id.btnSelectCustomer)
        btnChangeCustomer = findViewById(R.id.btnChangeCustomer)

        btnHoldScan.setOnClickListener { toggleScan() }
        btnScan.setOnClickListener { showGuide() }
        btnManualEntry.setOnClickListener { showManualEntryDialog() }

        btnSelectCustomer.setOnClickListener {
            customerPickerLauncher.launch(Intent(this, CustomerPickerActivity::class.java))
        }
        btnChangeCustomer.setOnClickListener {
            customerPickerLauncher.launch(Intent(this, CustomerPickerActivity::class.java))
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
            setStatus(true, "ONLINE")
        } else {
            setStatus(false, "NO DW")
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
        AlertDialog.Builder(this)
            .setTitle("Configurar escáner DataWedge")
            .setMessage(
                "Para usar el escáner físico del Zebra:\n\n" +
                "1. Abre la app DataWedge\n" +
                "2. Crea un perfil \"$DW_PROFILE\"\n" +
                "3. Asocia la app y activa Intent output\n" +
                "   Acción: $DW_RESULT_ACTION\n" +
                "   Delivery: Broadcast\n\n" +
                "O usa el botón 'Ingresar código' para entrada manual."
            )
            .setPositiveButton("Entendido", null)
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
            startActivity(
                Intent(this@MainActivity, ProductDetailActivity::class.java).apply {
                    putExtra("BARCODE", barcode)
                    putExtra("PRODUCT_NAME", product.name)
                    putExtra("PRODUCT_PRICE", product.price)
                    putExtra("QUANTITY", initialQty)
                    putExtra("CUSTOMER_ID", securePrefs.getActiveCustomerId())
                    putExtra("CUSTOMER_NAME", securePrefs.getActiveCustomerName())
                }
            )
        }
    }

    private fun showProductNotFound(barcode: String) {
        AlertDialog.Builder(this)
            .setTitle("Producto no encontrado")
            .setMessage("El código \"$barcode\" no existe en el catálogo.\n\nVerifica que el producto esté registrado en el sistema.")
            .setPositiveButton("Reintentar") { _, _ -> showManualEntryDialog() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun onDwResult(result: String) {
        runOnUiThread {
            setStatus(result == "TRUE", if (result == "TRUE") "ONLINE" else "ERROR")
        }
    }

    private fun showManualEntryDialog() {
        val margin = resources.getDimensionPixelSize(R.dimen.padding_screen)
        val etBarcode = EditText(this).apply {
            setBackgroundResource(R.drawable.bg_edittext)
            setPadding(margin, 12, margin, 12)
            hint = getString(R.string.hint_barcode)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(margin, margin / 2, margin, margin / 2)
            addView(etBarcode, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("Ingresar código de barras")
            .setView(container)
            .setPositiveButton("Buscar") { _, _ ->
                val barcode = etBarcode.text.toString().trim()
                if (barcode.isNotEmpty()) openDetail(barcode)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateLastScan() {
        layoutLastScan.visibility = View.GONE
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
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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.PreOrderItem
import com.example.test.data.PreOrderRequest
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class CreatePreOrderActivity : AppCompatActivity() {

    private lateinit var cardCustomer: MaterialCardView
    private lateinit var tvSelectedCustomer: TextView
    private lateinit var btnPickDate: MaterialButton
    private lateinit var tvSelectedDate: TextView
    private lateinit var etNotes: TextInputEditText
    private lateinit var layoutItems: LinearLayout
    private lateinit var tvNoItems: TextView
    private lateinit var tvTotalEstimated: TextView
    private lateinit var btnAddItem: MaterialButton
    private lateinit var btnSavePreOrder: MaterialButton
    private lateinit var securePrefs: SecurePreferences

    private var selectedCustomerId: String? = null
    private var selectedCustomerName: String? = null
    private var selectedDate: String? = null
    private val items = mutableListOf<PreOrderItem>()

    private companion object {
        const val DW_RESULT_ACTION = "com.symbol.datawedge.datawedge.ACTION_RESULT"
        const val DW_EXTRA_DATA    = "com.symbol.datawedge.data_string"
    }

    private val dwReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DW_RESULT_ACTION) {
                val data = intent.getStringExtra(DW_EXTRA_DATA) ?: return
                if (data.isNotBlank()) lookupAndAddItem(data)
            }
        }
    }

    private val customerPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedCustomerId   = result.data?.getStringExtra("customer_id")
            selectedCustomerName = result.data?.getStringExtra("customer_name")
            tvSelectedCustomer.text = selectedCustomerName ?: "Cliente seleccionado"
            tvSelectedCustomer.setTextColor(getColor(R.color.text_primary))
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
        btnPickDate       = findViewById(R.id.btnPickDate)
        tvSelectedDate    = findViewById(R.id.tvSelectedDate)
        etNotes           = findViewById(R.id.etNotes)
        layoutItems       = findViewById(R.id.layoutItems)
        tvNoItems         = findViewById(R.id.tvNoItems)
        tvTotalEstimated  = findViewById(R.id.tvTotalEstimated)
        btnAddItem        = findViewById(R.id.btnAddItem)
        btnSavePreOrder   = findViewById(R.id.btnSavePreOrder)

        // Pre-fill active customer if any
        securePrefs.getActiveCustomerId()?.let { id ->
            selectedCustomerId   = id
            selectedCustomerName = securePrefs.getActiveCustomerName()
            tvSelectedCustomer.text = selectedCustomerName ?: ""
            tvSelectedCustomer.setTextColor(getColor(R.color.text_primary))
        }

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        cardCustomer.setOnClickListener {
            customerPickerLauncher.launch(Intent(this, CustomerPickerActivity::class.java))
        }

        btnPickDate.setOnClickListener { showDatePicker() }
        btnAddItem.setOnClickListener { showAddItemDialog() }
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

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            selectedDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
            tvSelectedDate.text = "Entrega: $selectedDate"
            tvSelectedDate.visibility = View.VISIBLE
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).also {
            it.datePicker.minDate = cal.timeInMillis
        }.show()
    }

    private fun showAddItemDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val etBarcode = EditText(this).apply { hint = "Barcode o código" }
        val etQty = EditText(this).apply {
            hint = "Cantidad (lb)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val etPrice = EditText(this).apply {
            hint = "Precio/lb"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(etBarcode)
        layout.addView(etQty)
        layout.addView(etPrice)

        AlertDialog.Builder(this)
            .setTitle("Agregar producto")
            .setView(layout)
            .setPositiveButton("Agregar") { _, _ ->
                val barcode = etBarcode.text.toString().trim()
                val qty     = etQty.text.toString().toDoubleOrNull() ?: 0.0
                val price   = etPrice.text.toString().toDoubleOrNull() ?: 0.0
                if (barcode.isNotBlank() && qty > 0 && price > 0) {
                    lookupAndAddItemWithHint(barcode, qty, price)
                } else {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Ingresa barcode, cantidad y precio válidos",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun lookupAndAddItemWithHint(barcode: String, qty: Double, price: Double) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getProductByBarcode(barcode)
                val productName = if (resp.isSuccessful) {
                    resp.body()?.data?.name ?: barcode
                } else barcode
                addItem(PreOrderItem(barcode, productName, price, qty, price * qty))
            } catch (_: Exception) {
                addItem(PreOrderItem(barcode, barcode, price, qty, price * qty))
            }
        }
    }

    private fun lookupAndAddItem(barcode: String) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().getProductByBarcode(barcode)
                if (resp.isSuccessful) {
                    val product = resp.body()?.data ?: return@launch
                    runOnUiThread { showQtyDialog(barcode, product.name, product.price) }
                } else {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Producto no encontrado: $barcode",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "Error al buscar producto",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showQtyDialog(barcode: String, name: String, defaultPrice: Double) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val etQty = EditText(this).apply {
            hint = "Cantidad (lb)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val etPrice = EditText(this).apply {
            hint = "Precio/lb"
            setText(String.format(Locale.US, "%.2f", defaultPrice))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(etQty)
        layout.addView(etPrice)

        AlertDialog.Builder(this)
            .setTitle(name)
            .setView(layout)
            .setPositiveButton("Agregar") { _, _ ->
                val qty   = etQty.text.toString().toDoubleOrNull() ?: 0.0
                val price = etPrice.text.toString().toDoubleOrNull() ?: defaultPrice
                if (qty > 0) addItem(PreOrderItem(barcode, name, price, qty, price * qty))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun addItem(item: PreOrderItem) {
        items.add(item)
        tvNoItems.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rebuildItemsList()
        updateTotal()
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

            val tvItem = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "${item.productName}\n${String.format(Locale.US, "%.2f lb × $%.2f = $%.2f", item.quantity, item.price, item.total)}"
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
                    updateTotal()
                }
            }

            row.addView(tvItem)
            row.addView(btnRemove)
            layoutItems.addView(row)
        }
    }

    private fun updateTotal() {
        val total = items.sumOf { it.total }
        tvTotalEstimated.text = String.format(Locale.US, "$%.2f", total)
    }

    private fun savePreOrder() {
        if (selectedCustomerId == null || selectedCustomerName == null) {
            Snackbar.make(
                findViewById(android.R.id.content),
                "Selecciona un cliente primero",
                Snackbar.LENGTH_SHORT
            ).show()
            return
        }
        if (items.isEmpty()) {
            Snackbar.make(
                findViewById(android.R.id.content),
                "Agrega al menos un producto",
                Snackbar.LENGTH_SHORT
            ).show()
            return
        }

        btnSavePreOrder.isEnabled = false
        btnSavePreOrder.text = "Guardando..."

        val request = PreOrderRequest(
            customerId    = selectedCustomerId!!,
            customerName  = selectedCustomerName!!,
            scheduledDate = selectedDate,
            notes         = etNotes.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            items         = items.toList()
        )

        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.getApi().createPreOrder(request)
                if (resp.isSuccessful) {
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Error ${resp.code()}",
                        Snackbar.LENGTH_LONG
                    ).show()
                    btnSavePreOrder.isEnabled = true
                    btnSavePreOrder.text = "Guardar pre-orden"
                }
            } catch (e: Exception) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    e.localizedMessage ?: "Error de conexión",
                    Snackbar.LENGTH_LONG
                ).show()
                btnSavePreOrder.isEnabled = true
                btnSavePreOrder.text = "Guardar pre-orden"
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

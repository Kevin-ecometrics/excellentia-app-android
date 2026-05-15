package com.example.test

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.local.SecurePreferences
import com.example.test.data.print.PrintService
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_SCANNER_MODE = "scanner_mode"
    }

    private lateinit var etBackendUrl: EditText
    private lateinit var etJwtToken: EditText
    private lateinit var tvPrinterName: TextView
    private lateinit var radioScannerMode: RadioGroup
    private lateinit var switchOffline: Switch
    private lateinit var btnSave: MaterialButton
    private lateinit var btnLogout: MaterialButton
    private lateinit var btnSelectPrinter: MaterialButton
    private lateinit var btnTestPrinter: MaterialButton
    private lateinit var btnBack: ImageButton
    private lateinit var securePrefs: SecurePreferences

    private val btPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showBluetoothDevicePicker()
        else Toast.makeText(this, "Permiso Bluetooth requerido", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        val pad = resources.getDimensionPixelSize(R.dimen.padding_screen)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad)
            insets
        }

        securePrefs = SecurePreferences(this)

        etBackendUrl = findViewById(R.id.etBackendUrl)
        etJwtToken = findViewById(R.id.etJwtToken)
        tvPrinterName = findViewById(R.id.tvPrinterName)
        radioScannerMode = findViewById(R.id.radioScannerMode)
        switchOffline = findViewById(R.id.switchOffline)
        btnSave = findViewById(R.id.btnSave)
        btnLogout = findViewById(R.id.btnLogout)
        btnSelectPrinter = findViewById(R.id.btnSelectPrinter)
        btnTestPrinter = findViewById(R.id.btnTestPrinter)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener { finish() }
        btnSave.setOnClickListener { saveSettings() }
        btnLogout.setOnClickListener { logout() }
        btnSelectPrinter.setOnClickListener { requestBluetoothAndPick() }
        btnTestPrinter.setOnClickListener { testPrinter() }

        loadSettings()
    }

    @SuppressLint("HardwareIds")
    private fun loadSettings() {
        etBackendUrl.setText(securePrefs.getBackendUrl())
        etJwtToken.setText(securePrefs.getToken() ?: "")

        val savedName = securePrefs.getPrinterName()
        tvPrinterName.text = savedName ?: "Sin impresora seleccionada"
        tvPrinterName.setTextColor(
            if (savedName != null) getColor(R.color.text_primary)
            else getColor(R.color.text_secondary)
        )

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SCANNER_MODE, "datawedge") == "emdk") {
            radioScannerMode.check(R.id.rbEMDK)
        } else {
            radioScannerMode.check(R.id.rbDataWedge)
        }
        switchOffline.isChecked = securePrefs.isOfflineMode()

        // Device info
        findViewById<TextView>(R.id.tvDeviceModel).text =
            "${Build.MANUFACTURER.replaceFirstChar { it.uppercase(Locale.ROOT) }} ${Build.MODEL}"
        findViewById<TextView>(R.id.tvAndroidVersion).text =
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val serial = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "—"
            else {
                @Suppress("DEPRECATION")
                Build.SERIAL.takeIf { it != Build.UNKNOWN } ?: "—"
            }
        } catch (_: Exception) { "—" }
        findViewById<TextView>(R.id.tvDeviceSerial).text = serial

        // App version
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "—" }
        findViewById<TextView>(R.id.tvAppVersion).text = versionName
    }

    private fun saveSettings() {
        securePrefs.saveBackendUrl(etBackendUrl.text.toString().trim())
        securePrefs.saveToken(etJwtToken.text.toString().trim())
        securePrefs.saveOfflineMode(switchOffline.isChecked)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = if (radioScannerMode.checkedRadioButtonId == R.id.rbEMDK) "emdk" else "datawedge"
        prefs.edit().putString(KEY_SCANNER_MODE, mode).apply()

        Toast.makeText(this, "Ajustes guardados", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun requestBluetoothAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        showBluetoothDevicePicker()
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDevicePicker() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Activa el Bluetooth del dispositivo", Toast.LENGTH_SHORT).show()
            return
        }

        val paired = adapter.bondedDevices.toList()
        if (paired.isEmpty()) {
            Toast.makeText(this, "No hay dispositivos emparejados. Empareja la ZQ630 en Ajustes → Bluetooth.", Toast.LENGTH_LONG).show()
            return
        }

        val names = paired.map { "${it.name ?: "Desconocido"}  (${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Seleccionar impresora")
            .setItems(names) { _, index ->
                val device = paired[index]
                securePrefs.savePrinterAddress(device.address)
                securePrefs.savePrinterName(device.name ?: device.address)
                tvPrinterName.text = device.name ?: device.address
                tvPrinterName.setTextColor(getColor(R.color.text_primary))
                Toast.makeText(this, "Impresora seleccionada: ${device.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun testPrinter() {
        val address = securePrefs.getPrinterAddress()
        if (address.isNullOrBlank()) {
            Toast.makeText(this, "Selecciona una impresora primero", Toast.LENGTH_SHORT).show()
            return
        }
        btnTestPrinter.isEnabled = false
        btnTestPrinter.text = "Enviando…"

        lifecycleScope.launch {
            val result = PrintService.printTest(this@SettingsActivity, address)
            result.onSuccess {
                Toast.makeText(this@SettingsActivity, "✓ Página de prueba enviada", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(this@SettingsActivity, "Error: ${e.localizedMessage ?: "No se pudo conectar"}", Toast.LENGTH_LONG).show()
            }
            btnTestPrinter.isEnabled = true
            btnTestPrinter.text = "Imprimir página de prueba"
        }
    }

    private fun logout() {
        securePrefs.clearAll()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}

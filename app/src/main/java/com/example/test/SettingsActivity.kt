package com.example.test

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import com.google.android.material.appbar.MaterialToolbar
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
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


    private lateinit var etBackendUrl: EditText
    private lateinit var tvPrinterName: TextView
    private lateinit var switchOffline: SwitchCompat
    private lateinit var tvAccountAvatar: TextView
    private lateinit var tvAccountName: TextView
    private lateinit var tvAccountEmail: TextView
    private lateinit var tvAccountRole: TextView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnLogout: MaterialButton
    private lateinit var btnSelectPrinter: MaterialButton
    private lateinit var btnTestPrinter: MaterialButton
    private lateinit var securePrefs: SecurePreferences

    private val btPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showBluetoothDevicePicker()
        else Snackbar.make(findViewById(android.R.id.content), "Permiso Bluetooth requerido", Snackbar.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        securePrefs = SecurePreferences(this)

        etBackendUrl   = findViewById(R.id.etBackendUrl)
        tvPrinterName  = findViewById(R.id.tvPrinterName)
        switchOffline  = findViewById(R.id.switchOffline)
        tvAccountAvatar = findViewById(R.id.tvAccountAvatar)
        tvAccountName   = findViewById(R.id.tvAccountName)
        tvAccountEmail  = findViewById(R.id.tvAccountEmail)
        tvAccountRole   = findViewById(R.id.tvAccountRole)
        btnSave = findViewById(R.id.btnSave)
        btnLogout = findViewById(R.id.btnLogout)
        btnSelectPrinter = findViewById(R.id.btnSelectPrinter)
        btnTestPrinter = findViewById(R.id.btnTestPrinter)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        btnSave.setOnClickListener { saveSettings() }
        btnLogout.setOnClickListener { logout() }
        findViewById<MaterialButton>(R.id.btnChangePassword).setOnClickListener {
            startActivity(Intent(this, ChangePasswordActivity::class.java))
        }
        findViewById<android.widget.ImageButton>(R.id.btnPrinterHelp).setOnClickListener {
            showPrinterHelp()
        }
        btnSelectPrinter.setOnClickListener { requestBluetoothAndPick() }
        btnTestPrinter.setOnClickListener { testPrinter() }

        loadSettings()
    }

    @SuppressLint("HardwareIds")
    private fun loadSettings() {
        // Cuenta del operador
        val userName  = securePrefs.getUserName()
        val userEmail = securePrefs.getUserEmail() ?: securePrefs.getToken()?.let { "—" } ?: "—"
        val userRole  = securePrefs.getUserRole() ?: "operator"
        val displayName = userName ?: userEmail
        tvAccountAvatar.text = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        tvAccountName.text   = if (!userName.isNullOrBlank()) userName else "Sin nombre"
        tvAccountEmail.text  = userEmail
        tvAccountRole.text   = if (userRole == "admin") "ADMIN" else "OPERADOR"

        etBackendUrl.setText(securePrefs.getBackendUrl())

        val savedName = securePrefs.getPrinterName()
        tvPrinterName.text = savedName ?: "Sin impresora seleccionada"
        tvPrinterName.setTextColor(
            if (savedName != null) getColor(R.color.text_primary)
            else getColor(R.color.text_secondary)
        )

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
        securePrefs.saveOfflineMode(switchOffline.isChecked)
        Snackbar.make(findViewById(android.R.id.content), "Ajustes guardados", Snackbar.LENGTH_SHORT).show()
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
            Snackbar.make(findViewById(android.R.id.content), "Activa el Bluetooth del dispositivo", Snackbar.LENGTH_SHORT).show()
            return
        }

        val paired = adapter.bondedDevices.toList()
        if (paired.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content), "No hay dispositivos emparejados. Empareja la ZQ630 en Ajustes → Bluetooth.", Snackbar.LENGTH_LONG).show()
            return
        }

        val names = paired.map { "${it.name ?: "Desconocido"}  (${it.address})" }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Seleccionar impresora")
            .setItems(names) { _, index ->
                val device = paired[index]
                securePrefs.savePrinterAddress(device.address)
                securePrefs.savePrinterName(device.name ?: device.address)
                tvPrinterName.text = device.name ?: device.address
                tvPrinterName.setTextColor(getColor(R.color.text_primary))
                Snackbar.make(findViewById(android.R.id.content), "Impresora: ${device.name}", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun testPrinter() {
        val address = securePrefs.getPrinterAddress()
        if (address.isNullOrBlank()) {
            Snackbar.make(findViewById(android.R.id.content), "Selecciona una impresora primero", Snackbar.LENGTH_SHORT).show()
            return
        }
        btnTestPrinter.isEnabled = false
        btnTestPrinter.text = "Enviando…"

        lifecycleScope.launch {
            val result = PrintService.printTest(this@SettingsActivity, address)
            result.onSuccess {
                Snackbar.make(findViewById(android.R.id.content), "✓ Página de prueba enviada", Snackbar.LENGTH_SHORT).show()
            }.onFailure { e ->
                Snackbar.make(findViewById(android.R.id.content), "Error: ${e.localizedMessage ?: "No se pudo conectar"}", Snackbar.LENGTH_LONG).show()
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

    private fun showPrinterHelp() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("¿Cómo agregar la impresora?")
            .setMessage(
                "Sigue estos pasos para conectar la Zebra ZQ630 Plus:\n\n" +
                "1️⃣  Enciende la impresora ZQ630 Plus.\n\n" +
                "2️⃣  Encuentra la dirección Bluetooth\n" +
                "     de la impresora. Hay dos formas:\n\n" +
                "     • En la parte trasera (donde va la\n" +
                "       batería) hay una etiqueta con un\n" +
                "       código de barras — la dirección\n" +
                "       está impresa ahí.\n\n" +
                "     • O accede al menú Bluetooth de la\n" +
                "       impresora para verla en pantalla.\n\n" +
                "     Ejemplo: 8C:D5:4A:1C:0C:85\n\n" +
                "3️⃣  En esta pantalla toca\n" +
                "     \"Seleccionar impresora\".\n\n" +
                "4️⃣  Aparecerá la lista de impresoras\n" +
                "     disponibles con su dirección Bluetooth.\n" +
                "     Selecciona la que coincide con la\n" +
                "     dirección del paso 2.\n\n" +
                "5️⃣  Toca \"Imprimir página de prueba\"\n" +
                "     para confirmar que funciona.\n\n" +
                "💡  Si no aparece tu impresora en la lista,\n" +
                "     verifica que esté encendida y cerca\n" +
                "     del dispositivo."
            )
            .setPositiveButton("Entendido", null)
            .show()
    }
}

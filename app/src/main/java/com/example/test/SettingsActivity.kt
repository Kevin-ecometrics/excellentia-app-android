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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.local.SecurePreferences
import com.example.test.data.print.PrintService
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsActivity : BaseActivity() {


    private lateinit var btnLangEn: com.google.android.material.button.MaterialButton
    private lateinit var btnLangEs: com.google.android.material.button.MaterialButton
    private lateinit var etBackendUrl: EditText
    private lateinit var tvPrinterName: TextView
    private lateinit var switchOffline: SwitchCompat
    private lateinit var tvAccountAvatar: TextView
    private lateinit var tvAccountName: TextView
    private lateinit var tvAccountEmail: TextView
    private lateinit var tvAccountRole: TextView
    private lateinit var etDisclaimer: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnLogout: MaterialButton
    private lateinit var btnSelectPrinter: MaterialButton
    private lateinit var btnTestPrinter: MaterialButton
    private lateinit var securePrefs: SecurePreferences

    private val btPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showBluetoothDevicePicker()
        else Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_bluetooth_required), Snackbar.LENGTH_SHORT).show()
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

        btnLangEn = findViewById(R.id.btnLangEn)
        btnLangEs = findViewById(R.id.btnLangEs)
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
        etDisclaimer = findViewById(R.id.etDisclaimer)
        findViewById<MaterialButton>(R.id.btnChangePassword).setOnClickListener {
            startActivity(Intent(this, ChangePasswordActivity::class.java))
        }
        findViewById<android.widget.ImageButton>(R.id.btnPrinterHelp).setOnClickListener {
            showPrinterHelp()
        }
        btnSelectPrinter.setOnClickListener { requestBluetoothAndPick() }
        btnTestPrinter.setOnClickListener { testPrinter() }

        btnLangEn.setOnClickListener { applyLanguage("en") }
        btnLangEs.setOnClickListener { applyLanguage("es") }

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
        tvAccountName.text   = if (!userName.isNullOrBlank()) userName else getString(R.string.label_no_name)
        tvAccountEmail.text  = userEmail
        tvAccountRole.text   = if (userRole == "admin") "ADMIN" else "OPERATOR"

        updateLanguageButtons(securePrefs.getLanguage())

        etBackendUrl.setText(securePrefs.getBackendUrl())

        val savedName = securePrefs.getPrinterName()
        tvPrinterName.text = savedName ?: getString(R.string.label_no_printer_selected)
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

        // Disclaimer
        etDisclaimer.setText(securePrefs.getDisclaimer() ?: "")

        // App version
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "—" }
        findViewById<TextView>(R.id.tvAppVersion).text = versionName
    }

    private fun saveSettings() {
        securePrefs.saveBackendUrl(etBackendUrl.text.toString().trim())
        securePrefs.saveOfflineMode(switchOffline.isChecked)
        securePrefs.saveDisclaimer(etDisclaimer.text.toString().trim().takeIf { it.isNotBlank() })
        Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_settings_saved), Snackbar.LENGTH_SHORT).show()
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
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_enable_bluetooth), Snackbar.LENGTH_SHORT).show()
            return
        }

        val paired = adapter.bondedDevices.toList()
        if (paired.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_no_paired_devices), Snackbar.LENGTH_LONG).show()
            return
        }

        val names = paired.map { "${it.name ?: getString(R.string.label_unknown_device)}  (${it.address})" }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_select_printer))
            .setItems(names) { _, index ->
                val device = paired[index]
                securePrefs.savePrinterAddress(device.address)
                securePrefs.savePrinterName(device.name ?: device.address)
                tvPrinterName.text = device.name ?: device.address
                tvPrinterName.setTextColor(getColor(R.color.text_primary))
                Snackbar.make(findViewById(android.R.id.content), getString(R.string.msg_printer_selected, device.name), Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun testPrinter() {
        val address = securePrefs.getPrinterAddress()
        if (address.isNullOrBlank()) {
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_select_printer_first), Snackbar.LENGTH_SHORT).show()
            return
        }
        btnTestPrinter.isEnabled = false
        btnTestPrinter.text = getString(R.string.btn_sending_ellipsis)

        lifecycleScope.launch {
            val result = PrintService.printTest(this@SettingsActivity, address)
            result.onSuccess {
                Snackbar.make(findViewById(android.R.id.content), getString(R.string.success_test_page_sent), Snackbar.LENGTH_SHORT).show()
            }.onFailure { e ->
                Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_print_test, e.localizedMessage ?: getString(R.string.error_no_connection)), Snackbar.LENGTH_LONG).show()
            }
            btnTestPrinter.isEnabled = true
            btnTestPrinter.text = getString(R.string.btn_test_printer)
        }
    }

    private fun applyLanguage(lang: String) {
        if (securePrefs.getLanguage() == lang) return
        securePrefs.saveLanguage(lang)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }

    private fun updateLanguageButtons(activeLang: String) {
        val colorPrimary = ContextCompat.getColor(this, R.color.primary)
        val colorSurface = ContextCompat.getColor(this, R.color.surface)
        val colorWhite   = ContextCompat.getColor(this, R.color.white)
        val colorPrimaryText = ContextCompat.getColor(this, R.color.text_primary)

        if (activeLang == "en") {
            btnLangEn.setBackgroundColor(colorPrimary)
            btnLangEn.setTextColor(colorWhite)
            btnLangEs.setBackgroundColor(colorSurface)
            btnLangEs.setTextColor(colorPrimaryText)
        } else {
            btnLangEs.setBackgroundColor(colorPrimary)
            btnLangEs.setTextColor(colorWhite)
            btnLangEn.setBackgroundColor(colorSurface)
            btnLangEn.setTextColor(colorPrimaryText)
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
            .setTitle(getString(R.string.title_printer_help))
            .setMessage(getString(R.string.msg_printer_help))
            .setPositiveButton(getString(R.string.printer_help_understood), null)
            .show()
    }
}

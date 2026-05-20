package com.example.test

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.ChangePasswordRequest
import com.example.test.data.network.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class ChangePasswordActivity : AppCompatActivity() {

    private lateinit var etCurrent: TextInputEditText
    private lateinit var etNew: TextInputEditText
    private lateinit var etConfirm: TextInputEditText
    private lateinit var tvError: TextView
    private lateinit var btnSave: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_change_password)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        etCurrent = findViewById(R.id.etCurrentPassword)
        etNew     = findViewById(R.id.etNewPassword)
        etConfirm = findViewById(R.id.etConfirmPassword)
        tvError   = findViewById(R.id.tvError)
        btnSave   = findViewById(R.id.btnSave)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        btnSave.setOnClickListener { attemptChange() }
    }

    private fun attemptChange() {
        val current = etCurrent.text.toString()
        val new     = etNew.text.toString()
        val confirm = etConfirm.text.toString()

        if (current.isBlank()) { showError("Ingresa tu contraseña actual"); return }
        if (new.length < 6)    { showError("La nueva contraseña debe tener al menos 6 caracteres"); return }
        if (new != confirm)    { showError("Las contraseñas no coinciden"); return }

        btnSave.isEnabled = false
        btnSave.text = "Guardando…"
        tvError.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.getApi().changePassword(
                    ChangePasswordRequest(currentPassword = current, newPassword = new)
                )
                if (response.isSuccessful) {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Contraseña actualizada correctamente",
                        Snackbar.LENGTH_SHORT
                    ).show()
                    finish()
                } else {
                    val msg = when (response.code()) {
                        401 -> "Contraseña actual incorrecta"
                        else -> "Error del servidor (${response.code()})"
                    }
                    showError(msg)
                }
            } catch (e: Exception) {
                showError("Sin conexión — verifica el servidor")
            } finally {
                btnSave.isEnabled = true
                btnSave.text = "Guardar contraseña"
            }
        }
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }
}

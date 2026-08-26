package com.example.test

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge

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

class ChangePasswordActivity : BaseActivity() {

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
            v.setPadding(b.left, 0, b.right, b.bottom)
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

        if (current.isBlank()) { showError(getString(R.string.error_enter_current_password)); return }
        if (new.length < 8)    { showError(getString(R.string.error_password_too_short)); return }
        if (new != confirm)    { showError(getString(R.string.error_passwords_dont_match)); return }

        btnSave.isEnabled = false
        btnSave.text = getString(R.string.btn_saving)
        tvError.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.getApi().changePassword(
                    ChangePasswordRequest(currentPassword = current, newPassword = new)
                )
                if (response.isSuccessful) {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        getString(R.string.success_password_changed),
                        Snackbar.LENGTH_SHORT
                    ).show()
                    finish()
                } else {
                    val msg = when (response.code()) {
                        401 -> getString(R.string.error_wrong_current_password)
                        else -> getString(R.string.error_server_code, response.code())
                    }
                    showError(msg)
                }
            } catch (e: Exception) {
                showError(getString(R.string.error_no_connection))
            } finally {
                btnSave.isEnabled = true
                btnSave.text = getString(R.string.btn_save_password)
            }
        }
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }
}

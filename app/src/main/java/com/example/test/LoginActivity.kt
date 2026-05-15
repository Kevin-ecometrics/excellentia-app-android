package com.example.test

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    private lateinit var etBackendUrl: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var tilEmail: TextInputLayout
    private lateinit var btnLogin: MaterialButton
    private lateinit var btnRetry: MaterialButton
    private lateinit var layoutLoading: View
    private lateinit var layoutError: View
    private lateinit var tvErrorTitle: TextView
    private lateinit var tvErrorSub: TextView
    private lateinit var layoutWarning: View
    private lateinit var tvWarning: TextView
    private lateinit var viewStatusDot: View
    private lateinit var tvServerStatus: TextView
    private lateinit var tvServerToggle: TextView
    private lateinit var layoutServer: View
    private lateinit var securePrefs: SecurePreferences

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private var serverOk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        securePrefs = SecurePreferences(this)

        if (securePrefs.getToken() != null) {
            goToMain()
            return
        }

        bindViews()
        etBackendUrl.setText(securePrefs.getBackendUrl())
        etEmail.setText("admin@excellentia.com")

        btnLogin.setOnClickListener { doLogin() }
        btnRetry.setOnClickListener { pingServer(); clearError() }

        tvServerToggle.setOnClickListener {
            val visible = layoutServer.visibility == View.VISIBLE
            layoutServer.visibility = if (visible) View.GONE else View.VISIBLE
            tvServerToggle.text = if (visible) "⚙  Configurar servidor" else "▲  Ocultar configuración"
        }

        pingServer()
    }

    private fun bindViews() {
        etBackendUrl    = findViewById(R.id.etBackendUrl)
        etEmail         = findViewById(R.id.etEmail)
        etPassword      = findViewById(R.id.etPassword)
        tilEmail        = findViewById(R.id.tilEmail)
        btnLogin        = findViewById(R.id.btnLogin)
        btnRetry        = findViewById(R.id.btnRetry)
        layoutLoading   = findViewById(R.id.layoutLoading)
        layoutError     = findViewById(R.id.layoutError)
        tvErrorTitle    = findViewById(R.id.tvErrorTitle)
        tvErrorSub      = findViewById(R.id.tvErrorSub)
        layoutWarning   = findViewById(R.id.layoutWarning)
        tvWarning       = findViewById(R.id.tvWarning)
        viewStatusDot   = findViewById(R.id.viewStatusDot)
        tvServerStatus  = findViewById(R.id.tvServerStatus)
        tvServerToggle  = findViewById(R.id.tvServerToggle)
        layoutServer    = findViewById(R.id.layoutServer)
    }

    // ── Server ping ───────────────────────────────────────────────────────────

    private fun pingServer() {
        setStatus("COMPROBANDO", false, isChecking = true)
        val url = etBackendUrl.text.toString().trim().ifEmpty { securePrefs.getBackendUrl() }
        val baseUrl = if (url.endsWith("/")) url else "$url/"

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url("${baseUrl}api/auth/me")
                        .addHeader("Authorization", "Bearer ping")
                        .build()
                    val res = client.newCall(req).execute()
                    // 401 = server is up (just not authenticated) — that's fine
                    res.code != 0
                } catch (_: Exception) { false }
            }
            serverOk = ok
            if (ok) {
                setStatus("ONLINE", online = true)
                hideWarning()
            } else {
                setStatus("OFFLINE", online = false)
                showWarning("No se puede conectar al servidor\nVerifica tu conexión o la URL del servidor.", isError = true)
                btnRetry.visibility = View.VISIBLE
            }
        }
    }

    private fun setStatus(text: String, online: Boolean, isChecking: Boolean = false) {
        tvServerStatus.text = text
        tvServerStatus.setTextColor(getColor(
            when {
                isChecking -> R.color.text_secondary
                online     -> R.color.success
                else       -> R.color.red
            }
        ))
        viewStatusDot.setBackgroundResource(
            when {
                isChecking -> R.drawable.circle_gray
                online     -> R.drawable.circle_green
                else       -> R.drawable.circle_gray.also {
                    viewStatusDot.setBackgroundColor(getColor(R.color.red))
                    return
                }
            }
        )
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    private fun doLogin() {
        val url      = etBackendUrl.text.toString().trim().ifEmpty { securePrefs.getBackendUrl() }
        val email    = etEmail.text.toString().trim()
        val password = etPassword.text.toString()

        if (email.isEmpty()) { showFieldError("Ingresa tu email", "Email requerido"); return }
        if (password.isEmpty()) { showFieldError("Ingresa tu contraseña", "Contraseña requerida"); return }

        clearError()
        setLoading(true)

        val baseUrl = if (url.endsWith("/")) url else "$url/"

        lifecycleScope.launch {
            try {
                val json = gson.toJson(mapOf("email" to email, "password" to password))
                val body = json.toRequestBody("application/json".toMediaType())
                val req  = Request.Builder()
                    .url("${baseUrl}api/auth/login")
                    .post(body)
                    .build()

                val response = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val result = gson.fromJson(responseBody, LoginResponse::class.java)
                    securePrefs.saveBackendUrl(baseUrl.dropLast(1))
                    securePrefs.saveToken(result.token)
                    securePrefs.saveRefreshToken(result.refreshToken)
                    securePrefs.saveOfflineMode(false)
                    RetrofitClient.initialize(baseUrl, securePrefs)
                    goToMain()
                } else {
                    val msg = try {
                        gson.fromJson(responseBody, ErrorResponse::class.java).error
                    } catch (_: Exception) { null }
                    showFieldError(
                        msg ?: "Error del servidor (${response.code})",
                        "Verifica tu email y contraseña"
                    )
                    markEmailError()
                }
            } catch (_: IOException) {
                showWarning(
                    "No se puede conectar al servidor\nVerifica tu conexión o la URL del servidor.",
                    isError = true
                )
                btnRetry.visibility = View.VISIBLE
                setStatus("OFFLINE", online = false)
            } catch (e: Exception) {
                showFieldError(e.localizedMessage ?: "Error desconocido", "Intenta de nuevo")
            } finally {
                setLoading(false)
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setLoading(loading: Boolean) {
        btnLogin.isEnabled = !loading
        btnLogin.text      = if (loading) "INGRESANDO..." else "LOGIN"
        btnLogin.alpha     = if (loading) 0.6f else 1f
        layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showFieldError(title: String, sub: String) {
        tvErrorTitle.text = title
        tvErrorSub.text   = sub
        layoutError.visibility = View.VISIBLE
    }

    private fun markEmailError() {
        tilEmail.isErrorEnabled = true
        tilEmail.error = " "
        tilEmail.editText?.setOnFocusChangeListener { _, _ ->
            tilEmail.isErrorEnabled = false
            tilEmail.error = null
            tilEmail.editText?.onFocusChangeListener = null
        }
    }

    private fun showWarning(msg: String, isError: Boolean) {
        tvWarning.text = msg
        layoutWarning.visibility = View.VISIBLE
    }

    private fun hideWarning() {
        layoutWarning.visibility = View.GONE
        btnRetry.visibility = View.GONE
    }

    private fun clearError() {
        layoutError.visibility = View.GONE
        tilEmail.isErrorEnabled = false
        tilEmail.error = null
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private data class LoginResponse(val token: String, val refreshToken: String? = null)
    private data class ErrorResponse(val error: String)
}

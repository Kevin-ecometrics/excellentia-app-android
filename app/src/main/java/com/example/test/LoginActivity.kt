package com.example.test

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView

import androidx.lifecycle.lifecycleScope
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.os.Build
import android.provider.Settings as AndroidSettings
import java.io.IOException
import java.util.concurrent.TimeUnit

class LoginActivity : BaseActivity() {

    private lateinit var etBackendUrl: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var wrapServerUrl: View
    private lateinit var wrapEmail: View
    private lateinit var wrapPassword: View
    private lateinit var btnTogglePassword: ImageButton
    private var emailHasError = false
    private var isPasswordVisible = false
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
        setTheme(R.style.Theme_Test)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        securePrefs = SecurePreferences(this)

        if (securePrefs.getToken() != null) {
            goToMain()
            return
        }

        bindViews()
        etBackendUrl.setText(securePrefs.getBackendUrl())
        setupInputFocusHighlight(wrapServerUrl, etBackendUrl)
        setupInputFocusHighlight(wrapEmail, etEmail)
        setupInputFocusHighlight(wrapPassword, etPassword)
        btnTogglePassword.setOnClickListener { togglePasswordVisibility() }

        btnLogin.setOnClickListener { doLogin() }
        btnRetry.setOnClickListener { pingServer(); clearError() }

        tvServerToggle.setOnClickListener {
            val visible = layoutServer.visibility == View.VISIBLE
            layoutServer.visibility = if (visible) View.GONE else View.VISIBLE
            tvServerToggle.text = if (visible) getString(R.string.configure_server) else getString(R.string.hide_server_config)
        }

        pingServer()
    }

    private fun bindViews() {
        etBackendUrl    = findViewById(R.id.etBackendUrl)
        etEmail         = findViewById(R.id.etEmail)
        etPassword      = findViewById(R.id.etPassword)
        wrapServerUrl   = findViewById(R.id.wrapServerUrl)
        wrapEmail       = findViewById(R.id.wrapEmail)
        wrapPassword    = findViewById(R.id.wrapPassword)
        btnTogglePassword = findViewById(R.id.btnTogglePassword)
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
        setStatus(getString(R.string.status_checking), false, isChecking = true)
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
                setStatus(getString(R.string.status_online), online = true)
                hideWarning()
            } else {
                setStatus(getString(R.string.status_offline), online = false)
                showWarning(getString(R.string.error_cannot_connect), isError = true)
                btnRetry.visibility = View.VISIBLE
            }
        }
    }

    private fun setStatus(text: String, online: Boolean, isChecking: Boolean = false) {
        tvServerStatus.text = text
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

        if (email.isEmpty()) { showFieldError(getString(R.string.error_enter_email), getString(R.string.error_email_required)); return }
        if (password.isEmpty()) { showFieldError(getString(R.string.error_enter_password), getString(R.string.error_password_required)); return }

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
                    result.user?.let { u ->
                        securePrefs.saveUserInfo(
                            email = u.email ?: email,
                            name  = u.name,
                            role  = u.role,
                            id    = u.id
                        )
                    }
                    RetrofitClient.initialize(baseUrl, securePrefs)
                    // Cachear configuración de empresa
                    try {
                        val settingsResp = RetrofitClient.getApi().getCompanySettings()
                        if (settingsResp.isSuccessful) {
                            settingsResp.body()?.data?.let { d ->
                                securePrefs.saveCompanySettings(
                                    name       = d.companyName,
                                    subtitle   = d.subtitle,
                                    address    = d.address,
                                    phone      = d.phone,
                                    city       = d.city
                                )
                            }
                        }
                    } catch (_: Exception) { }
                    // Registrar dispositivo
                    try {
                        val androidId = AndroidSettings.Secure.getString(
                            contentResolver, AndroidSettings.Secure.ANDROID_ID
                        ) ?: "unknown"
                        RetrofitClient.getApi().registerDevice(
                            com.example.test.data.DeviceRegisterRequest(
                                name         = "${Build.MANUFACTURER} ${Build.MODEL}",
                                model        = Build.MODEL,
                                serialNumber = androidId
                            )
                        )
                    } catch (_: Exception) { }
                    goToMain()
                } else {
                    val msg = try {
                        gson.fromJson(responseBody, ErrorResponse::class.java).error
                    } catch (_: Exception) { null }
                    showFieldError(
                        msg ?: getString(R.string.msg_server_error, response.code.toString()),
                        getString(R.string.error_check_credentials)
                    )
                    markEmailError()
                }
            } catch (_: IOException) {
                showWarning(
                    getString(R.string.error_cannot_connect),
                    isError = true
                )
                btnRetry.visibility = View.VISIBLE
                setStatus(getString(R.string.status_offline), online = false)
            } catch (e: Exception) {
                showFieldError(e.localizedMessage ?: getString(R.string.error_unknown), getString(R.string.btn_retry))
            } finally {
                setLoading(false)
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setLoading(loading: Boolean) {
        btnLogin.isEnabled = !loading
        btnLogin.text      = if (loading) getString(R.string.btn_logging_in) else getString(R.string.btn_login)
        btnLogin.alpha     = if (loading) 0.6f else 1f
        layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showFieldError(title: String, sub: String) {
        tvErrorTitle.text = title
        tvErrorSub.text   = sub
        layoutError.visibility = View.VISIBLE
    }

    // Plain bordered boxes (no TextInputLayout) — swap the background drawable
    // by hand instead of relying on Material's built-in focus/error states.
    private fun setupInputFocusHighlight(wrapper: View, editText: EditText) {
        editText.setOnFocusChangeListener { _, hasFocus ->
            wrapper.setBackgroundResource(
                when {
                    wrapper === wrapEmail && emailHasError -> R.drawable.bg_input_field_error
                    hasFocus -> R.drawable.bg_input_field_focused
                    else -> R.drawable.bg_input_field
                }
            )
        }
    }

    private fun togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible
        val selection = etPassword.selectionStart
        etPassword.inputType = if (isPasswordVisible)
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        etPassword.setSelection(selection.coerceIn(0, etPassword.text?.length ?: 0))
        btnTogglePassword.setImageResource(if (isPasswordVisible) R.drawable.ic_eye_off else R.drawable.ic_eye)
    }

    private fun markEmailError() {
        emailHasError = true
        wrapEmail.setBackgroundResource(R.drawable.bg_input_field_error)
        etEmail.setOnFocusChangeListener { _, hasFocus ->
            emailHasError = false
            wrapEmail.setBackgroundResource(if (hasFocus) R.drawable.bg_input_field_focused else R.drawable.bg_input_field)
            setupInputFocusHighlight(wrapEmail, etEmail)
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
        if (emailHasError) {
            emailHasError = false
            wrapEmail.setBackgroundResource(R.drawable.bg_input_field)
            setupInputFocusHighlight(wrapEmail, etEmail)
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private data class LoginUserInfo(val id: Int? = null, val email: String? = null, val name: String? = null, val role: String? = null)
    private data class LoginResponse(val token: String, val refreshToken: String? = null, val user: LoginUserInfo? = null)
    private data class ErrorResponse(val error: String)
}

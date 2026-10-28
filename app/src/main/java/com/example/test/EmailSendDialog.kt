package com.example.test

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.test.data.SendEmailResponse
import com.example.test.data.network.RetrofitClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import retrofit2.Response

// Diálogo "Enviar por correo" (2026-10-27) — compartido por la nota de crédito
// (CustomerCreditsActivity) y la pre-orden (PreOrderDetailActivity). Precarga
// el correo guardado del cliente (corregido a mano o el de QBO); si el
// operador lo cambia, el backend lo guarda para la próxima vez. `send` hace el
// POST; `trigger` se deshabilita mientras tanto.
object EmailSendDialog {

    fun show(
        activity: AppCompatActivity,
        customerId: String,
        titleRes: Int,
        trigger: View,
        send: suspend (email: String) -> Response<SendEmailResponse>
    ) {
        val root = activity.findViewById<View>(android.R.id.content)
        fun message(msg: String) = Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show()
        val dp = activity.resources.displayMetrics.density

        trigger.isEnabled = false
        activity.lifecycleScope.launch {
            val current = try {
                RetrofitClient.getApi().getCustomerEmail(customerId).body()
            } catch (_: Exception) { null }
            trigger.isEnabled = true
            if (current != null && !current.mailConfigured) {
                message(activity.getString(R.string.error_email_not_configured))
                return@launch
            }
            val etEmail = android.widget.EditText(activity).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                hint = activity.getString(R.string.hint_customer_email)
                setText(current?.email ?: "")
                setSelection(text.length)
            }
            val container = android.widget.FrameLayout(activity).apply {
                setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
                addView(etEmail)
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(titleRes))
                .setMessage(activity.getString(
                    if (current?.email == null) R.string.msg_email_no_email else R.string.msg_email_to_customer
                ))
                .setView(container)
                .setPositiveButton(activity.getString(R.string.btn_send)) { _, _ ->
                    val email = etEmail.text.toString().trim()
                    if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                        message(activity.getString(R.string.error_invalid_email))
                        return@setPositiveButton
                    }
                    trigger.isEnabled = false
                    message(activity.getString(R.string.msg_sending_email))
                    activity.lifecycleScope.launch {
                        try {
                            val resp = send(email)
                            if (resp.isSuccessful) {
                                message(activity.getString(R.string.msg_email_sent, resp.body()?.to ?: email))
                            } else {
                                // Mensaje del backend (correo inválido, sin configurar, SMTP rechazó…).
                                val serverMsg = try { org.json.JSONObject(resp.errorBody()?.string() ?: "").optString("error") } catch (_: Exception) { "" }
                                message(serverMsg.ifBlank { activity.getString(R.string.msg_server_error, resp.code().toString()) })
                            }
                        } catch (e: Exception) {
                            message(e.localizedMessage ?: activity.getString(R.string.error_no_connection))
                        } finally {
                            trigger.isEnabled = true
                        }
                    }
                }
                .setNegativeButton(activity.getString(R.string.btn_cancel), null)
                .show()
        }
    }
}

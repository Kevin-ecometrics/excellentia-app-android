package com.example.test

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.BatchFeedbackRequest
import com.example.test.data.network.RetrofitClient
import android.widget.EditText
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

// Fase 120 — pantalla obligatoria de notas/feedback (backlog #4), pedido
// explícito del usuario: se muestra justo después de que se imprime el
// segundo ticket (sendBatchAndPrint, CurrentOrderActivity) y antes de la
// pantalla de "Venta completada" (OrderSuccessActivity) — nunca al revés.
// Esta Activity no arma su propia UI de éxito: recibe TODOS los extras que
// ya traía el Intent original a OrderSuccessActivity, los reenvía tal cual
// una vez que la nota se mandó (o falló — best-effort, mismo criterio que
// el resto de la app: nunca bloquea el flujo local por un fallo de red) y
// se cierra. batch_id es el único extra que además necesita leer para sí
// misma, para saber a qué batch atar la nota.
class BatchFeedbackActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_feedback)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        val batchId = intent.getStringExtra("batch_id") ?: ""
        val etFeedback = findViewById<EditText>(R.id.etFeedback)
        val btnSubmit = findViewById<MaterialButton>(R.id.btnSubmitFeedback)

        // Pantalla obligatoria — el botón de atrás (físico o gesto) no la
        // saltea. No hay otra forma de salir que completar el texto.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Snackbar.make(findViewById(android.R.id.content), getString(R.string.hint_batch_feedback), Snackbar.LENGTH_SHORT).show()
            }
        })

        etFeedback.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                btnSubmit.isEnabled = !s.isNullOrBlank()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnSubmit.setOnClickListener {
            val note = etFeedback.text?.toString()?.trim().orEmpty()
            if (note.isEmpty()) return@setOnClickListener
            btnSubmit.isEnabled = false
            btnSubmit.text = getString(R.string.btn_saving)

            lifecycleScope.launch {
                if (batchId.isNotBlank()) {
                    try {
                        val resp = RetrofitClient.getApi().submitBatchFeedback(batchId, BatchFeedbackRequest(note))
                        if (!resp.isSuccessful) {
                            Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_feedback_failed), Snackbar.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Snackbar.make(findViewById(android.R.id.content), getString(R.string.error_feedback_failed), Snackbar.LENGTH_SHORT).show()
                    }
                }
                continueToOrderSuccess()
            }
        }
    }

    private fun continueToOrderSuccess() {
        val next = Intent(this, OrderSuccessActivity::class.java)
        intent.extras?.let { next.putExtras(it) }
        next.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(next)
        finish()
    }
}

package com.example.test

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.test.data.CreditReasonDto
import com.example.test.data.repository.CreditReasonRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

// Motivo del crédito (2026-10-26) — obligatorio y por producto. Un solo
// diálogo con una tarjeta por línea de crédito (selector de motivo +
// comentario opcional). Lo usan los 3 lugares que registran un crédito:
// artículos dañados al finalizar una venta (CurrentOrderActivity) o al
// convertir una pre-orden (PreOrderDetailActivity), y "Agregar crédito" sin
// venta (IssueCreditActivity).
object CreditReasonPicker {

    data class Choice(val reasonId: Int, val note: String?)

    private const val NOTE_MAX = 255

    // lines: texto de cada línea (producto + cantidad). initial: elección previa
    // por línea (al volver atrás y reabrir), o null. onBack: botón "Atrás"
    // (null = "Cancelar", cierra sin elegir). onDone recibe una Choice por línea,
    // en el mismo orden.
    fun show(
        activity: AppCompatActivity,
        lines: List<String>,
        initial: List<Choice?> = emptyList(),
        onBack: (() -> Unit)? = null,
        onDone: (List<Choice>) -> Unit
    ) {
        activity.lifecycleScope.launch {
            val reasons = CreditReasonRepository(activity).load()
            if (reasons.isEmpty()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(R.string.title_credit_reason))
                    .setMessage(activity.getString(R.string.msg_no_credit_reasons))
                    .setCancelable(false)
                    .setPositiveButton(activity.getString(R.string.btn_continue)) { _, _ -> onBack?.invoke() }
                    .show()
                return@launch
            }
            showDialog(activity, reasons, lines, initial, onBack, onDone)
        }
    }

    private fun showDialog(
        activity: AppCompatActivity,
        reasons: List<CreditReasonDto>,
        lines: List<String>,
        initial: List<Choice?>,
        onBack: (() -> Unit)?,
        onDone: (List<Choice>) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        container.addView(TextView(activity).apply {
            text = activity.getString(R.string.msg_credit_reason_hint)
            textSize = 13f
            setTextColor(activity.getColor(R.color.text_secondary))
        })

        // Posición 0 del selector = "Elegí un motivo…" (sin elegir).
        val options = listOf(activity.getString(R.string.hint_credit_reason_select)) + reasons.map { it.label }
        val spinners = mutableListOf<Spinner>()
        val notes = mutableListOf<EditText>()
        val errors = mutableListOf<TextView>()

        for ((index, line) in lines.withIndex()) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(activity, R.drawable.bg_edittext)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            card.addView(TextView(activity).apply {
                text = line
                textSize = 14f
                setTextColor(activity.getColor(R.color.text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            val tvError = TextView(activity).apply {
                text = activity.getString(R.string.error_credit_reason_required)
                textSize = 12f
                setTextColor(activity.getColor(R.color.ex_danger))
                visibility = View.GONE
            }
            val spinner = Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, options)
                val preselected = initial.getOrNull(index)?.reasonId
                    ?.let { id -> reasons.indexOfFirst { it.id == id } }
                    ?.takeIf { it >= 0 }
                setSelection(if (preselected != null) preselected + 1 else 0)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (position > 0) tvError.visibility = View.GONE
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            val etNote = EditText(activity).apply {
                hint = activity.getString(R.string.hint_credit_reason_note)
                textSize = 13f
                filters = arrayOf(android.text.InputFilter.LengthFilter(NOTE_MAX))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setText(initial.getOrNull(index)?.note ?: "")
            }
            card.addView(spinner)
            card.addView(tvError)
            card.addView(etNote)
            container.addView(card)
            spinners.add(spinner)
            notes.add(etNote)
            errors.add(tvError)
        }

        val scroll = ScrollView(activity).apply { addView(container) }
        val wrapper = android.widget.FrameLayout(activity)
        wrapper.addView(scroll, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.45).toInt()
        ))

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.title_credit_reason))
            .setView(wrapper)
            .setCancelable(false)
            .setPositiveButton(activity.getString(R.string.btn_continue), null)
            .setNegativeButton(
                activity.getString(if (onBack != null) R.string.btn_back else R.string.btn_cancel)
            ) { _, _ -> onBack?.invoke() }
            .create()

        // Listener propio para que "Continuar" no cierre el diálogo si falta un motivo.
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                var missing = false
                spinners.forEachIndexed { i, sp ->
                    val ok = sp.selectedItemPosition > 0
                    errors[i].visibility = if (ok) View.GONE else View.VISIBLE
                    if (!ok) missing = true
                }
                if (missing) return@setOnClickListener
                val choices = spinners.mapIndexed { i, sp ->
                    Choice(
                        reasonId = reasons[sp.selectedItemPosition - 1].id,
                        note = notes[i].text.toString().trim().ifEmpty { null }
                    )
                }
                dialog.dismiss()
                onDone(choices)
            }
        }
        dialog.show()
    }
}

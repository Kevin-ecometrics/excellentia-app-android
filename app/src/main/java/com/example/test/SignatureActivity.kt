package com.example.test

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar

class SignatureActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signature)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        val customerName = intent.getStringExtra("customer_name")
        val orderTotal   = intent.getStringExtra("order_total")
        val tvCustomer   = findViewById<TextView>(R.id.tvSignatureCustomer)
        val cardOrderSummary = findViewById<View>(R.id.cardOrderSummary)
        val tvOrderCustomer  = findViewById<TextView>(R.id.tvSignatureOrderCustomer)
        val tvOrderTotal     = findViewById<TextView>(R.id.tvSignatureOrderTotal)
        val signatureView = findViewById<SignatureView>(R.id.signatureView)
        val btnClear     = findViewById<Button>(R.id.btnClear)
        val btnConfirm   = findViewById<Button>(R.id.btnConfirm)

        if (!customerName.isNullOrBlank()) {
            tvCustomer.text = customerName
        }

        if (!orderTotal.isNullOrBlank()) {
            tvOrderCustomer.text = if (!customerName.isNullOrBlank()) customerName else getString(R.string.default_customer_name)
            tvOrderTotal.text = orderTotal
            cardOrderSummary.visibility = View.VISIBLE
        } else {
            cardOrderSummary.visibility = View.GONE
        }

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        btnClear.setOnClickListener { signatureView.clear() }

        btnConfirm.setOnClickListener {
            if (signatureView.isEmpty) {
                Snackbar.make(btnConfirm, getString(R.string.error_draw_signature), Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val base64 = signatureView.getBase64()
            setResult(Activity.RESULT_OK, Intent().putExtra("signature", base64))
            finish()
        }
    }
}

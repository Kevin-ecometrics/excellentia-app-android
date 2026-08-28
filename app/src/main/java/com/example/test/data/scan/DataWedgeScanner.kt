package com.example.test.data.scan

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

// Receptor mínimo de escaneos DataWedge, reusable en cualquier Activity que
// necesite recibir barcodes sin duplicar la lógica. El perfil de DataWedge en
// sí (creación/config del perfil "TestScannerProfile") ya lo arma
// MainActivity.setupDataWedge() en cada login — este helper no lo repite,
// solo escucha el intent de salida ya configurado (mismo patrón mínimo que ya
// usaba CreatePreOrderActivity antes de esta clase).
object DataWedgeScanner {
    private const val DW_RESULT_ACTION = "com.symbol.datawedge.datawedge.ACTION_RESULT"
    private const val DW_EXTRA_DATA = "com.symbol.datawedge.data_string"
    private const val DW_EXTRA_DATA_ALT = "com.motorolasolutions.emdk.datawedge.data_string"
    private const val DW_CATEGORY = "android.intent.category.DEFAULT"

    fun createReceiver(onBarcode: (String) -> Unit): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DW_RESULT_ACTION) return
            var data = intent.getStringExtra(DW_EXTRA_DATA)
            if (data.isNullOrBlank()) data = intent.getStringExtra(DW_EXTRA_DATA_ALT)
            if (!data.isNullOrBlank()) onBarcode(data)
        }
    }

    fun register(activity: Activity, receiver: BroadcastReceiver) {
        val filter = IntentFilter(DW_RESULT_ACTION).apply { addCategory(DW_CATEGORY) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            activity.registerReceiver(receiver, filter)
        }
    }

    fun unregister(activity: Activity, receiver: BroadcastReceiver) {
        try { activity.unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}

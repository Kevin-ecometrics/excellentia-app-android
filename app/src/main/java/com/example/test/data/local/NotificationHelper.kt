package com.example.test.data.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.test.HistoryActivity
import com.example.test.R

object NotificationHelper {

    private const val CHANNEL_ID = "order_sync_channel"
    private const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sincronización de pedidos",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificaciones cuando un pedido se sincroniza con QuickBooks"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun showOrderSynced(context: Context, orderId: Int, productName: String, quantity: Double, total: Double) {
        createChannel(context)

        val intent = Intent(context, HistoryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("✅ Pedido sincronizado")
            .setContentText("$productName (${"%.1f".format(quantity)} lb) — $${"%.2f".format(total)} enviado a QuickBooks")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Pedido #$orderId\n$productName — ${"%.1f".format(quantity)} lb\nTotal: $${"%.2f".format(total)}\nEstado: Enviado a QuickBooks"))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID + orderId, notification)
    }
}

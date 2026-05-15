package com.example.test.data.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.example.test.data.BatchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object PrintService {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val PW  = 576

    // Solo font 4 (17×27px) y font 7 (28×44px) — siempre disponibles en ZQ630 Plus
    // Font 3 no disponible en este firmware.
    private const val F7 = 7;  private const val F7H = 52   // 44px + 8px gap
    private const val F4 = 4;  private const val F4H = 34   // 27px + 7px gap

    private const val BOTTOM  = 200  // espacio para cortar (~1")
    private const val DRAIN_MS = 2000L

    @SuppressLint("MissingPermission")
    suspend fun printTicket(
        context: Context,
        deviceAddress: String,
        items: List<BatchItem>,
        customerName: String?,
        batchId: String,
        invoiceId: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!hasBtConnectPermission(context))
            return@withContext Result.failure(Exception("Permiso Bluetooth no otorgado"))
        send(context, deviceAddress, buildCpcl(items, customerName, batchId, invoiceId))
    }

    @SuppressLint("MissingPermission")
    suspend fun printTest(context: Context, deviceAddress: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!hasBtConnectPermission(context))
                return@withContext Result.failure(Exception("Permiso Bluetooth no otorgado"))
            send(context, deviceAddress, buildTestCpcl())
        }

    @SuppressLint("MissingPermission")
    private fun send(context: Context, address: String, data: String): Result<Unit> {
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return Result.failure(Exception("Bluetooth no disponible"))
            val socket = adapter.getRemoteDevice(address)
                .createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                socket.connect()
                socket.outputStream.write(data.toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
                Thread.sleep(DRAIN_MS)
            } finally {
                runCatching { socket.close() }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ticket — solo font 4 y 7, todo CENTER o LEFT, sin RIGHT
    //
    //    ═══════════════════
    //       EXCELLENTIA        F7 center
    //      Ticket de Venta     F4 center
    //     14/05/2026 10:30     F4 center
    //     Pedido #XXXXXXXX     F4 center  (si aplica)
    //      Factura #XXXXX      F4 center  (si aplica)
    //    Cliente: Cool Cars    F4 center  (si aplica)
    //    ═══════════════════
    //    Queso Fresco          F4 left
    //    1.50lb x $33.33       F4 left
    //    Subtotal: $50.00      F4 left
    //    ───────────────────
    //    ═══════════════════
    //        TOTAL             F4 center
    //        $50.00            F7 center
    //    ═══════════════════
    //     Total: 1.50 lb       F4 center
    //    ───────────────────
    //  Excellentia Scanner     F4 center
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildCpcl(
        items: List<BatchItem>,
        customerName: String?,
        batchId: String,
        invoiceId: String?
    ): String {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date())
        val grandTotal = items.sumOf { it.total }
        val totalQty   = items.sumOf { it.quantity }

        val body = StringBuilder()
        var y = 20

        // ── Cabecera ──────────────────────────────────
        body.center()
        body.t(F7, 0, y, "EXCELLENTIA");                           y += F7H + 8
        body.t(F4, 0, y, "Ticket de Venta");                       y += F4H + 6
        body.t(F4, 0, y, date);                                    y += F4H + 4

        if (batchId.isNotBlank()) {
            body.t(F4, 0, y, "Pedido #${batchId.takeLast(8)}");    y += F4H + 4
        }
        if (!invoiceId.isNullOrBlank()) {
            body.t(F4, 0, y, "Factura #$invoiceId");               y += F4H + 4
        }
        if (!customerName.isNullOrBlank()) {
            y += 6
            body.t(F4, 0, y, "Cliente: $customerName");            y += F4H + 4
        }

        y += 20

        // ── Ítems ─────────────────────────────────────
        // 3 líneas por ítem:
        //   Línea 1: Nombre del producto
        //   Línea 2: Código de barras  ·  $precio/lb
        //   Línea 3: X.XX lb  =  $total
        for (item in items) {
            body.left()
            body.t(F4, 8, y, item.productName.take(26));           y += F4H + 4
            body.t(F4, 8, y,
                "${item.barcode}  \$${String.format(Locale.US, "%.2f", item.price)}/lb")
            y += F4H + 4
            body.t(F4, 8, y,
                String.format(Locale.US, "%.2f lb  =  \$%.2f", item.quantity, item.total))
            y += F4H
            y += 18
        }

        y += 10

        // ── Total ─────────────────────────────────────
        body.center()
        body.t(F4, 0, y, "TOTAL");                                 y += F4H + 10
        body.t(F7, 0, y, String.format(Locale.US, "\$%.2f", grandTotal)); y += F7H + 14

        // ── Pie ───────────────────────────────────────
        body.t(F4, 0, y,
            String.format(Locale.US, "%.2f lb en total", totalQty)); y += F4H + 10
        body.t(F4, 0, y, "Excellentia");                           y += F4H

        val height = y + BOTTOM
        return "! 0 200 200 $height 1\r\nPAGE-WIDTH $PW\r\n" +
               body.toString() +
               "PRINT\r\n"
    }

    private fun buildTestCpcl(): String {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date())
        val body = StringBuilder()
        var y = 20

        body.center()
        body.t(F7, 0, y, "EXCELLENTIA");            y += F7H + 10
        body.t(F4, 0, y, "Prueba de impresion");    y += F4H + 8
        body.t(F4, 0, y, date);                     y += F4H + 10
        body.t(F4, 0, y, "ZQ630 Plus  OK");         y += F4H

        val height = y + BOTTOM
        return "! 0 200 200 $height 1\r\nPAGE-WIDTH $PW\r\n" +
               body.toString() +
               "PRINT\r\n"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun StringBuilder.t(font: Int, x: Int, y: Int, data: String) =
        append("T $font 0 $x $y $data\r\n")

    private fun StringBuilder.left()   = append("LEFT\r\n")
    private fun StringBuilder.center() = append("CENTER\r\n")
}

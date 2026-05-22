package com.example.test.data.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.test.data.BatchItem
import com.example.test.data.local.SecurePreferences
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
        invoiceId: String?,
        customerAddress: String? = null,
        damageQty: Int = 0,
        paymentMethod: String? = null,
        signature: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!hasBtConnectPermission(context))
            return@withContext Result.failure(Exception("Permiso Bluetooth no otorgado"))
        val prefs = SecurePreferences(context)
        send(context, deviceAddress, buildCpcl(
            items           = items,
            customerName    = customerName,
            customerAddress = customerAddress,
            batchId         = batchId,
            invoiceId       = invoiceId,
            companyName     = prefs.getCompanyName(),
            subtitle        = prefs.getCompanySubtitle(),
            address         = prefs.getCompanyAddress(),
            phone           = prefs.getCompanyPhone(),
            city            = prefs.getCompanyCity(),
            damageQty       = damageQty,
            paymentMethod   = paymentMethod,
            signature       = signature
        ))
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
                val connectThread = Thread { socket.connect() }
                connectThread.start()
                connectThread.join(8000L)
                if (connectThread.isAlive) {
                    connectThread.interrupt()
                    runCatching { socket.close() }
                    return Result.failure(Exception("Tiempo de espera agotado — verifica que la impresora esté encendida"))
                }
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
        customerAddress: String? = null,
        batchId: String,
        invoiceId: String?,
        companyName: String = "EXCELLENTIA",
        subtitle: String = "Ticket de Venta",
        address: String? = null,
        phone: String? = null,
        city: String? = null,
        damageQty: Int = 0,
        paymentMethod: String? = null,
        signature: String? = null
    ): String {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date())
        val grandTotal = items.sumOf { it.total }
        val totalQty   = items.sumOf { it.quantity }

        val body = StringBuilder()
        var y = 20

        // ── Cabecera ──────────────────────────────────
        body.center()
        body.t(F7, 0, y, companyName.take(20));                    y += F7H + 8
        body.t(F4, 0, y, subtitle);                                y += F4H + 6
        if (!city.isNullOrBlank()) {
            body.t(F4, 0, y, city);                                y += F4H + 4
        }
        if (!address.isNullOrBlank()) {
            body.t(F4, 0, y, address.take(28));                    y += F4H + 4
        }
        if (!phone.isNullOrBlank()) {
            body.t(F4, 0, y, phone);                               y += F4H + 4
        }
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
            if (!paymentMethod.isNullOrBlank()) {
                body.t(F4, 0, y, "Payment: $paymentMethod");       y += F4H + 4
            }
            if (!customerAddress.isNullOrBlank()) {
                // Si la dirección supera 32 chars, separarla en dos líneas por la primera coma
                if (customerAddress.length <= 32) {
                    body.t(F4, 0, y, customerAddress);              y += F4H + 4
                } else {
                    val commaIdx = customerAddress.indexOf(", ")
                    if (commaIdx > 0) {
                        body.t(F4, 0, y, customerAddress.substring(0, commaIdx).take(32))
                        y += F4H + 4
                        body.t(F4, 0, y, customerAddress.substring(commaIdx + 2).take(32))
                        y += F4H + 4
                    } else {
                        body.t(F4, 0, y, customerAddress.take(32));y += F4H + 4
                    }
                }
            }
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
        body.t(F4, 0, y, companyName.take(20));                    y += F4H + 16

        // ── Negative Sale ──────────────────────────────
        if (damageQty > 0) {
            body.center()
            body.t(F4, 0, y, "------------------------------");     y += F4H + 6
            body.t(F4, 0, y, "Negative Sale");                      y += F4H + 4
            body.t(F4, 0, y, "$damageQty unit(s) damaged/expired"); y += F4H + 6
            body.t(F4, 0, y, "------------------------------");     y += F4H + 16
        }

        // ── Términos y condiciones ─────────────────────
        body.center()
        val terms = "I hereby acknowledge that all above referenced goods have been received and are in good condition. I also understand that this sale is expressly conditioned upon my assent to all terms on the reverse of this page and I accept all the terms of this sale."
        for (line in wrapText(terms, 30)) {
            body.t(F4, 0, y, line);                                  y += F4H + 2
        }

        // ── Firma ─────────────────────────────────────
        if (!signature.isNullOrBlank()) {
            y += 18
            body.center()
            body.t(F4, 0, y, "------------------------------");     y += F4H + 8
            body.t(F4, 0, y, "Customer Signature");                  y += F4H + 12
            val sigWidth = 480
            val sigX = (PW - sigWidth) / 2
            val (egCmd, newY) = buildSignatureEg(signature, sigWidth, sigX, y)
            if (egCmd.isNotEmpty()) {
                body.left()
                body.append(egCmd)
                y = newY
            }
            y += 8
        }

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

    // Convierte base64 PNG a comando CPCL EG (1-bit, MSB first).
    // Retorna (comando, nuevaY). Si falla, retorna ("", startY).
    private fun buildSignatureEg(base64: String, targetWidth: Int, x: Int, startY: Int): Pair<String, Int> {
        return try {
            val raw = Base64.decode(base64, Base64.DEFAULT)
            var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                ?: return Pair("", startY)

            val scale = targetWidth.toFloat() / bmp.width
            val newH = (bmp.height * scale).toInt().coerceAtLeast(1)
            bmp = android.graphics.Bitmap.createScaledBitmap(bmp, targetWidth, newH, true)

            val widthBytes = (targetWidth + 7) / 8
            val sb = StringBuilder()
            sb.append("EG $widthBytes $newH $x $startY ")

            for (row in 0 until newH) {
                var acc = 0
                var bits = 0
                for (col in 0 until targetWidth) {
                    val px = bmp.getPixel(col, row)
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                    acc = (acc shl 1) or (if (lum < 128) 1 else 0)
                    bits++
                    if (bits == 8) {
                        sb.append(String.format("%02X", acc))
                        acc = 0; bits = 0
                    }
                }
                if (bits > 0) {
                    acc = acc shl (8 - bits)
                    sb.append(String.format("%02X", acc))
                }
            }
            sb.append("\r\n")
            Pair(sb.toString(), startY + newH)
        } catch (_: Exception) {
            Pair("", startY)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun wrapText(text: String, maxChars: Int = 30): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (word in words) {
            when {
                current.isEmpty() -> current.append(word)
                current.length + 1 + word.length <= maxChars -> current.append(" $word")
                else -> { lines.add(current.toString()); current.clear(); current.append(word) }
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    private fun StringBuilder.t(font: Int, x: Int, y: Int, data: String) =
        append("T $font 0 $x $y $data\r\n")

    private fun StringBuilder.left()   = append("LEFT\r\n")
    private fun StringBuilder.center() = append("CENTER\r\n")
}

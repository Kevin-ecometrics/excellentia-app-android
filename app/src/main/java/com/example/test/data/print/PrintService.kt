package com.example.test.data.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.test.data.BatchItem
import com.example.test.data.byTicketCategory
import com.example.test.data.groupedForTicket
import com.example.test.data.isWeightTicketCategory
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
        invoiceNumber: Int? = null,
        customerAddress: String? = null,
        damageItems: List<com.example.test.data.DamageItem> = emptyList(),
        paymentMethod: String? = null,
        signature: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!hasBtConnectPermission(context))
            return@withContext Result.failure(Exception("Bluetooth permission not granted"))
        val prefs = SecurePreferences(context)
        send(context, deviceAddress, buildCpcl(
            items           = items,
            customerName    = customerName,
            customerAddress = customerAddress,
            batchId         = batchId,
            invoiceId       = invoiceId,
            invoiceNumber   = invoiceNumber,
            companyName     = prefs.getCompanyName(),
            subtitle        = prefs.getCompanySubtitle(),
            address         = prefs.getCompanyAddress(),
            phone           = prefs.getCompanyPhone(),
            city            = prefs.getCompanyCity(),
            damageItems     = damageItems,
            paymentMethod   = paymentMethod,
            signature       = signature,
            disclaimer      = prefs.getDisclaimer()
        ))
    }

    @SuppressLint("MissingPermission")
    suspend fun printTest(context: Context, deviceAddress: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!hasBtConnectPermission(context))
                return@withContext Result.failure(Exception("Bluetooth permission not granted"))
            send(context, deviceAddress, buildTestCpcl())
        }

    @SuppressLint("MissingPermission")
    private fun send(context: Context, address: String, data: String): Result<Unit> {
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return Result.failure(Exception("Bluetooth not available"))
            val socket = adapter.getRemoteDevice(address)
                .createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                val connectThread = Thread { socket.connect() }
                connectThread.start()
                connectThread.join(8000L)
                if (connectThread.isAlive) {
                    connectThread.interrupt()
                    runCatching { socket.close() }
                    return Result.failure(Exception("Connection timeout — verify the printer is on"))
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
        invoiceNumber: Int? = null,
        companyName: String = "EXCELLENTIA",
        subtitle: String = "Ticket de Venta",
        address: String? = null,
        phone: String? = null,
        city: String? = null,
        damageItems: List<com.example.test.data.DamageItem> = emptyList(),
        paymentMethod: String? = null,
        signature: String? = null,
        disclaimer: String? = null
    ): String {
        val date = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.US).format(Date())
        val grandTotal = items.sumOf { it.total }
        val totalQty   = items.sumOf { it.quantity }
        val SEP  = "================================"   // 32 chars — separador principal
        val DASH = "--------------------------------"   // 32 chars — separador secundario

        val body = StringBuilder()
        var y = 20

        // ── Cabecera empresa ────────────────────────────
        // Todo LEFT — nombre y subtítulo sin truncar
        body.left()
        body.t(F4, 0, y, companyName.take(33));                    y += F4H + 4
        body.t(F4, 0, y, subtitle.take(32));                       y += F4H + 4
        if (!address.isNullOrBlank())
            { body.t(F4, 0, y, address.take(32));                  y += F4H + 2 }
        if (!city.isNullOrBlank())
            { body.t(F4, 0, y, city.take(32));                     y += F4H + 2 }
        if (!phone.isNullOrBlank())
            { body.t(F4, 0, y, phone.take(32));                    y += F4H + 2 }

        // ── Info del pedido ─────────────────────────────
        y += 6
        body.t(F4, 0, y, SEP);                                     y += F4H + 6
        body.t(F4, 0, y, date);                                    y += F4H + 4
        val displayInvoice = invoiceNumber ?: invoiceId?.take(20)
        if (displayInvoice != null) {
            body.t(F4, 0, y, "Invoice #$displayInvoice");          y += F4H + 4
        }

        // ── Cliente ─────────────────────────────────────
        if (!customerName.isNullOrBlank()) {
            y += 4
            body.t(F4, 0, y, DASH);                                y += F4H + 6
            val clientLines = wrapText("Customer: $customerName", 28)
            for (line in clientLines) { body.t(F4, 0, y, line);   y += F4H + 3 }
            y += 1
            if (!paymentMethod.isNullOrBlank()) {
                body.t(F4, 0, y, "Payment: $paymentMethod");       y += F4H + 4
            }
            if (!customerAddress.isNullOrBlank()) {
                for (l in wrapText(customerAddress, 28)) {
                    body.t(F4, 4, y, l);                           y += F4H + 3
                }
                y += 1
            }
        }

        // ── Ítems (agrupados por producto, y por categoría LBS/CASE/UNIT/BUCKET) ──
        // Línea 1+: nombre del producto (con salto de línea si es largo)
        // Última línea, por peso: "X.XX lb x $X.XX/lb      $XX.XX"  (twoCol)
        // Última línea, por caja/unidad: "N - Case x $XX.XX      $XX.XX"  (sin decimales)
        y += 4
        body.t(F4, 0, y, SEP);                                     y += F4H + 8
        val groupedByCategory = items.groupedForTicket().byTicketCategory()
        val showCategoryHeaders = groupedByCategory.size > 1
        for ((category, group) in groupedByCategory) {
            if (showCategoryHeaders) {
                body.t(F4, 0, y, category);                        y += F4H + 4
            }
            for (g in group) {
                val avgPrice  = if (g.quantity != 0.0) g.total / g.quantity else 0.0
                val totalStr  = String.format(Locale.US, "\$%.2f", g.total)
                val unitLabel = unitLabel(g.unit)
                val detailStr = when {
                    // "N - X.XX lb x $X.XX/lb" — N = cantidad de unidades pesadas por
                    // separado y agrupadas en esta línea (ej. 2 chicharrones = 2.00 lb).
                    isWeightTicketCategory(category) ->
                        String.format(Locale.US, "%d - %.2f %s x \$%.2f/%s", g.count, g.quantity, unitLabel, avgPrice, unitLabel)
                    // "N - Case of Q x $XX.XX" — Q = unidades por caja (products.qty cuando unit=Case).
                    // Una caja puede traer 1 o varios artículos; sin este dato no se distingue.
                    category == "CASE" && (g.caseQty ?: 0) > 0 ->
                        String.format(Locale.US, "%d - %s of %d x \$%.2f", g.quantity.toInt(), unitLabel, g.caseQty, avgPrice)
                    else ->
                        String.format(Locale.US, "%d - %s x \$%.2f", g.quantity.toInt(), unitLabel, avgPrice)
                }
                for (line in wrapText(g.productName, 28)) {
                    body.t(F4, 0, y, line);                        y += F4H + 3
                }
                body.t(F4, 0, y, twoCol(detailStr, totalStr, 28)); y += F4H + 4
                y += 8
            }
        }

        // ── Resumen Negative Sale (si hay alguno) ───────
        val totalDamage = damageItems.sumOf { it.qty }
        if (totalDamage > 0) {
            body.t(F4, 0, y, DASH);                                y += F4H + 6
            body.t(F4, 0, y, "Negative Sale Summary:");            y += F4H + 4
            for (dmg in damageItems.filter { it.qty > 0 }) {
                for (line in wrapText("${dmg.productName}: ${dmg.qty} unit(s)", 28)) {
                    body.t(F4, 4, y, line);                        y += F4H + 3
                }
            }
            body.t(F4, 0, y, DASH);                                y += F4H + 10
        }

        // ── Total ──────────────────────────────────────
        body.t(F4, 0, y, SEP);                                     y += F4H + 10
        body.t(F4, 0, y, twoCol("TOTAL:", String.format(Locale.US, "\$%.2f", grandTotal), 28)); y += F4H + 8
        // Con una sola categoría se puede sumar cantidad + unidad ("22.80 lb total").
        // Mezclando lb + case + unit no hay una suma que tenga sentido — se muestra
        // cantidad de productos en su lugar.
        val qtyLine = if (groupedByCategory.size <= 1) {
            val category = groupedByCategory.firstOrNull()?.first ?: "LBS"
            val overallUnit = items.firstOrNull()?.let { unitLabel(it.unit) } ?: "lb"
            if (isWeightTicketCategory(category))
                String.format(Locale.US, "%.2f %s total", totalQty, overallUnit)
            else
                String.format(Locale.US, "%d %s total", totalQty.toInt(), overallUnit)
        } else {
            val itemCount = items.groupedForTicket().size
            "$itemCount items total"
        }
        body.t(F4, 0, y, qtyLine);                                 y += F4H + 6
        body.t(F4, 0, y, companyName.take(32));                    y += F4H + 16

        // ── Términos y condiciones ──────────────────────
        // wrapText(28) = 476px — margen seguro para evitar corte en el borde físico
        y += 8
        body.left()
        val terms = disclaimer ?: "Terms and Conditions:\n\n(1) Seller retains title to the goods until buyer performs the entire contract and goods have been paid for in full. Seller retains a security interest in the goods, including all additions and replacements, to secure performance of all buyer's obligations under this contract.\n\n(2) The buyer is responsible for any loss or damage to goods once they are in buyer's possession.\n\n(3) Any claim of immediately apparent defect against delivered goods must be made upon receipt. In the case of hidden defects, buyer shall have no more than 3 days to present seller with a claim of defect.\n\n(4) The goods sold in this invoice will only be used for resale.\n\n(5) In any action which may be brought to enforce payment under this contract, seller shall be entitled to recover from buyer all the attorney fees seller incurs, in addition to seller's actual, incidental, and consequential damages.\n\n(6) Buyer agrees to pay a fee of $30.00 for each check drawn on insufficient funds (NSF Check).\n\n(7) Buyer agrees that jurisdiction and venue for any dispute under this contract are proper in San Diego, CA."
        for (line in wrapText(terms, 28)) {
            body.t(F4, 0, y, line);                                y += F4H + 4
        }
        y += 4

        // ── Firma ──────────────────────────────────────
        if (!signature.isNullOrBlank()) {
            y += 16
            body.t(F4, 0, y, DASH);                               y += F4H + 8
            body.t(F4, 0, y, "Customer Signature");               y += F4H + 12
            val sigWidth = 480
            val sigX = (PW - sigWidth) / 2
            val (egCmd, newY) = buildSignatureEg(signature, sigWidth, sigX, y)
            if (egCmd.isNotEmpty()) {
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
        val date = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.US).format(Date())
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

    // Devuelve una cadena de `width` chars con `left` a la izquierda y `right` a la derecha
    private fun twoCol(left: String, right: String, width: Int = 32): String {
        val maxLeft = (width - right.length - 1).coerceAtLeast(0)
        val l = left.take(maxLeft)
        val padding = (width - l.length - right.length).coerceAtLeast(1)
        return l + " ".repeat(padding) + right
    }

    // Respeta saltos de línea reales del texto original (ej. el disclaimer guardado
    // en la webapp, con un Enter entre cada punto numerado) partiendo primero por
    // "\n" — si no, una "palabra" podía terminar arrastrando un salto de línea crudo
    // metido en medio del texto (ej. "...contrato.\n(2) The buyer..."), lo que rompía
    // el comando CPCL de esa línea al imprimir (T requiere una sola línea de texto).
    private fun wrapText(text: String, maxChars: Int = 30): List<String> {
        val lines = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            if (paragraph.isBlank()) {
                lines.add("")
                continue
            }
            val current = StringBuilder()
            for (word in paragraph.split(" ")) {
                if (word.isEmpty()) continue
                when {
                    current.isEmpty() -> current.append(word)
                    current.length + 1 + word.length <= maxChars -> current.append(" $word")
                    else -> { lines.add(current.toString()); current.clear(); current.append(word) }
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString())
        }
        return lines
    }

    private fun StringBuilder.t(font: Int, x: Int, y: Int, data: String) =
        append("T $font 0 $x $y $data\r\n")

    private fun StringBuilder.left()   = append("LEFT\r\n")
    private fun StringBuilder.center() = append("CENTER\r\n")

    private fun unitLabel(unit: String?): String =
        if (unit.isNullOrBlank() || unit == "Lbs") "lb" else unit
}

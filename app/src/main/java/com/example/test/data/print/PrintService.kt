package com.example.test.data.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.test.R
import com.example.test.data.BatchItem
import com.example.test.data.byTicketCategory
import com.example.test.data.creditsTotalOf
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
    // Papel 102mm ≈ 816 dots. PW=800 deja ~1mm de holgura física por lado. El
    // padding visible lo pone X_LEFT: el origen x=0 de CPCL queda pegado al borde
    // físico izquierdo (verificado imprimiendo: cero margen a la izquierda y aire
    // solo a la derecha), así que todo el contenido se desplaza X_LEFT dots y los
    // anchos de texto se derivan del ancho INTERIOR (PW - 2*X_LEFT) para que el
    // ticket quede centrado con padding simétrico.
    private const val PW  = 800
    private const val X_LEFT = 48   // 6mm de padding a cada lado (el borde físico izquierdo suma ~3mm propios)

    // Fuente única del cuerpo del ticket: Font 7 del ZQ630 Plus. Medición física
    // con el probe de fuentes (ago 2026): en este firmware la Font 7 es una
    // condensada MÁS CHICA que la Font 4 (~40 dígitos entran en PAGE-WIDTH vs 33,
    // glifos más bajos) — las tablas de Zebra que listan la Font 7 como 28×44px NO
    // aplican a este firmware, y SETMAG no la afecta (con y sin SETMAG 1 1 se ve
    // igual). BODY_CHAR_PX = 15 es conservador a propósito: el dígito midió ≈14.4px
    // (cross-check del probe de ancho: 56 "=" entraban en 816 dots ≈ 14.5px) pero
    // si la fuente es proporcional hay letras más anchas que un dígito, y una
    // línea que desborda PAGE-WIDTH la envuelve la impresora pisando la siguiente.
    // Si un ticket real deja margen derecho de sobra, bajar a 14 gana ~3 chars/línea.
    private const val BODY_FONT = 7;  private const val BODY_H = 28   // alto estimado (Font 4 era 34) — afinar con ticket físico
    private const val BODY_CHAR_PX = 15

    private const val BOTTOM  = 200  // espacio para cortar (~1")
    private const val DRAIN_MS = 2000L

    // ── Ancho de línea — fuente única, todo el ticket lo usa ────────────────
    // BODY_CHAR_PX = ancho real estimado de un carácter en la fuente del cuerpo
    // (Font 7, medido con el probe: ~40 dígitos por línea — ver comentario arriba).
    // MAX_LINE_CHARS = cuántos caracteres entran físicamente en
    // PAGE-WIDTH sin desbordar — límite duro, no ajustable. LINE_WIDTH es el
    // ancho que de verdad usan wrapText/twoCol/threeCol en todo el ticket: se
    // deja un colchón de seguridad bajo MAX_LINE_CHARS a propósito (texto justo
    // al borde se corta feo en la impresora física). Para "que entre más info"
    // se sube este único número — todos los campos se ajustan juntos, ninguno
    // puede quedar desincronizado como pasaba antes con anchos sueltos por
    // campo. wrapText/twoCol/threeCol además clampan internamente contra
    // MAX_LINE_CHARS por las dudas, así que aunque alguien pase a mano un ancho
    // más grande en una llamada puntual, nunca se desborda la página — siempre
    // hace salto de línea real en vez de superponerse con lo que sigue.
    private val MAX_LINE_CHARS = (PW - 2 * X_LEFT) / BODY_CHAR_PX
    private val LINE_WIDTH = (MAX_LINE_CHARS - 4).coerceAtLeast(1)
    // Nombre de producto — deja lugar para el prefijo "N - " (cantidad
    // seleccionada, hasta 2 dígitos + " - ") antes del texto en la primera línea
    // (Fase 97).
    private val ITEM_NAME_WIDTH = (LINE_WIDTH - 4).coerceAtLeast(1)

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
        checkNumber: String? = null,
        signature: String? = null,
        // Total de créditos ya calculado por el backend (BatchResponse.creditsTotal),
        // cuando está disponible — más preciso que sumar qty*unitPrice localmente
        // (para Lbs, el backend usa weight_per_unit, que el cliente no conoce). Si
        // es null (impresión offline, sin respuesta del servidor todavía), se cae a
        // la suma local vía creditsTotalOf().
        creditsTotal: Double? = null,
        creditApplied: Double? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!hasBtConnectPermission(context))
            return@withContext Result.failure(Exception("Bluetooth permission not granted"))
        val prefs = SecurePreferences(context)
        send(context, deviceAddress, buildCpcl(
            context         = context,
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
            checkNumber     = checkNumber,
            signature       = signature,
            creditsTotal    = creditsTotal,
            creditApplied   = creditApplied
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
    // Ticket — fuente única BODY_FONT (Font 7), todo CENTER o LEFT, sin RIGHT
    //
    //    ═══════════════════
    //       EXCELLENTIA        BODY center
    //      Ticket de Venta     BODY center
    //     14/05/2026 10:30     BODY center
    //     Pedido #XXXXXXXX     BODY center  (si aplica)
    //      Factura #XXXXX      BODY center  (si aplica)
    //    Cliente: Cool Cars    BODY center  (si aplica)
    //    ═══════════════════
    //    Queso Fresco          BODY left
    //    1.50lb x $33.33       BODY left
    //    Subtotal: $50.00      BODY left
    //    ───────────────────
    //    ═══════════════════
    //        TOTAL             BODY center
    //        $50.00            BODY center
    //    ═══════════════════
    //     Total: 1.50 lb       BODY center
    //    ───────────────────
    //  Excellentia Scanner     BODY center
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildCpcl(
        context: Context,
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
        checkNumber: String? = null,
        signature: String? = null,
        creditsTotal: Double? = null,
        creditApplied: Double? = null
    ): String {
        val date = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.US).format(Date())
        val grandTotal = items.sumOf { it.total }
        val totalQty   = items.sumOf { it.quantity }
        // 56 chars: el "=" real mide ~11.5-12px (el gap derecho observado en el
        // ticket con separadores de 52 lo confirma) → 56×12=672px entran sobrado
        // en el ancho interior (PW - 2*X_LEFT = 704) y cruzan casi todo el papel.
        // Desacoplados de LINE_WIDTH a propósito — las líneas de TEXTO usan un
        // ancho más conservador porque la Font 7 es proporcional y hay letras
        // (dígitos ~14.2px) más anchas que el "=".
        val SEP  = "========================================================"   // 56 chars
        val DASH = "--------------------------------------------------------"   // 56 chars

        val body = StringBuilder()
        var y = 20

        // ── Cabecera empresa ────────────────────────────
        // Todo LEFT — nombre y subtítulo con wrap real (nunca se trunca en
        // silencio: si es largo, hace salto de línea y sigue en la próxima).
        body.left()
        y = body.tWrapped(X_LEFT, y, companyName, lineGap = 4)
        y = body.tWrapped(X_LEFT, y, subtitle, lineGap = 4)
        if (!address.isNullOrBlank()) {
            for (part in splitAddress(address)) { y = body.tWrapped(X_LEFT, y, part, lineGap = 2) }
        }
        if (!city.isNullOrBlank()) {
            for (part in splitAddress(city)) { y = body.tWrapped(X_LEFT, y, part, lineGap = 2) }
        }
        if (!phone.isNullOrBlank())   y = body.tWrapped(X_LEFT, y, phone, lineGap = 2)

        // ── Info del pedido ─────────────────────────────
        y += 6
        body.t(BODY_FONT, X_LEFT, y, SEP);                                     y += BODY_H + 6
        body.t(BODY_FONT, X_LEFT, y, date);                                    y += BODY_H + 4
        val displayInvoice = invoiceNumber ?: invoiceId?.take(20)
        if (displayInvoice != null) {
            body.t(BODY_FONT, X_LEFT, y, "Invoice #$displayInvoice");          y += BODY_H + 4
        }

        // ── Cliente ─────────────────────────────────────
        if (!customerName.isNullOrBlank()) {
            y += 4
            body.t(BODY_FONT, X_LEFT, y, DASH);                                y += BODY_H + 6
            val clientLines = wrapText("Customer: $customerName")
            for (line in clientLines) { body.t(BODY_FONT, X_LEFT, y, line);   y += BODY_H + 3 }
            y += 1
            if (!customerAddress.isNullOrBlank()) {
                for (part in splitAddress(customerAddress)) {
                    y = body.tWrapped(X_LEFT + 4, y, part, lineGap = 3)
                }
                y += 1
            }
            if (!paymentMethod.isNullOrBlank()) {
                body.t(BODY_FONT, X_LEFT, y, "Payment: $paymentMethod");          y += BODY_H + 4
                if ("Check".equals(paymentMethod, ignoreCase = true) && !checkNumber.isNullOrBlank()) {
                    y = body.tWrapped(X_LEFT + 4, y, "Check #: $checkNumber", lineGap = 3)
                }
            }
        }

        // ── Ítems (agrupados por producto, y por categoría LBS/CASE-UNIT/BUCKET) ──
        // Línea 1: "N - Nombre del producto" — N = cantidad de veces que se
        // seleccionó/escaneó este producto (cajas/buckets elegidos, o pesadas
        // individuales agrupadas para Lbs) — pedido explícito del usuario (Fase 97,
        // no es la misma cantidad que Qty/W, que ya viene multiplicada/expandida).
        // Líneas siguientes (si el nombre no entra en una sola): solo texto, sin
        // repetir el prefijo. Última línea: "Qty/Weight   Rate   Total" en 3
        // columnas (threeCol).
        y += 4
        body.t(BODY_FONT, X_LEFT, y, SEP);                                     y += BODY_H + 8
        body.t(BODY_FONT, X_LEFT, y, "Desc");                                   y += BODY_H + 3
        body.t(BODY_FONT, X_LEFT, y, threeCol("Qty/W", "Rate", "Total"));      y += BODY_H + 6
        val groupedByCategory = items.groupedForTicket().byTicketCategory()
        for ((category, group) in groupedByCategory) {
            // Header de categoría (Fase 98, siempre visible; Fase 100, enmarcado
            // con DASH arriba/abajo) — antes el nombre de categoría (ej. "LBS")
            // quedaba pegado al resto del texto y se perdía visualmente; ahora
            // queda enmarcado como su propia sección, imposible de confundir con
            // el nombre de un producto.
            body.t(BODY_FONT, X_LEFT, y, DASH);                                y += BODY_H + 3
            body.t(BODY_FONT, X_LEFT, y, category);                            y += BODY_H + 3
            body.t(BODY_FONT, X_LEFT, y, DASH);                                y += BODY_H + 6
            for (g in group) {
                // Qty/W = cantidad total real seleccionada (Fase 96, pedido del
                // usuario): Case/Unit multiplica por unidades por caja (caseQty) —
                // 3 cajas de 12 = 36; Lbs/Bucket ya traen el total real en
                // `quantity` (peso sumado / conteo de buckets), sin multiplicar. El
                // rate se recalcula sobre esta cantidad (no sobre el número de
                // cajas/pesadas) para que rate × qty siga dando el total de la línea.
                val displayQty = if (category == "CASE/UNIT") {
                    g.quantity * (g.caseQty?.takeIf { it > 0 } ?: 1)
                } else {
                    g.quantity
                }
                val avgPrice  = if (displayQty != 0.0) g.total / displayQty else 0.0
                val totalStr  = String.format(Locale.US, "\$%.2f", g.total)
                val rateStr   = String.format(Locale.US, "\$%.2f", avgPrice)
                // Fase 100/101 — indicador corto de unidad en Qty/W, un número puro
                // ("105", "36") se prestaba a confusión sin contexto.
                val qtyStr = if (isWeightTicketCategory(category))
                    String.format(Locale.US, "%.2f lb", displayQty)
                else
                    String.format(Locale.US, "%d %s", displayQty.toInt(), shortQtyUnit(category))
                // Cantidad seleccionada (para el prefijo del nombre, no confundir con
                // displayQty de arriba): Lbs = cuántas pesadas individuales se
                // agruparon en esta línea; Case/Unit y Bucket = cuántas unidades se
                // eligieron (antes de multiplicar por unidades por caja).
                val pickCount = if (isWeightTicketCategory(category)) g.count else g.quantity.toInt()
                val nameLines = wrapText(g.productName, ITEM_NAME_WIDTH)
                for ((idx, line) in nameLines.withIndex()) {
                    if (idx == 0) {
                        body.t(BODY_FONT, X_LEFT, y, "$pickCount - $line");    y += BODY_H + 3
                    } else {
                        body.t(BODY_FONT, X_LEFT, y, line);                    y += BODY_H + 3
                    }
                }
                body.t(BODY_FONT, X_LEFT, y, threeCol(qtyStr, rateStr, totalStr )); y += BODY_H + 4
                y += 8
            }
        }

        // ── Resumen Negative Sale (si hay alguno) ───────
        val totalDamage = damageItems.sumOf { it.qty }
        val credits = creditsTotalOf(damageItems, creditsTotal)
        if (totalDamage > 0) {
            body.t(BODY_FONT, X_LEFT, y, DASH);                                y += BODY_H + 6
            body.t(BODY_FONT, X_LEFT, y, "Negative Sale Summary:");            y += BODY_H + 4
            for (dmg in damageItems.filter { it.qty > 0 }) {
                val lineAmount = String.format(Locale.US, "\$%.2f", dmg.qty * dmg.unitPrice)
                val dmgQtyStr = com.example.test.data.formatDamageQty(dmg.qty, dmg.unit)
                for (line in wrapText("${dmg.productName}: $dmgQtyStr · -$lineAmount")) {
                    body.t(BODY_FONT, X_LEFT + 4, y, line);                        y += BODY_H + 3
                }
            }
            body.t(BODY_FONT, X_LEFT, y, DASH);                                y += BODY_H + 10
        }

        // ── Total ──────────────────────────────────────
        body.t(BODY_FONT, X_LEFT, y, SEP);                                     y += BODY_H + 10
        if (credits > 0) {
            body.t(BODY_FONT, X_LEFT, y, twoCol("Subtotal:", String.format(Locale.US, "\$%.2f", grandTotal))); y += BODY_H + 4
            body.t(BODY_FONT, X_LEFT, y, twoCol("Credits:", String.format(Locale.US, "-\$%.2f", credits)));    y += BODY_H + 4
        }
        val creditAppliedVal = creditApplied ?: 0.0
        val finalTotal = if (creditAppliedVal > 0) {
            body.t(BODY_FONT, X_LEFT, y, twoCol("Credit Applied:", String.format(Locale.US, "-\$%.2f", creditAppliedVal))); y += BODY_H + 4
            grandTotal - credits - creditAppliedVal
        } else {
            grandTotal - credits
        }
        body.t(BODY_FONT, X_LEFT, y, twoCol("TOTAL:", String.format(Locale.US, "\$%.2f", finalTotal))); y += BODY_H + 8
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
        body.t(BODY_FONT, X_LEFT, y, qtyLine);                                 y += BODY_H + 6
        y = body.tWrapped(X_LEFT, y, companyName, lineGap = 4);          y += 12

        // ── Términos y condiciones (QR) ──────────────────
        // Reemplaza el texto legal completo por un QR que apunta a la página
        // web con el disclaimer — el texto ya no se imprime en el ticket.
        y += 8
        body.t(BODY_FONT, X_LEFT, y, "Terms & Conditions:");                   y += BODY_H + 4
        body.t(BODY_FONT, X_LEFT, y, "Scan to view");                          y += BODY_H + 8
        val qrWidth = 500
        val qrX = (PW - qrWidth) / 2
        val (qrCmd, qrNewY) = buildQrEg(context, qrWidth, qrX, y)
        if (qrCmd.isNotEmpty()) {
            body.append(qrCmd)
            y = qrNewY
        }
        for (urlLine in "https://excellentiafoods.com/terms-and-conditions/".chunked(LINE_WIDTH)) {
            body.t(BODY_FONT, X_LEFT, y, urlLine); y += BODY_H + 4
        }
        y += 8

        // ── Firma ──────────────────────────────────────
        if (!signature.isNullOrBlank()) {
            y += 16
            body.t(BODY_FONT, X_LEFT, y, DASH);                               y += BODY_H + 8
            body.t(BODY_FONT, X_LEFT, y, "Customer Signature");               y += BODY_H + 12
            val sigWidth = 700
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
        body.t(BODY_FONT, X_LEFT, y, "EXCELLENTIA");         y += BODY_H + 10
        body.t(BODY_FONT, X_LEFT, y, "Prueba de impresion"); y += BODY_H + 8
        body.t(BODY_FONT, X_LEFT, y, date);                  y += BODY_H + 10
        body.t(BODY_FONT, X_LEFT, y, "ZQ630 Plus  OK");      y += BODY_H

        val height = y + BOTTOM
        return "! 0 200 200 $height 1\r\nPAGE-WIDTH $PW\r\n" +
               body.toString() +
               "PRINT\r\n"
    }

    // Convierte un Bitmap a comando CPCL EG (1-bit, MSB first), escalado a
    // targetWidth. Retorna (comando, nuevaY). Compartido por la firma y el QR
    // del disclaimer.
    private fun bitmapToEg(bmp: android.graphics.Bitmap, targetWidth: Int, x: Int, startY: Int): Pair<String, Int> {
        val scale = targetWidth.toFloat() / bmp.width
        val newH = (bmp.height * scale).toInt().coerceAtLeast(1)
        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, targetWidth, newH, true)

        val widthBytes = (targetWidth + 7) / 8
        val sb = StringBuilder()
        sb.append("EG $widthBytes $newH $x $startY ")

        for (row in 0 until newH) {
            var acc = 0
            var bits = 0
            for (col in 0 until targetWidth) {
                val px = scaled.getPixel(col, row)
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
        return Pair(sb.toString(), startY + newH)
    }

    // Convierte base64 PNG (firma del cliente) a comando CPCL EG.
    // Retorna (comando, nuevaY). Si falla, retorna ("", startY).
    private fun buildSignatureEg(base64: String, targetWidth: Int, x: Int, startY: Int): Pair<String, Int> {
        return try {
            val raw = Base64.decode(base64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                ?: return Pair("", startY)
            bitmapToEg(bmp, targetWidth, x, startY)
        } catch (_: Exception) {
            Pair("", startY)
        }
    }

    // QR del disclaimer (drawable-nodpi/disclaimer_qr.png) a comando CPCL EG.
    // Retorna (comando, nuevaY). Si falla, retorna ("", startY).
    private fun buildQrEg(context: Context, targetWidth: Int, x: Int, startY: Int): Pair<String, Int> {
        return try {
            val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.disclaimer_qr)
                ?: return Pair("", startY)
            bitmapToEg(bmp, targetWidth, x, startY)
        } catch (_: Exception) {
            Pair("", startY)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Devuelve una cadena de `width` chars con `left` a la izquierda y `right` a la derecha.
    // `width` se clampa contra MAX_LINE_CHARS — no importa qué se le pase, nunca
    // desborda la página físicamente (evita el bug de que una fila se corra a la
    // derecha del borde y la impresora la envuelva sola, pisando la línea siguiente).
    private fun twoCol(left: String, right: String, width: Int = LINE_WIDTH): String {
        val w = width.coerceAtMost(MAX_LINE_CHARS)
        val maxLeft = (w - right.length - 1).coerceAtLeast(0)
        val l = left.take(maxLeft)
        val padding = (w - l.length - right.length).coerceAtLeast(1)
        return l + " ".repeat(padding) + right
    }

    // Fila de 3 columnas para el detalle de ítem (qty/weight, rate, total) —
    // mid/right van pegadas a la derecha en un ancho fijo (alcanza para
    // "$9999.99"), left toma el resto del ancho y se trunca si no entra (caso
    // raro: "N - Case/Unit of Q" con Q de 2+ dígitos y N de 2+ dígitos a la vez).
    // `width` clampado contra MAX_LINE_CHARS, misma razón que twoCol().
    private fun threeCol(left: String, mid: String, right: String, width: Int = LINE_WIDTH, midWidth: Int = 8, rightWidth: Int = 9): String {
        val w = width.coerceAtMost(MAX_LINE_CHARS)
        val r = right.padStart(rightWidth).takeLast(rightWidth)
        val m = mid.padStart(midWidth).takeLast(midWidth)
        val leftWidth = (w - midWidth - rightWidth).coerceAtLeast(1)
        val l = left.take(leftWidth).padEnd(leftWidth)
        return l + m + r
    }

    // Respeta saltos de línea reales del texto original (ej. el disclaimer guardado
    // en la webapp, con un Enter entre cada punto numerado) partiendo primero por
    // "\n" — si no, una "palabra" podía terminar arrastrando un salto de línea crudo
    // metido en medio del texto (ej. "...contrato.\n(2) The buyer..."), lo que rompía
    // el comando CPCL de esa línea al imprimir (T requiere una sola línea de texto).
    // `maxChars` se clampa contra MAX_LINE_CHARS — una palabra individual más
    // larga que eso también se corta a la fuerza (`.take()`) en vez de mandarla
    // tal cual: dejarla pasar entera desbordaría la página igual que un `width`
    // de columna mal puesto, y la impresora la envolvería por su cuenta sin que
    // el `y` del comando stream se entere (la línea siguiente se pisa encima).
    private fun wrapText(text: String, maxChars: Int = LINE_WIDTH): List<String> {
        val max = maxChars.coerceAtMost(MAX_LINE_CHARS).coerceAtLeast(1)
        val lines = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            if (paragraph.isBlank()) {
                lines.add("")
                continue
            }
            val current = StringBuilder()
            for (rawWord in paragraph.split(" ")) {
                if (rawWord.isEmpty()) continue
                val word = if (rawWord.length > max) rawWord.take(max) else rawWord
                when {
                    current.isEmpty() -> current.append(word)
                    current.length + 1 + word.length <= max -> current.append(" $word")
                    else -> { lines.add(current.toString()); current.clear(); current.append(word) }
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString())
        }
        return lines
    }

    private fun StringBuilder.t(font: Int, x: Int, y: Int, data: String) =
        append("T $font 0 $x $y $data\r\n")

    // Imprime `text` en la fuente del cuerpo (BODY_FONT), envolviendo con
    // wrapText en vez de truncar —
    // usado en los campos de la cabecera/pie (nombre de empresa, subtítulo,
    // dirección, etc.) que antes usaban `.take()` y perdían texto en silencio
    // si eran largos. Devuelve el `y` después de la última línea.
    private fun StringBuilder.tWrapped(x: Int, startY: Int, text: String, maxChars: Int = LINE_WIDTH, lineGap: Int = 4): Int {
        var y = startY
        for (line in wrapText(text, maxChars)) {
            t(BODY_FONT, x, y, line)
            y += BODY_H + lineGap
        }
        return y
    }

    private fun StringBuilder.left()   = append("LEFT\r\n")
    private fun StringBuilder.center() = append("CENTER\r\n")

    private fun unitLabel(unit: String?): String = when {
        unit.isNullOrBlank() || unit == "Lbs" -> "lb"
        com.example.test.data.isCaseUnitType(unit) -> "Case/Unit"
        else -> unit
    }

    // Indicador corto de unidad para la columna Qty/W (Fase 101) — a diferencia
    // de unitLabel() (nombre completo, usado en el header de categoría y el pie
    // del ticket), acá va abreviado porque comparte línea con rate/total en un
    // ancho angosto. Basado en `ticketCategoryFor()` (ya normalizado a mayúsculas),
    // no en el `unit` crudo del producto.
    private fun shortQtyUnit(category: String): String = when (category) {
        "CASE/UNIT" -> "cs/unt"
        "BUCKET" -> "bkt"
        else -> category.take(3).lowercase(Locale.US)
    }

    // Parte una dirección en líneas más naturales que el wrap por ancho solo.
    // 1. Primera coma → separa calle de ciudad/estado (ej. "123 Main St, Springfield, IL")
    // 2. Palabras clave (Suite/Unit/Apt/Ste/Apart) → parte antes de la palabra
    // 3. # → parte antes del hash (ej. "123 Main St #4B")
    // Si no encuentra nada, devuelve el texto completo sin partir — wrapText
    // (vía tWrapped) se encarga del ancho después.
    private fun splitAddress(text: String): List<String> {
        if (text.isBlank()) return listOf(text)
        val a = text.trim()
        if (a.length <= 28) return listOf(a)

        val ci = a.indexOf(", ")
        if (ci > 0) return listOf(a.substring(0, ci), a.substring(ci + 2))

        val keywords = listOf("Suite", "Unit", "Apt", "Ste", "Apart")
        val regex = Regex("""\b(?:${keywords.joinToString("|")})\b""", RegexOption.IGNORE_CASE)
        val match = regex.find(a, startIndex = 4)
        if (match != null && match.range.first >= 4) {
            return listOf(a.substring(0, match.range.first).trimEnd(), a.substring(match.range.first))
        }

        val hi = a.indexOf(" #", startIndex = 4)
        if (hi > 0) return listOf(a.substring(0, hi), a.substring(hi + 1))

        return listOf(a)
    }
}

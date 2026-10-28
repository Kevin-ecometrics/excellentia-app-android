package com.example.test

import com.example.test.data.CreditLineDto
import com.example.test.data.CreditMovementDto
import com.example.test.data.print.PrintService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Nota de crédito impresa (ticket 2, 2026-10-27): el contenido y que ningún
// texto se pase del ancho del papel (una línea que desborda la impresora la
// envuelve pisando la siguiente). Sin impresora: se valida el CPCL generado.
class CreditNoteCpclTest {

    // (PW 800 - 2 × X_LEFT 48) / BODY_CHAR_PX 15 = 46 — MAX_LINE_CHARS de PrintService.
    private val maxChars = 46

    private fun credit(items: List<CreditLineDto>, issuedBy: String? = "Juan Operador", batchId: String? = "crmvr2vua24vt42r") =
        CreditMovementDto(
            id = 1, type = "EARNED", amount = items.sumOf { it.amount }, batchId = batchId, note = null,
            createdAt = "2026-10-27T19:28:14.000Z", saleDate = null, invoiceNumber = null, saleStatus = null,
            standalone = true, issuedBy = issuedBy, items = items
        )

    private val queso = CreditLineDto("B1", "Queso fresco", 2.5, "Lbs", 3.0, 7.5, "Dañado", "caja rota")
    private val crema = CreditLineDto(null, "Crema 24ct", 2.0, "Case/Unit", 1.5, 3.0, null, null)

    private fun build(c: CreditMovementDto, balance: Double = 10.5, customer: String = "Cliente Uno") =
        PrintService.buildCreditNoteCpcl(
            companyName = "EXCELLENTIA FOODS", address = "123 Main St, Suite 4", city = "San Diego, CA 92101",
            phone = "(619) 555-0100", customerName = customer, credit = c, balance = balance
        )

    // Texto de cada comando "T font 0 x y data".
    private fun texts(cpcl: String) = cpcl.split("\r\n").filter { it.startsWith("T ") }
        .map { it.split(" ", limit = 6)[5] }

    @Test fun includesAllFields() {
        val out = texts(build(credit(listOf(queso, crema)))).joinToString("\n")
        listOf(
            "EXCELLENTIA FOODS", "Credit Note", "123 Main St", "(619) 555-0100",
            "Credit #A24VT42R", "Customer: Cliente Uno", "Issued by: Juan Operador",
            "Queso fresco", "2.50 lb", "\$7.50", "Reason: Danado", "Note: caja rota",
            "Crema 24ct", "2 unit(s)", "\$3.00", "CREDIT TOTAL:", "\$10.50", "Current balance:"
        ).forEach { assertTrue("falta \"$it\" en:\n$out", out.contains(it)) }
        // Producto sin motivo (APK viejo): no imprime "Reason:" vacío.
        assertEquals(1, out.split("\n").count { it.contains("Reason:") })
    }

    @Test fun wellFormedCpcl() {
        val cpcl = build(credit(listOf(queso)))
        assertTrue(cpcl.startsWith("! 0 200 200 "))
        assertTrue(cpcl.endsWith("PRINT\r\n"))
        // Alto declarado mayor que el último y usado.
        val height = cpcl.substringAfter("! 0 200 200 ").substringBefore(" ").toInt()
        val lastY = cpcl.split("\r\n").filter { it.startsWith("T ") }.maxOf { it.split(" ")[4].toInt() }
        assertTrue(height > lastY)
    }

    @Test fun longTextNeverOverflows() {
        val long = CreditLineDto(
            "B9", "Queso Oaxaca de hebra extra largo bolsa familiar 5 lb importado de Jalisco", 12.75, "Lbs", 4.25, 54.19,
            "Producto vencido en la tienda del cliente", "El cliente encontró la caja abierta y con humedad en el fondo del refrigerador"
        )
        val cpcl = build(credit(listOf(long, crema)), customer = "Supermercado Las Palmas de San Ysidro Sucursal Norte 2")
        val tooLong = texts(cpcl)
            .filterNot { it.all { ch -> ch == '=' } || it.all { ch -> ch == '-' } } // separadores: 56 a propósito, ver buildCpcl
            .filter { it.length > maxChars }
        assertTrue("líneas que desbordan: $tooLong", tooLong.isEmpty())
        // El comentario largo no se pierde: se envuelve en varias líneas.
        val all = texts(cpcl).joinToString(" ")
        assertTrue(all.contains("refrigerador"))
        assertTrue(all.contains("Jalisco"))
    }

    // La impresora no entiende UTF-8 de 2 bytes (ver el "·" en PrintService):
    // "ñ"/acentos de motivos y comentarios en español imprimían basura.
    @Test fun onlyAsciiReachesThePrinter() {
        val accented = CreditLineDto("B1", "Jalapeño en rodajas", 1.0, "Lbs", 2.0, 2.0, "Dañado", "Caja rota — llegó así")
        val cpcl = build(credit(listOf(accented)), customer = "Abarrotes Peña")
        val nonAscii = texts(cpcl).filter { line -> line.any { it.code > 127 } }
        assertTrue("texto no ASCII: $nonAscii", nonAscii.isEmpty())
        val out = texts(cpcl).joinToString("\n")
        assertTrue(out.contains("Customer: Abarrotes Pena"))
        assertTrue(out.contains("Jalapeno en rodajas"))
        assertTrue(out.contains("Reason: Danado"))
        assertTrue(out.contains("Note: Caja rota - llego asi"))
    }

    @Test fun optionalFieldsMissing() {
        val out = texts(build(credit(listOf(crema), issuedBy = null, batchId = null))).joinToString("\n")
        assertFalse(out.contains("Issued by"))
        assertFalse(out.contains("Credit #"))
        assertTrue(out.contains("CREDIT TOTAL:"))
    }

    @Test fun printsCreditDateNotBlank() {
        val out = texts(build(credit(listOf(crema))))
        // Fecha MM/dd/yyyy HH:mm en la hora del equipo (UTC 19:28 → depende de la zona).
        assertTrue(out.any { Regex("""^\d{2}/\d{2}/\d{4} \d{2}:\d{2}$""").matches(it) })
        assertTrue(out.any { it.startsWith("10/27/2026") })
    }
}

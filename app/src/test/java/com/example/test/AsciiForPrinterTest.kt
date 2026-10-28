package com.example.test

import com.example.test.data.print.PrintService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// asciiForPrinter (2026-10-27) — lo usan el ticket de venta y la nota de
// crédito sobre el CPCL completo, así que además de quitar acentos no puede
// tocar comandos, saltos de línea ni los gráficos (firma/QR en hex).
class AsciiForPrinterTest {

    @Test fun stripsSpanishAccents() {
        assertEquals("Abarrotes Pena", PrintService.asciiForPrinter("Abarrotes Peña"))
        assertEquals("Jalapeno Ano Cafe Unico Pinata", PrintService.asciiForPrinter("Jalapeño Año Café Único Piñata"))
        assertEquals("ACENTUACION aeiou AEIOU uU nN", PrintService.asciiForPrinter("ACENTUACIÓN áéíóú ÁÉÍÓÚ üÜ ñÑ"))
    }

    @Test fun replacesTypographicPunctuation() {
        assertEquals("Caja rota - llego asi", PrintService.asciiForPrinter("Caja rota — llegó así"))
        assertEquals("Queso 'Don Juan' \"Premium\" 5-10", PrintService.asciiForPrinter("Queso ‘Don Juan’ “Premium” 5–10"))
        assertEquals("a - b", PrintService.asciiForPrinter("a · b"))
    }

    @Test fun unknownCharactersBecomeQuestionMark() {
        assertEquals("Promo ?", PrintService.asciiForPrinter("Promo €"))
    }

    @Test fun neverGrowsSoWrappingStillHolds() {
        val s = "Supermercado Peña — “Ñandú” · Almacén Ünico"
        assertTrue(PrintService.asciiForPrinter(s).length <= s.length)
    }

    @Test fun leavesCpclCommandsAndGraphicsUntouched() {
        val cpcl = "! 0 200 200 900 1\r\nPAGE-WIDTH 800\r\nLEFT\r\n" +
            "T 7 0 48 20 Customer: Peña\r\n" +
            "EG 57 120 175 400 00FF7A3C0BE1\r\n" +
            "T 7 0 48 60 \$12.50\r\n"
        val expected = cpcl.replace("Peña", "Pena")
        assertEquals(expected, PrintService.asciiForPrinter(cpcl))
    }
}

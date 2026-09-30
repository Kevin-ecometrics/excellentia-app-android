package com.example.test.data.scan

// Etiqueta de peso de las cajas de peso variable (ej. Tío Francisco): además
// del código del producto trae un Code128 pequeño y vertical cuyo contenido es
// SOLO el peso en libras como texto ("14.45", "15.08", "24.20"). Se decidió leer
// solo ese formato por ahora — el Code128 largo ("350/1A-26239/14.45") y el
// GS1 de Cotija ("...3202001215...") también traen el peso pero quedan fuera
// de esta versión.
//
// El punto decimal es obligatorio a propósito: un código de producto (UPC/EAN,
// 12-13 dígitos, sin punto) nunca puede confundirse con un peso.
object WeightLabel {
    private val WEIGHT = Regex("""^\d{1,4}\.\d{1,2}$""")

    /** Peso en lbs, o `null` si `raw` no es una etiqueta de peso válida (o es 0). */
    fun parse(raw: String?): Double? {
        val text = raw?.trim() ?: return null
        if (!WEIGHT.matches(text)) return null
        return text.toDoubleOrNull()?.takeIf { it > 0 }
    }
}

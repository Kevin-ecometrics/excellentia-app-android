package com.example.test.data

import android.content.Context
import com.example.test.R

// Fase 141 — texto del desglose por lote de una línea de ruta (una caja/carga).
// Una línea por lote: "Lote 1A-26239 · vence 2026-10-01". El peso por lote solo
// se repite cuando la línea se partió en 2+ lotes (si no, ya es el número grande
// de la fila). La parte cargada desde stock general se muestra aparte.
// null = no hay nada que mostrar (backend anterior, o línea sin lotes).
fun routeLotsSummary(context: Context, lots: List<RouteItemLotDto>, unlottedQty: Double, unit: String?): String? {
    val hasUnlotted = unlottedQty > 0.0001
    val showQty = lots.size + (if (hasUnlotted) 1 else 0) > 1
    fun qtyText(q: Double): String =
        if (isLbsUnit(unit)) context.getString(R.string.wh_loaded_lb_suffix, formatQty(q)) else formatQty(q)

    val lines = mutableListOf<String>()
    for (lot in lots) {
        val number = lot.lotNumber?.takeIf { it.isNotBlank() } ?: context.getString(R.string.wh_route_lot_no_number)
        var line = context.getString(R.string.wh_route_lot_line, number)
        if (showQty) line += " · " + qtyText(lot.quantity)
        lot.expirationDate?.take(10)?.let { line += context.getString(R.string.wh_item_expiration_suffix, it) }
        lines += line
    }
    if (hasUnlotted) lines += context.getString(R.string.wh_route_unlotted_line, qtyText(unlottedQty))
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

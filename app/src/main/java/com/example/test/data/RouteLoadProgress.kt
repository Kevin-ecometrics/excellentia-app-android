package com.example.test.data

// Fase 148 — carga de ruta por pasos, una pre-orden a la vez. Todo se deriva de
// lo ya persistido (route_items con su route_stop_id vs el resumen de la
// pre-orden de la parada), sin estado en el servidor.
//
// - Lbs: cada caja cargada es UNA línea de route_items (Fase 130), así que
//   "cargado" = cuántas líneas hay para ese producto en la parada (vs boxCount).
// - Case/Unit/Bucket: "cargado" = suma de quantity (vs requestedQty, en cajas).

private const val EPS = 1e-6

data class LoadLineProgress(
    val productName: String,
    val barcode: String?,
    val unit: String?,
    val isBoxCount: Boolean,
    val requested: Double,
    val loaded: Double,
    // Líneas de route_items que cuentan en este producto, una por carga (caja/lote);
    // se muestran tal cual, sin sumar, igual que la lista "Cargado".
    val loadedItems: List<RouteItemDto> = emptyList(),
) {
    val remaining: Double get() = (requested - loaded).coerceAtLeast(0.0)
    val complete: Boolean get() = loaded + EPS >= requested
}

data class LoadStepProgress(
    val stop: RouteStopDto,
    val lines: List<LoadLineProgress>,
) {
    val complete: Boolean get() = lines.all { it.complete }
}

// Un paso por parada PRE_ORDER con productos pedidos, en el orden de la ruta.
// Las demás paradas (BATCH/CUSTOMER/CONSIGNMENT) no tienen pasos.
fun computeLoadSteps(stops: List<RouteStopDto>, items: List<RouteItemDto>): List<LoadStepProgress> =
    stops.sortedBy { it.position }.mapNotNull { stop ->
        if (stop.stopType != "PRE_ORDER") return@mapNotNull null
        val summary = stop.preOrder?.summary?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val stopItems = items.filter { it.routeStopId == stop.id }
        val lines = summary.map { s ->
            val matching = stopItems.filter { i ->
                if (s.barcode != null && i.barcode != null) i.barcode == s.barcode else i.name == s.productName
            }
            val boxes = s.boxCount != null
            LoadLineProgress(
                productName = s.productName,
                barcode = s.barcode,
                unit = s.unit,
                isBoxCount = boxes,
                requested = s.boxCount?.toDouble() ?: s.requestedQty ?: 0.0,
                loaded = if (boxes) matching.size.toDouble() else matching.sumOf { it.quantity },
                loadedItems = matching,
            )
        }
        LoadStepProgress(stop, lines)
    }

// Paso actual = primera pre-orden que todavía no se dio por lista.
fun currentLoadStop(steps: List<LoadStepProgress>, doneStopIds: Set<Int>): LoadStepProgress? =
    steps.firstOrNull { it.stop.id !in doneStopIds }

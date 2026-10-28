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

// ---- Modo de carga "por producto" -------------------------------------------
// En vez de recorrer el almacén pre-orden por pre-orden, el almacenista junta
// cada producto UNA vez para todas las pre-órdenes de la ruta. Los totales y el
// reparto de cada escaneo a una parada se derivan de los mismos pasos de arriba
// (nada nuevo en el servidor); cada carga sigue siendo un addRouteItem normal
// con su route_stop_id.

data class ProductStopNeed(val stop: RouteStopDto, val line: LoadLineProgress)

data class ProductTotal(
    val productName: String,
    val barcode: String?,
    val unit: String?,
    val isBoxCount: Boolean,
    val byStop: List<ProductStopNeed>,
) {
    val requested: Double get() = byStop.sumOf { it.line.requested }
    val loaded: Double get() = byStop.sumOf { it.line.loaded }
    val remaining: Double get() = byStop.sumOf { it.line.remaining }
    val complete: Boolean get() = byStop.all { it.line.complete }
}

// Un producto por fila, en el orden en que aparece por primera vez en la ruta.
// Se agrupa por barcode (o nombre si no hay), igual que la clave del resumen.
fun computeProductTotals(steps: List<LoadStepProgress>): List<ProductTotal> {
    val groups = LinkedHashMap<String, MutableList<ProductStopNeed>>()
    for (step in steps) for (l in step.lines) {
        groups.getOrPut(l.barcode ?: l.productName) { mutableListOf() }.add(ProductStopNeed(step.stop, l))
    }
    return groups.values.map { needs ->
        val first = needs.first().line
        ProductTotal(first.productName, first.barcode, first.unit, first.isBoxCount, needs)
    }
}

data class LoadAllocation(val stopId: Int, val quantity: Double)

sealed class AssignResult {
    // A qué parada(s) va lo escaneado. Lbs (isBoxCount) → una sola con cantidad 1
    // (una caja): el peso real que se manda al servidor es el escaneado, no ese 1.
    data class Ok(val allocations: List<LoadAllocation>, val isBoxCount: Boolean = false) : AssignResult()
    // El producto no figura en ninguna pre-orden de la ruta.
    object NotRequested : AssignResult()
    // Pedido de más: `remaining` es lo que todavía falta en total (0 = ya está completo).
    data class Exceeds(val remaining: Double) : AssignResult()
}

// Reparte un escaneo entre las paradas, en orden de ruta, empezando por la
// primera que todavía lo necesita. Lbs: una caja por escaneo (el peso no cuenta,
// igual que computeLoadSteps). Case/Unit/Bucket: llena lo que falta de cada
// parada y el resto pasa a la siguiente. Si no alcanza el total pedido se
// rechaza todo (nunca asigna una parte), igual que el backend rechaza un
// producto fuera de la pre-orden.
fun assignLoad(steps: List<LoadStepProgress>, barcode: String?, name: String, quantity: Double): AssignResult {
    val needs = steps.flatMap { step ->
        step.lines.filter { l ->
            if (l.barcode != null && barcode != null) l.barcode == barcode else l.productName == name
        }.map { l -> ProductStopNeed(step.stop, l) }
    }
    if (needs.isEmpty()) return AssignResult.NotRequested
    val open = needs.filter { it.line.remaining > EPS }
    val totalRemaining = needs.sumOf { it.line.remaining }
    if (open.isEmpty()) return AssignResult.Exceeds(0.0)

    if (open.first().line.isBoxCount) {
        return AssignResult.Ok(listOf(LoadAllocation(open.first().stop.id, 1.0)), isBoxCount = true)
    }
    if (quantity > totalRemaining + EPS) return AssignResult.Exceeds(totalRemaining)
    var left = quantity
    val out = mutableListOf<LoadAllocation>()
    for (n in open) {
        if (left <= EPS) break
        val take = minOf(left, n.line.remaining)
        out.add(LoadAllocation(n.stop.id, take))
        left -= take
    }
    return AssignResult.Ok(out)
}

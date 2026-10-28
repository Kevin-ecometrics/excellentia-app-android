package com.example.test

import com.example.test.data.AssignResult
import com.example.test.data.PreOrderSummaryLine
import com.example.test.data.RouteItemDto
import com.example.test.data.RouteStopDto
import com.example.test.data.RouteStopPreOrder
import com.example.test.data.assignLoad
import com.example.test.data.computeLoadSteps
import com.example.test.data.computeProductTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Modo de carga "por producto": totales entre pre-órdenes y reparto automático
// de cada escaneo a la primera parada (en orden de ruta) que todavía lo necesita.
class ProductLoadModeTest {

    private fun line(barcode: String, unit: String?, boxes: Int? = null, qty: Double? = null) =
        PreOrderSummaryLine(barcode = barcode, productName = "P$barcode", unit = unit, boxCount = boxes, requestedQty = qty ?: boxes?.toDouble())

    private fun stop(id: Int, pos: Int, lines: List<PreOrderSummaryLine>) =
        RouteStopDto(
            id = id, routeId = 1, position = pos, stopType = "PRE_ORDER", status = "PENDING",
            customerName = "C$id",
            preOrder = RouteStopPreOrder(id = id * 10, status = "CONFIRMED", summary = lines)
        )

    private fun item(stopId: Int?, barcode: String, qty: Double, unit: String?) =
        RouteItemDto(id = 0, routeId = 1, productId = 1, routeStopId = stopId, barcode = barcode, quantity = qty, name = "P$barcode", unit = unit)

    private fun steps(stops: List<RouteStopDto>, items: List<RouteItemDto> = emptyList()) = computeLoadSteps(stops, items)

    private fun ok(r: AssignResult) = (r as AssignResult.Ok).allocations.map { it.stopId to it.quantity }

    // ---- computeProductTotals ----

    @Test fun totalsSumSameProductAcrossPreOrders() {
        val s = steps(listOf(
            stop(1, 1, listOf(line("A", "Lbs", boxes = 3))),
            stop(2, 2, listOf(line("A", "Lbs", boxes = 2), line("B", "Case", qty = 4.0))),
        ))
        val totals = computeProductTotals(s)
        assertEquals(listOf("A", "B"), totals.map { it.barcode })
        assertEquals(5.0, totals[0].requested, 0.0)
        assertEquals(listOf(1, 2), totals[0].byStop.map { it.stop.id })
        assertEquals(4.0, totals[1].requested, 0.0)
    }

    @Test fun totalsCountLoadedPerProduct() {
        val s = steps(
            listOf(stop(1, 1, listOf(line("A", "Lbs", boxes = 3))), stop(2, 2, listOf(line("A", "Lbs", boxes = 2)))),
            listOf(item(1, "A", 10.0, "Lbs"), item(2, "A", 11.0, "Lbs"))
        )
        val a = computeProductTotals(s)[0]
        assertEquals(2.0, a.loaded, 0.0)
        assertEquals(3.0, a.remaining, 0.0)
        assertFalse(a.complete)
    }

    @Test fun totalIsCompleteWhenAllStopsAre() {
        val s = steps(
            listOf(stop(1, 1, listOf(line("B", "Case", qty = 2.0))), stop(2, 2, listOf(line("B", "Case", qty = 1.0)))),
            listOf(item(1, "B", 2.0, "Case"), item(2, "B", 1.0, "Case"))
        )
        assertTrue(computeProductTotals(s)[0].complete)
    }

    @Test fun totalsEmptyWithoutPreOrderSteps() {
        assertTrue(computeProductTotals(emptyList()).isEmpty())
    }

    // ---- assignLoad: Lbs (una caja por escaneo) ----

    @Test fun lbsBoxGoesToFirstStopThatStillNeedsBoxes() {
        val s = steps(listOf(stop(1, 1, listOf(line("A", "Lbs", boxes = 1))), stop(2, 2, listOf(line("A", "Lbs", boxes = 2)))))
        assertEquals(listOf(1 to 1.0), ok(assignLoad(s, "A", "PA", 14.45)))
    }

    @Test fun lbsSkipsStopsAlreadyComplete() {
        val s = steps(
            listOf(stop(1, 1, listOf(line("A", "Lbs", boxes = 1))), stop(2, 2, listOf(line("A", "Lbs", boxes = 2)))),
            listOf(item(1, "A", 10.0, "Lbs"))
        )
        assertEquals(listOf(2 to 1.0), ok(assignLoad(s, "A", "PA", 12.0)))
    }

    @Test fun lbsBoxIsRejectedWhenEveryStopIsFull() {
        val s = steps(listOf(stop(1, 1, listOf(line("A", "Lbs", boxes = 1)))), listOf(item(1, "A", 10.0, "Lbs")))
        val r = assignLoad(s, "A", "PA", 12.0)
        assertTrue(r is AssignResult.Exceeds)
        assertEquals(0.0, (r as AssignResult.Exceeds).remaining, 0.0)
    }

    // ---- assignLoad: Case/Unit/Bucket (cantidad repartida) ----

    @Test fun caseFillsFirstStopThenSpillsToNext() {
        val s = steps(listOf(stop(1, 1, listOf(line("B", "Case", qty = 2.0))), stop(2, 2, listOf(line("B", "Case", qty = 3.0)))))
        assertEquals(listOf(1 to 2.0, 2 to 1.0), ok(assignLoad(s, "B", "PB", 3.0)))
    }

    @Test fun caseCountsAlreadyLoadedBeforeAssigning() {
        val s = steps(
            listOf(stop(1, 1, listOf(line("B", "Case", qty = 2.0))), stop(2, 2, listOf(line("B", "Case", qty = 3.0)))),
            listOf(item(1, "B", 1.0, "Case"))
        )
        assertEquals(listOf(1 to 1.0, 2 to 2.0), ok(assignLoad(s, "B", "PB", 3.0)))
    }

    @Test fun caseOverTheTotalIsRejectedWholeNotPartially() {
        val s = steps(listOf(stop(1, 1, listOf(line("B", "Case", qty = 2.0))), stop(2, 2, listOf(line("B", "Case", qty = 1.0)))))
        val r = assignLoad(s, "B", "PB", 4.0)
        assertTrue(r is AssignResult.Exceeds)
        assertEquals(3.0, (r as AssignResult.Exceeds).remaining, 0.0)
    }

    @Test fun caseExactRemainingIsAccepted() {
        val s = steps(listOf(stop(1, 1, listOf(line("B", "Case", qty = 2.0)))))
        assertEquals(listOf(1 to 2.0), ok(assignLoad(s, "B", "PB", 2.0)))
    }

    // ---- assignLoad: producto que no está pedido ----

    @Test fun productNotInAnyPreOrderIsNotRequested() {
        val s = steps(listOf(stop(1, 1, listOf(line("A", "Lbs", boxes = 1)))))
        assertTrue(assignLoad(s, "Z", "PZ", 1.0) is AssignResult.NotRequested)
    }

    @Test fun matchesByNameWhenBarcodeIsMissing() {
        val s = steps(listOf(stop(1, 1, listOf(PreOrderSummaryLine(barcode = null, productName = "PX", unit = "Case", requestedQty = 2.0)))))
        assertEquals(listOf(1 to 2.0), ok(assignLoad(s, null, "PX", 2.0)))
    }

    @Test fun differentBarcodeButSameNameStillMatchesLikeStepMode() {
        // Mismo criterio que computeLoadSteps: si falta el barcode de un lado se compara el nombre.
        val s = steps(listOf(stop(1, 1, listOf(PreOrderSummaryLine(barcode = null, productName = "PX", unit = "Lbs", boxCount = 1, requestedQty = 1.0)))))
        assertEquals(listOf(1 to 1.0), ok(assignLoad(s, "ANY", "PX", 10.0)))
    }
}

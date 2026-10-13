package com.example.test

import com.example.test.data.PreOrderSummaryLine
import com.example.test.data.RouteItemDto
import com.example.test.data.RouteStopDto
import com.example.test.data.RouteStopPreOrder
import com.example.test.data.computeLoadSteps
import com.example.test.data.currentLoadStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLoadProgressTest {

    private fun line(barcode: String, unit: String?, boxes: Int? = null, qty: Double? = null) =
        PreOrderSummaryLine(barcode = barcode, productName = "P$barcode", unit = unit, boxCount = boxes, requestedQty = qty ?: boxes?.toDouble())

    private fun stop(id: Int, pos: Int, type: String = "PRE_ORDER", lines: List<PreOrderSummaryLine> = emptyList()) =
        RouteStopDto(
            id = id, routeId = 1, position = pos, stopType = type, status = "PENDING",
            preOrder = if (type == "PRE_ORDER" || lines.isNotEmpty()) RouteStopPreOrder(id = id * 10, status = "CONFIRMED", summary = lines) else null
        )

    private fun item(stopId: Int?, barcode: String, qty: Double, unit: String?) =
        RouteItemDto(id = 0, routeId = 1, productId = 1, routeStopId = stopId, barcode = barcode, quantity = qty, name = "P$barcode", unit = unit)

    @Test fun onlyPreOrderStopsWithLinesAreSteps() {
        val stops = listOf(
            stop(1, 1, "PRE_ORDER", listOf(line("A", "Lbs", boxes = 2))),
            stop(2, 2, "CUSTOMER"),
            stop(3, 3, "BATCH"),
            stop(4, 4, "PRE_ORDER", emptyList()),
        )
        assertEquals(listOf(1), computeLoadSteps(stops, emptyList()).map { it.stop.id })
    }

    @Test fun stepsFollowStopPosition() {
        val stops = listOf(
            stop(2, 2, lines = listOf(line("A", "Lbs", boxes = 1))),
            stop(1, 1, lines = listOf(line("A", "Lbs", boxes = 1))),
        )
        assertEquals(listOf(1, 2), computeLoadSteps(stops, emptyList()).map { it.stop.id })
    }

    @Test fun lbsCountsBoxLinesNotWeight() {
        val stops = listOf(stop(1, 1, lines = listOf(line("A", "Lbs", boxes = 2))))
        val one = listOf(item(1, "A", 14.45, "Lbs"))
        val two = one + item(1, "A", 15.08, "Lbs")
        assertFalse(computeLoadSteps(stops, one)[0].complete)
        assertEquals(1.0, computeLoadSteps(stops, one)[0].lines[0].loaded, 0.0)
        assertTrue(computeLoadSteps(stops, two)[0].complete)
    }

    @Test fun caseSumsQuantity() {
        val stops = listOf(stop(1, 1, lines = listOf(line("B", "Case", qty = 3.0))))
        assertFalse(computeLoadSteps(stops, listOf(item(1, "B", 2.0, "Case")))[0].complete)
        assertTrue(computeLoadSteps(stops, listOf(item(1, "B", 2.0, "Case"), item(1, "B", 1.0, "Case")))[0].complete)
    }

    @Test fun overLoadingStillCompleteAndRemainingNeverNegative() {
        val stops = listOf(stop(1, 1, lines = listOf(line("B", "Unit", qty = 1.0))))
        val l = computeLoadSteps(stops, listOf(item(1, "B", 3.0, "Unit")))[0].lines[0]
        assertTrue(l.complete)
        assertEquals(0.0, l.remaining, 0.0)
    }

    @Test fun itemsFromOtherStopsOrUnassignedDoNotCount() {
        val stops = listOf(
            stop(1, 1, lines = listOf(line("A", "Lbs", boxes = 1))),
            stop(2, 2, lines = listOf(line("A", "Lbs", boxes = 1))),
        )
        val items = listOf(item(2, "A", 10.0, "Lbs"), item(null, "A", 10.0, "Lbs"))
        val steps = computeLoadSteps(stops, items)
        assertFalse(steps[0].complete)
        assertTrue(steps[1].complete)
    }

    @Test fun stepIsCompleteOnlyWhenEveryLineIs() {
        val stops = listOf(stop(1, 1, lines = listOf(line("A", "Lbs", boxes = 1), line("B", "Case", qty = 2.0))))
        val items = listOf(item(1, "A", 10.0, "Lbs"), item(1, "B", 1.0, "Case"))
        assertFalse(computeLoadSteps(stops, items)[0].complete)
    }

    @Test fun lineWithNoRequestedQuantityIsComplete() {
        val stops = listOf(stop(1, 1, lines = listOf(PreOrderSummaryLine(barcode = "A", productName = "A", unit = "Case"))))
        assertTrue(computeLoadSteps(stops, emptyList())[0].complete)
    }

    @Test fun fallsBackToProductNameWhenBarcodeMissing() {
        val s = listOf(stop(1, 1, lines = listOf(PreOrderSummaryLine(barcode = null, productName = "PX", unit = "Case", requestedQty = 1.0))))
        val it = RouteItemDto(id = 0, routeId = 1, productId = 1, routeStopId = 1, barcode = null, quantity = 1.0, name = "PX", unit = "Case")
        assertTrue(computeLoadSteps(s, listOf(it))[0].complete)
    }

    @Test fun currentStopIsFirstNotDone() {
        val stops = listOf(
            stop(1, 1, lines = listOf(line("A", "Lbs", boxes = 1))),
            stop(2, 2, lines = listOf(line("A", "Lbs", boxes = 1))),
        )
        val steps = computeLoadSteps(stops, emptyList())
        assertEquals(1, currentLoadStop(steps, emptySet())?.stop?.id)
        assertEquals(2, currentLoadStop(steps, setOf(1))?.stop?.id)
        assertNull(currentLoadStop(steps, setOf(1, 2)))
    }

    @Test fun keepsEachLoadedLineIndividuallyWithoutSumming() {
        val stops = listOf(stop(1, 1, lines = listOf(line("A", "Lbs", boxes = 3))))
        val items = listOf(item(1, "A", 14.45, "Lbs"), item(1, "A", 15.08, "Lbs"))
        val l = computeLoadSteps(stops, items)[0].lines[0]
        assertEquals(listOf(14.45, 15.08), l.loadedItems.map { it.quantity })
    }
}

package com.example.test

import com.example.test.data.PreOrderDateRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

// Mismos casos que services/preOrderRules.test.ts (backend) — las dos reglas deben coincidir.
// San Diego: noviembre = PST (UTC-8), julio = PDT (UTC-7).
class PreOrderDateRulesTest {
    private fun earliest(iso: String) =
        PreOrderDateRules.earliestDeliveryDate(Instant.parse(iso)).toString()

    @Test fun beforeCutoffIsTomorrow() {
        assertEquals("2026-11-20", earliest("2026-11-19T21:59:00Z")) // 1:59 pm
        assertEquals("2026-11-20", earliest("2026-11-19T08:00:00Z")) // 0:00
    }

    @Test fun fromCutoffIsDayAfterTomorrow() {
        assertEquals("2026-11-21", earliest("2026-11-19T22:00:00Z")) // 2:00 pm exacto
        assertEquals("2026-11-21", earliest("2026-11-20T07:59:00Z")) // 11:59 pm
    }

    @Test fun daylightSavingAndYearEnd() {
        assertEquals("2026-07-02", earliest("2026-07-01T20:59:00Z"))
        assertEquals("2026-07-03", earliest("2026-07-01T21:00:00Z"))
        assertEquals("2027-01-02", earliest("2026-12-31T23:00:00Z"))
    }

    @Test fun isAllowed() {
        val at3pm = Instant.parse("2026-11-19T23:00:00Z")
        assertFalse(PreOrderDateRules.isAllowed("2026-11-19", at3pm))
        assertFalse(PreOrderDateRules.isAllowed("2026-11-20", at3pm))
        assertTrue(PreOrderDateRules.isAllowed("2026-11-21", at3pm))
        assertFalse(PreOrderDateRules.isAllowed("basura", at3pm))
    }
}

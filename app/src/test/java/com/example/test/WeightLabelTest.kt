package com.example.test

import com.example.test.data.scan.WeightLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeightLabelTest {
    @Test fun parsesRealLabels() {
        assertEquals(14.45, WeightLabel.parse("14.45")!!, 0.0)
        assertEquals(15.08, WeightLabel.parse("15.08")!!, 0.0)
        assertEquals(24.20, WeightLabel.parse("24.20")!!, 0.0)
    }

    @Test fun trimsWhitespace() {
        assertEquals(13.25, WeightLabel.parse(" 13.25\n")!!, 0.0)
    }

    @Test fun rejectsNoData() {
        assertNull(WeightLabel.parse(null))
        assertNull(WeightLabel.parse(""))
        assertNull(WeightLabel.parse("   "))
    }

    @Test fun rejectsOtherFormats() {
        assertNull(WeightLabel.parse("350/1A-26239/14.45"))
        assertNull(WeightLabel.parse("227242003501"))   // código de producto, sin punto
        assertNull(WeightLabel.parse("0227242003501"))
        assertNull(WeightLabel.parse("14,45"))
        assertNull(WeightLabel.parse("14"))
        assertNull(WeightLabel.parse("-14.45"))
        assertNull(WeightLabel.parse("abc"))
    }

    @Test fun rejectsZero() {
        assertNull(WeightLabel.parse("0.00"))
    }
}

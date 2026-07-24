package com.sky22333.skyadb.lanmouse

import org.junit.Assert.assertEquals
import org.junit.Test

class LanMouseScrollAnchorTest {
    @Test
    fun upwardScrollStartsLowOnTheRightSide() {
        assertEquals(
            LanMouseScrollAnchor(x = 960, y = 972),
            calculateScrollAnchor(1_920, 1_080, vertical = true, direction = -1f),
        )
    }

    @Test
    fun downwardScrollStartsHighOnTheRightSide() {
        assertEquals(
            LanMouseScrollAnchor(x = 960, y = 108),
            calculateScrollAnchor(1_920, 1_080, vertical = true, direction = 1f),
        )
    }

    @Test
    fun horizontalScrollUsesDirectionAwareBottomAnchors() {
        assertEquals(
            LanMouseScrollAnchor(x = 1_728, y = 540),
            calculateScrollAnchor(1_920, 1_080, vertical = false, direction = -1f),
        )
        assertEquals(
            LanMouseScrollAnchor(x = 192, y = 540),
            calculateScrollAnchor(1_920, 1_080, vertical = false, direction = 1f),
        )
    }

    @Test
    fun missingScreenInfoUsesTelevisionFallbackSize() {
        assertEquals(
            LanMouseScrollAnchor(x = 960, y = 972),
            calculateScrollAnchor(0, 0, vertical = true, direction = -1f),
        )
    }
}

package com.sky22333.skyadb.ui.flyingmouse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollDeltaAccumulatorTest {
    @Test
    fun upwardSmallDeltasAccumulateInsteadOfRoundingToZero() {
        val accumulator = ScrollDeltaAccumulator()

        accumulator.addVertical(-0.4f)
        accumulator.addVertical(-0.4f)
        val total = accumulator.addVertical(-0.4f)

        assertTrue(total.toInt() < 0)
        assertEquals(-1.92f, total, 0.001f)
    }

    @Test
    fun verticalDirectionsAreSymmetric() {
        val upward = ScrollDeltaAccumulator().addVertical(-8f)
        val downward = ScrollDeltaAccumulator().addVertical(8f)

        assertEquals(-downward, upward, 0.001f)
    }

    @Test
    fun horizontalDirectionsAreSymmetricAndResettable() {
        val accumulator = ScrollDeltaAccumulator()
        val left = accumulator.addHorizontal(-5f)
        accumulator.reset()
        val right = accumulator.addHorizontal(5f)

        assertEquals(-right, left, 0.001f)
    }
}

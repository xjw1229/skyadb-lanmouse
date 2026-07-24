package com.sky22333.skyadb.ui.flyingmouse

/** Accumulates the full drag distance expected by the TV server's accumulated touch protocol. */
internal class ScrollDeltaAccumulator(
    private val multiplier: Float = 1.6f,
) {
    private var horizontal = 0f
    private var vertical = 0f

    fun reset() {
        horizontal = 0f
        vertical = 0f
    }

    fun addHorizontal(delta: Float): Float {
        horizontal += delta * multiplier
        return horizontal
    }

    fun addVertical(delta: Float): Float {
        vertical += delta * multiplier
        return vertical
    }
}

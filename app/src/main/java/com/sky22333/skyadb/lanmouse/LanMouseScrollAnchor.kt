package com.sky22333.skyadb.lanmouse

internal data class LanMouseScrollAnchor(val x: Int, val y: Int)

internal fun calculateScrollAnchor(
    screenWidth: Int,
    screenHeight: Int,
    vertical: Boolean,
    direction: Float,
): LanMouseScrollAnchor {
    val width = screenWidth.takeIf { it > 0 } ?: 1_920
    val height = screenHeight.takeIf { it > 0 } ?: 1_080
    val directionalRatio = if (direction < 0f) 0.90f else 0.10f

    val x = if (vertical) width * 0.50f else width * directionalRatio
    val y = if (vertical) height * directionalRatio else height * 0.50f
    return LanMouseScrollAnchor(
        x = x.toInt().coerceIn(1, width - 1),
        y = y.toInt().coerceIn(1, height - 1),
    )
}

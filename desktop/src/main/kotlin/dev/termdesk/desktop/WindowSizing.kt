package dev.termdesk.desktop

internal data class WindowSizeDp(
    val width: Float,
    val height: Float,
    val minimumWidth: Float,
    val minimumHeight: Float,
)

/** Choose a readable management-console size while keeping both size and minimum on-screen. */
internal fun calculateWindowSize(
    availableWidthDp: Float,
    availableHeightDp: Float,
    scale: Float,
    requestedPhysical: String? = null,
): WindowSizeDp {
    val availableWidth = availableWidthDp.takeIf { it.isFinite() && it > 0f } ?: 1600f
    val availableHeight = availableHeightDp.takeIf { it.isFinite() && it > 0f } ?: 1000f
    val validScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val minWidth = minOf(900f, availableWidth)
    val minHeight = minOf(620f, availableHeight)

    val requested = requestedPhysical
        ?.split("x")
        ?.mapNotNull { it.trim().toFloatOrNull() }
        ?.takeIf { it.size == 2 && it.all { value -> value.isFinite() && value > 0f } }
        ?.let { it[0] / validScale to it[1] / validScale }
    val desired = listOf(2560f to 1440f, 1920f to 1080f)
        .firstOrNull { (width, height) ->
            width / validScale <= availableWidth * 0.96f && height / validScale <= availableHeight * 0.92f
        }
    val width = requested?.first ?: desired?.first?.div(validScale) ?: availableWidth * 0.94f
    val height = requested?.second ?: desired?.second?.div(validScale) ?: availableHeight * 0.90f

    return WindowSizeDp(
        width = width.coerceIn(minWidth, availableWidth),
        height = height.coerceIn(minHeight, availableHeight),
        minimumWidth = minWidth,
        minimumHeight = minHeight,
    )
}

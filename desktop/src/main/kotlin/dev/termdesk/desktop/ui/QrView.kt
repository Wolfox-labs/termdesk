package dev.termdesk.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import dev.termdesk.desktop.agent.QrGrid

/**
 * The pairing QR, drawn from the agent's module grid.
 *
 * The agent already answers with rows of '0'/'1' (see pair.js), so there is no
 * QR library and no SVG parsing in this window: each module is one rectangle.
 */
@Composable
internal fun QrView(grid: QrGrid, size: Dp) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(size)) {
        drawRect(Color.White)
        val n = grid.rows.size
        if (n == 0) return@Canvas
        val cell = this.size.minDimension / n
        for (y in 0 until n) {
            val row = grid.rows[y]
            for (x in 0 until n) {
                if (x < row.length && row[x] == '1') {
                    // Half a pixel of overlap: at this size the rounding gaps
                    // between modules would otherwise read as noise to a scanner.
                    drawRect(ink, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.6f, cell + 0.6f))
                }
            }
        }
    }
}

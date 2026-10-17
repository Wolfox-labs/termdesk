package dev.termdesk.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.termdesk.app.ui.theme.TermDeskTheme
import dev.termdesk.app.ui.theme.ThemeMode
import dev.termdesk.desktop.agent.AgentProcess
import dev.termdesk.desktop.ui.ConsoleScreen
import dev.termdesk.desktop.ui.ConsoleTheme
import java.awt.GraphicsEnvironment

/**
 * TermDesk desktop.
 *
 * A native window — Compose Desktop renders through Skia, so there is no browser
 * anywhere in this app — showing the same agent the phone talks to, in the same
 * visual language: the theme below is the Android app's own `Theme.kt`, compiled
 * straight out of the Android source tree.
 *
 * The agent process is owned here rather than inside the UI, so closing the
 * window stops it: this machine must never be left with an agent nobody can see.
 *
 * `--start` (or TERMDESK_AUTOSTART=1) also brings the agent up when the window
 * opens. It is not the default, because nothing here should start by itself.
 */
fun main(args: Array<String>) = application {
    val autoStart = args.contains("--start") || System.getenv("TERMDESK_AUTOSTART") == "1"
    val node = remember { AgentProcess.findNode() }
    val logs = remember { mutableStateListOf<String>() }
    val agent = remember {
        AgentProcess.findRoot()?.let { root ->
            AgentProcess(root) { line ->
                // Bounded: the window is not a log file, and an unbounded list
                // would grow for as long as it is left open.
                if (logs.size > 800) logs.removeAt(0)
                logs.add(line)
            }
        }
    }

    // Window sizes on Windows are physical pixels; Compose measures in dp. The
    // two are only equal at 100% scaling, and this app is used on 3200x2000 at
    // 200%, where the same 2560x1440 window is 1280x720dp. So the default is a
    // common *physical* window size (2560x1440, else 1920x1080) divided by the
    // screen's own scale, not a dp number that happens to look right on one
    // monitor. At 100% that is the literal size a person asks for; at 200% it is
    // the same window on screen, with type twice as many pixels tall.
    val screen = remember {
        runCatching {
            val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            val config = device.defaultConfiguration
            val scale = config.defaultTransform.scaleX.toFloat().takeIf { it > 0f } ?: 1f
            GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds to scale
        }.getOrNull()
    }
    val scale = screen?.second ?: 1f
    // maximumWindowBounds is already in the same user-space units Compose calls
    // dp (measured here: 3200x2000 at 200% answers 1600x952, not 3200x1904), so
    // it is not divided by the scale a second time. A *desired* window size is
    // physical pixels and does get divided, because that is the number a person
    // reads off a resolution list.
    val availableWidth = screen?.first?.width?.toFloat() ?: 1600f
    val availableHeight = screen?.first?.height?.toFloat() ?: 1000f

    val desired = listOf(2560f to 1440f, 1920f to 1080f)
        .firstOrNull { (w, h) -> w / scale <= availableWidth * 0.96f && h / scale <= availableHeight * 0.92f }
    // A requested size is read in PHYSICAL pixels too: TERMDESK_WINDOW=2560x1440
    // is the window a person would pick in a screen-resolution list.
    val requested = System.getenv("TERMDESK_WINDOW")
        ?.split("x")
        ?.mapNotNull { it.trim().toFloatOrNull() }
        ?.takeIf { it.size == 2 }
        ?.let { it[0] / scale to it[1] / scale }
    val widthDp = requested?.first
        ?: desired?.let { it.first / scale }
        ?: (availableWidth * 0.94f)
    val heightDp = requested?.second
        ?: desired?.let { it.second / scale }
        ?: (availableHeight * 0.90f)

    val state = rememberWindowState(
        size = DpSize(
            width = widthDp.coerceAtMost(availableWidth).coerceAtLeast(900f).dp,
            height = heightDp.coerceAtMost(availableHeight).coerceAtLeast(620f).dp,
        ),
    )
    if (System.getenv("TERMDESK_DEBUG_WINDOW") == "1") {
        println("[termdesk] scale=$scale available=${availableWidth}x$availableHeight window=${widthDp}x${heightDp}")
    }

    Window(
        onCloseRequest = {
            agent?.stop()
            exitApplication()
        },
        state = state,
        title = System.getenv("TERMDESK_WINDOW_TITLE") ?: "TermDesk PC",
    ) {
        LaunchedEffect(Unit) {
            // User-space units, the same ones maximumWindowBounds uses.
            window.minimumSize = java.awt.Dimension(900, 620)
        }
        TermDeskTheme(mode = ThemeMode.System) {
            ConsoleTheme {
                ConsoleScreen(
                    agent = agent,
                    logs = logs,
                    nodeAvailable = node != null,
                    autoStart = autoStart,
                )
            }
        }
    }
}
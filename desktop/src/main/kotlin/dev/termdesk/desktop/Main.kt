package dev.termdesk.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.termdesk.app.ui.theme.TermDeskTheme
import dev.termdesk.app.ui.theme.ThemeMode
import dev.termdesk.desktop.agent.AgentProcess
import dev.termdesk.desktop.ui.DesktopApp
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

    // Never taller or wider than the screen. The size below is in dp, and on a
    // 200% display that is twice as many pixels — a window that cannot fit is a
    // window whose buttons are off-screen.
    val bounds = remember {
        runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
    }
    val state = rememberWindowState(
        size = DpSize(
            width = minOf(1080.dp, ((bounds?.width ?: 1200) * 0.9f).dp),
            height = minOf(800.dp, ((bounds?.height ?: 900) * 0.9f).dp),
        ),
    )

    Window(
        onCloseRequest = {
            agent?.stop()
            exitApplication()
        },
        state = state,
        title = "TermDesk PC",
    ) {
        TermDeskTheme(mode = ThemeMode.System) {
            DesktopApp(agent = agent, logs = logs, nodeAvailable = node != null, autoStart = autoStart)
        }
    }
}
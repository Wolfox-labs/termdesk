package dev.termdesk.desktop.agent

import java.io.File
import java.util.concurrent.TimeUnit

internal fun packagedAgentRoot(codeLocation: File): File? {
    if (!codeLocation.isFile) return null
    return File(codeLocation.parentFile, "resources")
        .takeIf { File(it, "pc-agent/src/server.js").isFile }
}

/**
 * The TermDesk PC agent, owned by this window.
 *
 * The shell does not reimplement the agent: it starts the same
 * `pc-agent/src/server.js` the phone talks to, shows its output, and stops it.
 * That is what keeps one implementation of kernels, sessions and the tunnel for
 * both clients.
 */
class AgentProcess(
    private val root: File,
    private val onLine: (String) -> Unit,
) {
    @Volatile
    private var process: Process? = null

    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * Whether the running agent was started by THIS window.
     *
     * The agent can also be up because a launcher script started it. The window
     * must not pretend it can stop something it does not own — killing a process
     * it did not spawn could take down work the user is in the middle of.
     */
    val isOwner: Boolean get() = isRunning

    /**
     * Start the agent. `--enable-shell` and `--tunnel` mirror what the one-click
     * launcher passes, so the window and the script produce the same agent.
     */
    fun start(port: Int, shell: Boolean = true, tunnel: Boolean = true): Result<Unit> {
        if (isRunning) return Result.success(Unit)
        val node = findNode()
            ?: return Result.failure(IllegalStateException("找不到 Node.js —— 装一个 Node 20+，或用 TERMDESK_NODE 指定路径"))
        val entry = File(root, "pc-agent/src/server.js")
        if (!entry.isFile) return Result.failure(IllegalStateException("找不到 agent 入口：${entry.path}"))

        val command = mutableListOf(node.path, entry.path, "--host", "0.0.0.0", "--port", port.toString())
        if (shell) command += "--enable-shell"
        if (tunnel) command += "--tunnel"

        return runCatching {
            val builder = ProcessBuilder(command)
                .directory(File(root, "pc-agent"))
                .redirectErrorStream(true)
            // Lets the kernel list state what each kernel really declares.
            builder.environment()["TERMDESK_KERNELS_PROBE"] = "1"
            val started = builder.start()
            process = started

            Thread {
                runCatching {
                    started.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { raw -> sanitize(raw)?.let(onLine) }
                    }
                }
            }.apply { isDaemon = true; name = "termdesk-agent-stdout" }.start()

            Thread {
                runCatching { started.waitFor() }
                val code = runCatching { started.exitValue() }.getOrDefault(-1)
                onLine("[进程已退出] 退出码 $code")
            }.apply { isDaemon = true; name = "termdesk-agent-wait" }.start()
        }
    }

    /** Stop the agent AND its children — cloudflared is a child and must go too. */
    fun stop() {
        val current = process ?: return
        process = null
        val taskkill = if (isWindowsHost()) findWindowsTaskkill() else null
        if (taskkill != null) {
            runCatching {
                ProcessBuilder(taskkill.path, "/PID", current.pid().toString(), "/T", "/F")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(6, TimeUnit.SECONDS)
            }
        }
        // On hosts without taskkill (and when it fails), stop this owned process
        // tree through the JDK. This is intentionally limited to `process`, the
        // child this window started; an independently started agent is untouched.
        runCatching {
            current.toHandle().descendants().toList().asReversed().forEach { it.destroyForcibly() }
        }
        runCatching { current.destroy() }
        runCatching {
            if (!current.waitFor(3, TimeUnit.SECONDS)) current.destroyForcibly()
        }
    }

    companion object {
        /** Terminal colouring and other escape sequences. */
        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

        /** Lines that are nothing but block glyphs — the terminal QR code. */
        private val BLOCK_ART = Regex("^[\\s\u2588\u2580\u2584\u258C\u2590\u2591\u2592\u2593]+$")

        /**
         * Make a console line readable in a text panel.
         *
         * The agent prints a block-character QR for people without a browser and
         * colours its own banner. Both are right in a terminal and wrong here: the
         * escape sequences render as literal garbage, and the QR is drawn properly
         * by this window anyway. Removed once, at the source, so nothing that
         * reads the log has to know about it.
         */
        fun sanitize(line: String): String? {
            val clean = ANSI.replace(line, "").trimEnd()
            if (clean.isEmpty()) return null
            if (BLOCK_ART.matches(clean)) return null
            return clean
        }

        /** Node, by explicit override, platform install locations, then PATH. */
        fun findNode(): File? = findNodeExecutable()

        /**
         * Locate the repository.
         *
         * Searched rather than hard-coded: during development this runs from the
         * Gradle project directory, and later from an installed location, and
         * both have to find the same agent. Walking up also means the desktop
         * build does not have to know how it was launched.
         */
        fun findRoot(): File? {
            System.getenv("TERMDESK_ROOT")?.let { if (File(it, "pc-agent/src/server.js").isFile) return File(it) }
            val starts = mutableListOf(File(System.getProperty("user.dir")))
            runCatching {
                val location = AgentProcess::class.java.protectionDomain?.codeSource?.location
                if (location != null) {
                    val code = File(location.toURI())
                    starts += code
                    // jpackage places app resources next to the application JAR.
                    // The installed image stages the agent at app/resources/pc-agent.
                    packagedAgentRoot(code)?.let { return it }
                }
            }
            for (start in starts) {
                var dir: File? = start.absoluteFile
                var hops = 0
                while (dir != null && hops < 6) {
                    if (File(dir, "pc-agent/src/server.js").isFile) return dir
                    dir = dir.parentFile
                    hops += 1
                }
            }
            return null
        }
    }
}

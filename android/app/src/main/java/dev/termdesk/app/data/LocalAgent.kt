package dev.termdesk.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The agent that runs *inside* the phone's sandbox.
 *
 * The idea it implements: a sandbox is a second machine, so it should be served
 * by the same agent as the PC instead of by a second, phone-specific code path.
 * That agent is staged into the APK from `pc-agent/src` (see the syncLocalAgent
 * Gradle task) and speaks the identical protocol, which is why files, terminal,
 * sessions and chats need no new client code at all - only a different socket.
 *
 * It is loopback-only (`--local`), so there is nothing to pair and nothing to
 * expose: the phone is the only thing that can reach it.
 *
 * Why the binary must exist first: the agent is a Node program, and Node lives in
 * the sandbox payload. No payload means no host, and the state says so instead of
 * pretending to start.
 */
enum class LocalAgentStage { ABSENT, STAGED, STARTING, RUNNING, FAILED }

data class LocalAgentState(
    val stage: LocalAgentStage = LocalAgentStage.ABSENT,
    /** What is happening or what went wrong - always a full sentence. */
    val note: String = "",
    val port: Int = LocalAgent.PORT,
    val token: String = "",
) {
    val ready: Boolean get() = stage == LocalAgentStage.RUNNING && token.isNotEmpty()
    val url: String get() = "ws://127.0.0.1:$port"
}

class LocalAgent(private val context: Context) {

    private val _state = MutableStateFlow(LocalAgentState())
    val state: StateFlow<LocalAgentState> = _state.asStateFlow()

    @Volatile private var process: Process? = null

    /** The agent's own output, so a failure can be read instead of guessed. */
    private val tail = ArrayDeque<String>()
    private val tailLock = Any()

    private fun filesDir(): File = context.filesDir
    private fun agentDir(): File = File(filesDir(), "local-agent")
    private fun sandboxUsr(): File = File(filesDir(), "usr")
    private fun home(): File = File(filesDir(), "home").apply { mkdirs() }
    private fun nodeBin(): File = File(sandboxUsr(), "bin/node")
    private fun bashBin(): File = File(sandboxUsr(), "bin/bash")
    private fun tokenFile(): File = File(home(), ".termdesk/token")
    private fun stampFile(): File = File(agentDir(), ".staged")

    /** Is there a sandbox to host this at all? */
    fun installed(): Boolean = bashBin().exists() && nodeBin().exists()

    /** What is on disk right now, without starting anything. */
    fun refresh(): LocalAgentState {
        val current = process?.takeIf { it.isAlive }
        val state = when {
            !installed() -> LocalAgentState(
                stage = LocalAgentStage.ABSENT,
                note = "还没有本地内核：先装上手机里的沙盒，它才有 Node 来跑这个代理",
            )
            !File(agentDir(), "src/server.js").exists() -> LocalAgentState(
                stage = LocalAgentStage.ABSENT,
                note = "沙盒有了，但代理还没解包（首次启动会做）",
            )
            current != null && _state.value.stage == LocalAgentStage.RUNNING -> _state.value
            else -> LocalAgentState(stage = LocalAgentStage.STAGED, note = "代理已就位，尚未启动")
        }
        _state.value = state
        return state
    }

    /**
     * Copy the agent out of the APK into the app's private dir.
     *
     * Re-staged when the app version changes: the agent and the app are one build,
     * so a new app means a new agent, and leaving the old one behind would be
     * exactly the version drift this design exists to prevent.
     */
    suspend fun stage(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!installed()) return@withContext false
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        val stamp = stampFile()
        val current = if (stamp.exists()) stamp.readText().trim() else ""
        if (!force && current == version && File(agentDir(), "src/server.js").exists()) return@withContext true

        _state.value = _state.value.copy(stage = LocalAgentStage.STAGED, note = "正在解包本地代理…")
        runCatching {
            val dest = agentDir()
            dest.deleteRecursively()  // never walk symlinks: this tree has none
            copyAssetTree("local-agent", dest)
            stamp.writeText(version)
        }.onFailure { err ->
            _state.value = LocalAgentState(
                stage = LocalAgentStage.FAILED,
                note = "本地代理解包失败：${err.message}",
            )
            return@withContext false
        }
        Log.i(TAG, "staged local agent for $version")
        true
    }

    private fun copyAssetTree(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) copyAssetTree("$assetPath/$child", File(dest, child))
    }

    /**
     * Start the agent and wait until it is genuinely answering.
     *
     * "Started" means the process is alive AND the port accepts a connection AND
     * the token file is readable - the same standard the rest of this project uses,
     * because a process that has not answered anything is not a working kernel.
     */
    suspend fun start(): LocalAgentState = withContext(Dispatchers.IO) {
        if (!installed()) return@withContext fail("还没有本地内核：先装上手机里的沙盒")
        if (!stage()) return@withContext _state.value
        stop()

        synchronized(tailLock) { tail.clear() }
        _state.value = _state.value.copy(
            stage = LocalAgentStage.STARTING,
            note = "正在启动本地代理…",
            token = "",
        )

        // The working directory matters: the entry point is passed as a relative
        // path, and the app process starts somewhere else entirely (usually "/").
        val child = runCatching {
            ProcessBuilder(argv())
                .directory(agentDir())
                .apply { environment().putAll(env()) }
                .start()
        }.getOrElse { return@withContext fail("本地代理起不来：${it.message}") }
        process = child
        val reader = Thread {
            runCatching {
                child.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(tailLock) {
                        tail.addLast(line)
                        if (tail.size > 40) tail.removeFirst()
                    }
                    Log.i(TAG, line)
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!child.isAlive) break
            if (portAnswers()) {
                val token = readToken()
                if (token != null) {
                    val state = LocalAgentState(
                        stage = LocalAgentStage.RUNNING,
                        note = "本地代理已在 ws://127.0.0.1:$PORT 上应答",
                        token = token,
                    )
                    _state.value = state
                    Log.i(TAG, "local agent ready on port $PORT")
                    return@withContext state
                }
            }
            Thread.sleep(200)
        }
        val why = synchronized(tailLock) { tail.joinToString("\n").takeLast(400) }
        fail(
            if (child.isAlive) "本地代理启动了但一直没有应答（端口 $PORT）${if (why.isBlank()) "" else "：$why"}"
            else "本地代理退出了${if (why.isBlank()) "" else "：$why"}",
        )
    }

    fun stop() {
        val child = process ?: return
        process = null
        runCatching { child.destroy() }
        if (_state.value.stage == LocalAgentStage.RUNNING) {
            _state.value = LocalAgentState(stage = LocalAgentStage.STAGED, note = "本地代理已停止")
        }
    }

    private fun fail(message: String): LocalAgentState {
        Log.w(TAG, message)
        _state.value = LocalAgentState(stage = LocalAgentStage.FAILED, note = message)
        return _state.value
    }

    /** Is something listening on the loopback port? */
    private fun portAnswers(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 250) }
        true
    }.getOrDefault(false)

    /** The token the agent wrote into the sandbox home. */
    private fun readToken(): String? = runCatching {
        val file = tokenFile()
        if (!file.exists()) return@runCatching null
        file.readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun argv(): List<String> = listOf(
        nodeBin().absolutePath,
        "src/server.js",
        "--local",
        "--enable-shell",
        "--port", PORT.toString(),
    )

    /**
     * The environment the sandbox expects.
     *
     * PATH points at the sandbox's own bin first: the agent must use the sandbox's
     * Node and bash, not anything the Android system image happens to ship. The
     * browsable roots are the sandbox home and its userland - both are the phone's
     * own files, which is the whole point of a local kernel.
     */
    private fun env(): Map<String, String> {
        val usr = sandboxUsr().absolutePath
        val home = home().absolutePath
        val env = mutableMapOf(
            "PATH" to "$usr/bin:$usr/bin/applets:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to "$usr/lib",
            "PREFIX" to usr,
            "HOME" to home,
            "TMPDIR" to File(usr, "tmp").apply { mkdirs() }.absolutePath,
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "TERMDESK_ROOTS" to "$home;$usr",
            "TERMDESK_SHELL" to bashBin().absolutePath,
            "TERMDESK_SHELL_CWD" to home,
            // The sandbox routes to the free model by default. A desktop default of a
            // paid model is a cost decision that should not be inherited by a phone
            // someone taps at; the model can still be named per turn through the agent.
            "TERMDESK_CHAT_PROVIDER" to "wolfox",
            "TERMDESK_CHAT_MODEL" to "mimo-v2.6-flash",
        )
        // DSH, when it has been installed into the sandbox. The kernel table asks
        // TERMDESK_DSH first, and DSH_HOME keeps its profiles, credentials and
        // sessions inside the sandbox instead of a stock ~/.dsh it cannot write.
        val dshHome = File(home(), ".dsh")
        val dshEntry = File(dshHome, "profiles/sdk/node_modules/@deepseek-ai/dsh/lib/bin.js")
        if (dshEntry.exists()) {
            env["DSH_HOME"] = dshHome.absolutePath
            env["TERMDESK_DSH"] = dshEntry.absolutePath
            env.putAll(dshCredentials(dshHome))
        }
        return env
    }

    /**
     * DSH provider rows name an environment variable (`apiKeyEnv: WOLFOX_API_KEY`)
     * rather than a key, so the value has to be in the environment of the process
     * that runs the kernel. On a desktop the app that starts DSH does that; here
     * nobody did, and the result was the worst kind of failure: the turn ended
     * with no answer and no error, because the request went out unauthenticated.
     *
     * Only \`NAME: value\` lines whose name is a plain env-style identifier are
     * carried over; anything else in the file is left alone rather than guessed at.
     */
    private fun dshCredentials(dshHome: File): Map<String, String> {
        val file = File(dshHome, ".credentials.yaml")
        if (!file.exists()) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (line in file.readLines()) {
            val at = line.indexOf(':')
            if (at <= 0) continue
            val name = line.substring(0, at).trim()
            val value = line.substring(at + 1).trim().trim('\'', '"')
            if (name.matches(Regex("[A-Z][A-Z0-9_]*")) && value.isNotEmpty()) out[name] = value
        }
        return out
    }

    companion object {
        /** Loopback only, and unusual enough not to collide with the debug agent. */
        const val PORT = 7431
        private const val TAG = "TermDeskLocalAgent"
        private const val READY_TIMEOUT_MS = 30_000L
    }
}

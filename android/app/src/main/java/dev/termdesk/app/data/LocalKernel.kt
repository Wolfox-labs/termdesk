package dev.termdesk.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The local kernel: a Termux userland that runs ON the phone.
 *
 * Why it is downloaded rather than shipped inside the APK: the payload is ~90 MB
 * compressed, and bundling it would make every install (and every update of a
 * one-line UI fix) carry it. Why the PC serves it: the machine that can rebuild
 * it is the machine that already has the toolchain, and the phone is already
 * authenticated to it.
 *
 * The install is deliberately literal - download, verify the sha256, unpack with
 * the phone's own tar, then RUN something and show what came back - because
 * "installed" that has never executed a byte is a claim, not a fact.
 */
data class LocalKernelManifest(
    val version: String,
    val packageName: String,
    val sizeBytes: Long,
    val sha256: String,
    val prefix: String,
    val installDir: String,
    val healthArgv: List<String>,
    val healthToken: String,
    val shellEntry: String?,
) {
    companion object {
        /**
         * The PC's manifest, read defensively: a field this app needs but the
         * payload does not define is reported as missing instead of defaulted
         * into something that looks installed.
         */
        fun from(json: JSONObject): LocalKernelManifest {
            val entry = json.optJSONObject("entryPoints")
            val health = json.optJSONObject("healthCheck")
            val argv = health?.optJSONArray("argv")?.let { array ->
                (0 until array.length()).map { array.optString(it) }
            }.orEmpty()
            return LocalKernelManifest(
                version = json.optString("version"),
                packageName = json.optString("package"),
                sizeBytes = json.optLong("sizeBytes"),
                sha256 = json.optString("sha256"),
                prefix = json.optString("prefix"),
                installDir = json.optString("installDir", "usr"),
                healthArgv = argv,
                healthToken = argv.lastOrNull()?.substringAfterLast(' ')?.trim().orEmpty(),
                shellEntry = entry?.optString("shell")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** Where the install is, in one word, so the screen never has to guess. */
enum class LocalKernelStage { UNKNOWN, ABSENT, DOWNLOADING, VERIFYING, EXTRACTING, CHECKING, READY, FAILED }

data class LocalKernelState(
    val stage: LocalKernelStage = LocalKernelStage.UNKNOWN,
    val progress: Float = 0f,
    /** What is happening, or what went wrong - always a full sentence. */
    val note: String = "",
    val manifest: LocalKernelManifest? = null,
    /** Bytes on disk for the unpacked tree, when it is there. */
    val installedBytes: Long = 0,
    val installedVersion: String? = null,
    /** The health check's own output, so a failure can be read, not guessed. */
    val healthOutput: String? = null,
) {
    val installed: Boolean get() = stage == LocalKernelStage.READY
    val busy: Boolean
        get() = stage == LocalKernelStage.DOWNLOADING || stage == LocalKernelStage.VERIFYING ||
            stage == LocalKernelStage.EXTRACTING || stage == LocalKernelStage.CHECKING
}

/**
 * Downloads, verifies, unpacks and health-checks the local kernel.
 *
 * Every step reports itself through [state]; nothing is inferred from the
 * presence of a file, because a half-unpacked tree looks exactly like an
 * installed one until something tries to run.
 */
class LocalKernelInstaller(private val context: Context, private val http: OkHttpClient) {

    /**
     * A step-by-step trace. Each stage is logged and each failure keeps the whole
     * reason: a phone-side install that silently does nothing is indistinguishable
     * from a tap that never landed, which is exactly what happened once.
     */
    private fun step(message: String) { Log.i(TAG, message) }

    private val _state = MutableStateFlow(LocalKernelState())
    val state: StateFlow<LocalKernelState> = _state.asStateFlow()

    /** Where the payload unpacks: the manifest says, because the payload's absolute
     *  symlinks are baked to that path. */
    private fun targetDir(): File = File(filesDir(), _state.value.manifest?.installDir ?: "usr")

    private fun filesDir(): File = context.filesDir

    private fun stampFile(): File = File(context.filesDir, "local-kernel-installed.json")

    /** What is on the phone right now, without touching the network. */
    fun inspect(): LocalKernelState {
        val stamp = stampFile()
        val shell = File(filesDir(), "usr/bin/bash")

        val measured = Storage.measureSandbox(targetDir())
        val bytes = measured.bytes
        if (!measured.complete) step("install: 用量测量被上限截断，界面会显示下限")
        if (!stamp.exists()) {
            return LocalKernelState(
                stage = if (bytes > 0) LocalKernelStage.FAILED else LocalKernelStage.ABSENT,
                note = if (bytes > 0)
                    "有文件但没有安装记录（${Storage.format(bytes)}）：上次安装没走完，建议重装"
                else "还没有安装本地内核",
                installedBytes = bytes,
            )
        }
        val recorded = runCatching { JSONObject(stamp.readText()) }.getOrNull()
        val version = recorded?.optString("version")?.takeIf { it.isNotBlank() }
        val hasShell = shell.exists()
        return LocalKernelState(
            stage = if (hasShell) LocalKernelStage.READY else LocalKernelStage.FAILED,
            note = if (hasShell) "已安装" else "安装记录还在，但 $shell 不见了：需要重装",
            installedBytes = bytes,
            installedVersion = version,
            healthOutput = recorded?.optString("health")?.takeIf { it.isNotBlank() },
        )
    }

    fun refresh() { _state.value = inspect() }

    /**
     * Fetch the PC's manifest. Metadata only: no payload bytes, no unpacking.
     */
    suspend fun loadManifest(base: String, token: String): LocalKernelState = withContext(Dispatchers.IO) {
        val result = runCatching {
            val request = Request.Builder()
                .url("$base/kernel/local")
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("电脑端回答 HTTP ${response.code}：${text.take(160)}")
                val json = JSONObject(text)
                if (!json.optBoolean("ok", false)) error(json.optString("message", "电脑端没有可用的载荷"))
                val manifest = LocalKernelManifest.from(json.optJSONObject("manifest") ?: JSONObject())
                if (!json.optBoolean("present", false)) {
                    error("清单有了，但电脑上找不到载荷文件（${manifest.packageName}），先在电脑上构建")
                }
                manifest
            }
        }
        result.fold(
            onSuccess = { manifest ->
                val current = inspect()
                LocalKernelState(
                    stage = if (current.installed) LocalKernelStage.READY else LocalKernelStage.ABSENT,
                    // Never overwrite a warning with a cheerful line: leftover files from a
                    // half-finished install are exactly what the user needs to see.
                    note = when {
                        current.installed -> "已安装 ${current.installedVersion ?: ""}"
                        current.stage == LocalKernelStage.FAILED -> current.note
                        else -> "可以安装（${manifest.version}，${Storage.format(manifest.sizeBytes)}）"
                    },
                    manifest = manifest,
                    installedBytes = current.installedBytes,
                    installedVersion = current.installedVersion,
                )
            },
            onFailure = { err ->
                _state.value.copy(note = "读不到载荷清单：${err.message}", stage = if (_state.value.installed) LocalKernelStage.READY else LocalKernelStage.FAILED)
            },
        ).also { _state.value = it }
    }

    /** Download -> sha256 -> unpack -> run it. */
    suspend fun install(base: String, token: String): LocalKernelState = withContext(Dispatchers.IO) {
        val manifest = _state.value.manifest ?: loadManifest(base, token).manifest
        if (manifest == null) return@withContext _state.value
        val pkg = File(context.cacheDir, manifest.packageName)

        // 1. download, hashing as it goes: the hash of the bytes actually
        //    received, not of a file assumed to be right.
        _state.value = _state.value.copy(stage = LocalKernelStage.DOWNLOADING, progress = 0f, note = "下载中…")
        step("install: 开始下载 ${manifest.packageName}（${manifest.sizeBytes} 字节）")
        val digest = MessageDigest.getInstance("SHA-256")
        val downloadError = runCatching {
            pkg.delete()
            val request = Request.Builder()
                .url("$base/kernel/local.pkg")
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
                val body = response.body ?: error("下载失败：没有响应体")
                val total = if (body.contentLength() > 0) body.contentLength() else manifest.sizeBytes
                body.byteStream().use { input ->
                    pkg.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = input.read(buffer)
                        var done = 0L
                        while (read >= 0) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            _state.value = _state.value.copy(
                                progress = if (total > 0) done.toFloat() / total else 0f,
                                note = "下载中 ${Storage.format(done)} / ${Storage.format(total)}",
                            )
                            read = input.read(buffer)
                        }
                    }
                }
            }
        }.exceptionOrNull()
        if (downloadError != null) {
            pkg.delete()
            return@withContext fail("下载失败：${downloadError.message}")
        }

        // 2. verify
        _state.value = _state.value.copy(stage = LocalKernelStage.VERIFYING, note = "校验完整性…")
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        step("install: 校验 sha256 实际=${actual.take(16)} 期望=${manifest.sha256.take(16)}")
        if (actual != manifest.sha256.lowercase()) {
            pkg.delete()
            return@withContext fail("校验不通过：下载到的文件与电脑上的不是同一个（期望 ${manifest.sha256.take(12)}…，实际 ${actual.take(12)}…）")
        }
        if (pkg.length() != manifest.sizeBytes) {
            return@withContext fail("校验不通过：大小不符（期望 ${manifest.sizeBytes}，实际 ${pkg.length()}）")
        }

        // 3. unpack with the phone's own tar: toybox handles gzip and restores
        //    symlinks, which is what a Termux userland mostly is. It REFUSES
        //    absolute link targets, and this payload has 21 of them (measured),
        //    all under /data/data/<pkg>/files/usr - the compatibility spelling of
        //    a directory this app process cannot even see. tar therefore exits 1
        //    having created everything except those links, and they are put back
        //    afterwards as RELATIVE links (which is what the payload should have
        //    contained in the first place).
        _state.value = _state.value.copy(stage = LocalKernelStage.EXTRACTING, note = "解包中…")
        val extractRoot = targetDir()
        step("install: 解包到 ${extractRoot.absolutePath}")
        extractRoot.mkdirs()
        val tar = File("/system/bin/tar")
        if (!tar.exists()) return@withContext fail("这台手机没有 /system/bin/tar，无法解包")
        val extract = runCatching {
            val process = ProcessBuilder(tar.absolutePath, "-xzf", pkg.absolutePath, "-C", extractRoot.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) {
                step("install: tar 退出码 $code，尝试补齐绝对符号链接")
                val missing = restoreAbsoluteLinks(pkg, extractRoot, manifest.prefix)
                if (missing.isNotEmpty()) {
                    error("tar 退出码 $code，还有 ${missing.size} 个链接没补上：${missing.take(3).joinToString()}｜${output.takeLast(200)}")
                }
                step("install: 已补齐绝对符号链接")
            }
            output
        }.exceptionOrNull()
        if (extract != null) return@withContext fail("解包失败：${extract.message}")
        step("install: 解包完成")
        pkg.delete()

        // 4. run it. "Installed" that has never executed is a claim, not a fact.
        _state.value = _state.value.copy(stage = LocalKernelStage.CHECKING, note = "运行健康检查…")
        val argv = manifest.healthArgv
        // The manifest's paths are relative to the app's private root, and the payload
        // unpacks into `installDir`. Joining both gave files/usr/usr/bin/bash and the
        // check failed on a perfectly good install.
        val shell = File(filesDir(), manifest.shellEntry ?: "usr/bin/bash")
        step("install: 健康检查 $shell")
        if (argv.isEmpty() || !shell.exists()) return@withContext fail("解包完成，但找不到 ${shell.absolutePath}")
        val check = runCatching {
            val command = ArrayList<String>(argv.size + 1)
            command.add(shell.absolutePath)
            command.addAll(argv.drop(1))
            val process = ProcessBuilder(command)
                .directory(filesDir())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            if (code != 0) error("退出码 $code：${output.takeLast(300)}")
            output
        }
        check.fold(
            onSuccess = { output ->
                val ok = manifest.healthToken.isEmpty() || output.contains(manifest.healthToken)
                if (!ok) {
                    fail("健康检查输出了意料之外的内容：$output")
                } else {
                    stampFile().writeText(
                        JSONObject()
                            .put("version", manifest.version)
                            .put("sha256", manifest.sha256)
                            .put("installedAt", System.currentTimeMillis())
                            .put("health", output)
                            .toString(),
                    )
                    val measured = Storage.measureSandbox(extractRoot)
                    val bytes = measured.bytes
                    LocalKernelState(
                        stage = LocalKernelStage.READY,
                        progress = 1f,
                        note = "已安装并跑通：$output",
                        manifest = manifest,
                        installedBytes = bytes,
                        installedVersion = manifest.version,
                        healthOutput = output,
                    )
                }
            },
            onFailure = { err -> fail("装好了，但跑不起来：${err.message}") },
        ).also { _state.value = it }
    }

    /** Remove the unpacked tree and its stamp. Off the main thread by contract. */
    suspend fun remove(): LocalKernelState = withContext(Dispatchers.IO) {
        deleteRecursively(targetDir())
        stampFile().delete()
        File(context.cacheDir, _state.value.manifest?.packageName ?: "").delete()
        inspect().also { _state.value = it }
    }

    private fun fail(message: String): LocalKernelState {
        step("install: 失败 $message")
        return LocalKernelState(
            stage = LocalKernelStage.FAILED,
            note = message,
            manifest = _state.value.manifest,
            installedBytes = Storage.measureSandbox(targetDir()).bytes,
        )
    }

    /**
     * Never walk through a symlink: it points elsewhere, and the target is not
     * ours to delete.
     */
    private fun deleteRecursively(file: File) {
        try {
            val path = file.toPath()
            if (!java.nio.file.Files.isSymbolicLink(path) && java.nio.file.Files.isDirectory(path)) {
                java.nio.file.Files.newDirectoryStream(path).use { children ->
                    for (child in children) deleteRecursively(child.toFile())
                }
            }
            file.delete()
        } catch (_: Exception) {
            // Best effort: a locked file is not a reason to fail the whole clear.
        }
    }
}

private const val TAG = "TermDeskLocalKernel"

    /**
     * Put back the symlinks toybox tar refused, as RELATIVE links.
     *
     * `tar -tvzf` lists them (`name -> target`); each target that starts with the
     * payload prefix becomes a relative link from the entry's own directory, which
     * is what a portable payload should have contained. Returns the entries that are
     * still missing afterwards - the caller treats a non-empty list as a hard
     * failure, so a mis-parsed name cannot pass silently.
     */
    private fun restoreAbsoluteLinks(pkg: File, root: File, prefix: String): List<String> {
        val tar = File("/system/bin/tar")
        val listing = ProcessBuilder(tar.absolutePath, "-tvzf", pkg.absolutePath)
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        val wanted = mutableListOf<Pair<String, String>>()
        for (line in listing.lineSequence()) {
            val at = line.indexOf(" -> ");
            if (at < 0) continue
            val target = line.substring(at + 4).trim()
            if (!target.startsWith(prefix)) continue
            val name = line.substring(0, at).trim().split(Regex("\\\\s+")).lastOrNull() ?: continue
            wanted += name.removePrefix("./") to target
        }
        val missing = mutableListOf<String>()
        for ((name, target) in wanted) {
            val link = File(root, name)
            if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) continue
            link.parentFile?.mkdirs()
            val suffix = target.removePrefix(prefix).trimStart('/');
            val parent = link.parentFile?.absolutePath ?: root.absolutePath
            val relative = File(target.replace(prefix, parent))
            val linkTarget = try {
                relative.toPath()
            } catch (err: Exception) {
                null
            }
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), File(suffix).toPath())
            } catch (err: Exception) {
                missing += "$name -> $suffix (${err.message})"
            }
            if (!java.nio.file.Files.isSymbolicLink(link.toPath())) missing += "$name (未创建)";
        }
        return missing
    }

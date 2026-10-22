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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/*
 * 五大功能（网页 / PPT / 海报 / 表格 / 图片）按需下载的数据包。
 *
 * 为什么拆出来：这些功能的"知识"——模板骨架、PPT 蓝图、海报底图、表格体裁、图片预设
 * ——过去全部编在 APK 里，于是补一套版式就得发一个新版本。现在它们走和内核完全相同的
 * 路子：电脑上打包（tools/build-feature-pack.mjs），手机按需下载、校验、解包。
 *
 * 代码仍在 APK 里。Kotlin 不能安全热加载，所以包里只有数据与模板；这也决定了包能改
 * 什么、不能改什么。没装包时功能入口显示为"去下载"，而不是假装能用。
 */

/** 打开一个包内文件。路径与 APK assets 里的相对路径一致（如 `templates/web/official.html`）。 */
fun interface FeatureSource {
    fun open(path: String): InputStream?

    companion object {
        /** 没有安装任何包时的空来源；读不到就是 null，调用方自己决定怎么退。 */
        val none = FeatureSource { null }
    }
}

/** 电脑端 `/features` 里的一条。 */
data class FeaturePackInfo(
    val id: String,
    val name: String,
    val version: String,
    val minApp: String,
    val summary: String,
    val packageName: String,
    val sizeBytes: Long,
    val sha256: String,
    val present: Boolean,
) {
    companion object {
        fun from(json: JSONObject): FeaturePackInfo? {
            val id = json.optString("id").trim()
            if (id.isEmpty()) return null
            return FeaturePackInfo(
                id = id,
                name = json.optString("name").ifBlank { id },
                version = json.optString("version"),
                minApp = json.optString("minApp"),
                summary = json.optString("summary"),
                packageName = json.optString("packageName"),
                sizeBytes = json.optLong("sizeBytes", 0L),
                sha256 = json.optString("sha256"),
                present = json.optBoolean("present", false),
            )
        }

        fun list(text: String): List<FeaturePackInfo> {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            val packs = root.optJSONArray("packs") ?: JSONArray()
            return (0 until packs.length()).mapNotNull { index ->
                packs.optJSONObject(index)?.let { from(it) }
            }
        }
    }
}

/** `/feature/<id>` 里的清单：下载前要核对的大小与哈希。 */
internal data class FeaturePackManifest(
    val id: String,
    val version: String,
    val minApp: String,
    val packageName: String,
    val sizeBytes: Long,
    val sha256: String,
    val files: List<String>,
) {
    companion object {
        fun from(json: JSONObject): FeaturePackManifest? {
            val id = json.optString("id").trim()
            val packageName = json.optString("packageName").trim()
            if (id.isEmpty() || packageName.isEmpty()) return null
            val files = json.optJSONArray("files") ?: JSONArray()
            return FeaturePackManifest(
                id = id,
                version = json.optString("version"),
                minApp = json.optString("minApp"),
                packageName = packageName,
                sizeBytes = json.optLong("sizeBytes", 0L),
                sha256 = json.optString("sha256"),
                files = (0 until files.length()).mapNotNull {
                    files.optJSONObject(it)?.optString("path")?.takeIf(String::isNotBlank)
                },
            )
        }
    }
}

enum class FeatureStage { ABSENT, DOWNLOADING, VERIFYING, EXTRACTING, READY, FAILED }

/** 包内路径是否安全：只许相对路径，不许跳出包目录（zip-slip）。 */
internal fun featureEntryPathIsSafe(name: String): Boolean =
    name.isNotBlank() && !name.startsWith("/") && !name.contains("..") && !name.contains('\\')

/** `1.2.3-dev` 这种版本号比大小用；解析不出来就当 0，别把坏版本当成"新"。 */
internal fun featureVersionCode(version: String): Int {
    val parts = version.trim().substringBefore('-').split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return major * 1_000_000 + minor * 1_000 + patch
}


/**
 * 手机上此刻装了哪些包，供界面观察。
 *
 * 安装器写完磁盘就刷新这里，`@` 菜单订阅它——所以装完包回到会话，那一条就出现了，
 * 不需要重启 App，也不用把状态一层层往下传。
 */
internal object InstalledFeatures {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    fun refresh(store: FeatureStore) {
        _ids.value = store.installedIds()
    }
}

/** 一个功能包此刻的样子，界面直接照着画。 */
data class FeatureState(
    val id: String,
    val name: String,
    val stage: FeatureStage = FeatureStage.ABSENT,
    val progress: Float = 0f,
    val note: String = "",
    val available: FeaturePackInfo? = null,
    val installedVersion: String? = null,
    val installedBytes: Long = 0L,
) {
    val installed: Boolean get() = installedVersion != null
    val busy: Boolean
        get() = stage == FeatureStage.DOWNLOADING || stage == FeatureStage.VERIFYING ||
            stage == FeatureStage.EXTRACTING
}

/**
 * 已安装的包在磁盘上的样子。
 *
 * 一个包一个目录：`files/features/<id>/`，包内路径原样铺开，所以包的
 * `templates/web/x.html` 落在 `files/features/<id>/templates/web/x.html`——读取方拿到的
 * 路径和以前读 assets 时一模一样。安装是先解到 `<id>.tmp` 再整体改名，失败时删掉临时
 * 目录，不会留下半个包。
 */
internal class FeatureStore(private val appFilesDir: File) {

    fun root(): File = File(appFilesDir, "features")

    fun dirOf(id: String): File = File(root(), id)

    /** 已安装的包：id -> 磁盘目录。目录在就算装了。 */
    fun installed(): Map<String, File> {
        val root = root()
        if (!root.isDirectory) return emptyMap()
        return root.listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.endsWith(".tmp") }
            .associateBy { it.name }
    }

    fun installedIds(): Set<String> = installed().keys

    /** 装了的版本号，读包里的 feature.json；读不出来就当没版本。 */
    fun installedVersion(id: String): String? {
        val file = File(dirOf(id), "feature.json")
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()).optString("version").takeIf(String::isNotBlank) }
            .getOrNull()
    }

    fun sizeOf(id: String): Long {
        val dir = dirOf(id)
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 读一个包内文件。
     *
     * 只查已安装的包，不碰 APK：模板已经从 assets 里搬走了，回退到 assets 只会读到
     * 一个不存在的文件、让人误以为包还在。装了就一定读得到，没装就是 null。
     */
    fun open(path: String): InputStream? {
        if (path.isBlank()) return null
        for ((_, dir) in installed()) {
            val file = File(dir, path)
            if (file.isFile) return runCatching { file.inputStream() }.getOrNull()
        }
        return null
    }

    fun readText(path: String): String? =
        open(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }

    fun source(): FeatureSource = FeatureSource { path -> open(path) }

    fun remove(id: String): Boolean = dirOf(id).deleteRecursively()
}

/**
 * 下载 -> 校验 -> 解包。与内核安装器同一套规矩，只是形状不同：
 * 内核是 tar.gz（要保留符号链接），功能包是 zip（只有普通文件）。
 */
internal class FeatureInstaller(
    private val context: Context,
    private val http: OkHttpClient,
) {
    private val store = FeatureStore(context.filesDir)
    private val _state = MutableStateFlow<Map<String, FeatureState>>(emptyMap())
    val state: StateFlow<Map<String, FeatureState>> = _state.asStateFlow()

    /**
     * 走连接传输时用：开场、报进度、报失败。
     *
     * 之所以要这三个小方法：字节是从 WebSocket 上来的，进度由 [AgentClient] 收到
     * 分块时推给这里，安装器自己不碰网络。
     */
    fun markDownloading(info: FeaturePackInfo) {
        val prev = _state.value[info.id] ?: FeatureState(id = info.id, name = info.name)
        _state.value = _state.value + (info.id to prev.copy(
            stage = FeatureStage.DOWNLOADING,
            progress = 0f,
            note = "下载中…",
            available = info,
        ))
    }

    fun reportProgress(id: String, fraction: Float, note: String) {
        val prev = _state.value[id] ?: return
        _state.value = _state.value + (id to prev.copy(
            stage = FeatureStage.DOWNLOADING,
            progress = fraction.coerceIn(0f, 1f),
            note = note,
        ))
    }

    fun fail(id: String, message: String) {
        val prev = _state.value[id] ?: FeatureState(id = id, name = id)
        _state.value = _state.value + (id to prev.copy(
            stage = FeatureStage.FAILED,
            progress = 0f,
            note = message,
        ))
    }

    /** 磁盘上已经装了什么，先把状态摆对，再去问电脑有什么。 */
    fun refresh() {
        val installed = store.installed()
        val now = _state.value
        val merged = LinkedHashMap<String, FeatureState>()
        for ((id, _) in installed) {
            val prev = now[id]
            merged[id] = (prev ?: FeatureState(id = id, name = id)).copy(
                stage = if (prev?.busy == true) prev.stage else FeatureStage.READY,
                available = prev?.available,
                installedVersion = store.installedVersion(id),
                installedBytes = store.sizeOf(id),
                note = "已安装",
            )
        }
        // 正在装的那些不能因为一次 refresh 就消失。
        for ((id, prev) in now) {
            if (prev.busy && !merged.containsKey(id)) merged[id] = prev
        }
        _state.value = merged
        InstalledFeatures.refresh(store)
    }

    suspend fun loadIndex(base: String, token: String) = withContext(Dispatchers.IO) {
        refresh()
        val result = runCatching {
            val request = Request.Builder()
                .url("$base/features")
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("电脑端回答 HTTP ${response.code}：${text.take(160)}")
                FeaturePackInfo.list(text)
            }
        }
        val catalog = result.getOrElse { err ->
            Log.i(TAG, "features: 读不到清单：${err.message}")
            emptyList()
        }
        applyCatalog(catalog)
    }

    /**
     * 电脑把清单给我的时候用它（走 WebSocket 那条路，不用 HTTP）。
     *
     * 和老 agent 的 HTTP 清单走同一段合并逻辑：电脑上有什么就更新哪些；
     * 电脑上没有的保留已安装记录，不能因为一次失败就"卸载"。
     */
    fun applyCatalog(catalog: List<FeaturePackInfo>) {
        val merged = LinkedHashMap(_state.value)
        for (pack in catalog) {
            val prev = merged[pack.id]
            merged[pack.id] = (prev ?: FeatureState(id = pack.id, name = pack.name)).copy(
                name = pack.name,
                available = pack,
                installedVersion = prev?.installedVersion ?: store.installedVersion(pack.id),
                installedBytes = prev?.installedBytes ?: store.sizeOf(pack.id),
                note = when {
                    prev?.busy == true -> prev.note
                    prev?.installed == true -> "已安装"
                    !pack.present -> "电脑上还没构建这个包"
                    else -> "可以下载（${pack.version}，${Storage.format(pack.sizeBytes)}）"
                },
            )
        }
        _state.value = merged
    }

    suspend fun install(base: String, token: String, info: FeaturePackInfo) = withContext(Dispatchers.IO) {
        val current = _state.value[info.id]
        if (current?.busy == true) return@withContext

        fun put(stage: FeatureStage, progress: Float, note: String, error: String? = null) {
            val next = (_state.value[info.id] ?: FeatureState(id = info.id, name = info.name)).copy(
                stage = stage,
                progress = progress,
                note = if (error != null) error else note,
                available = info,
            )
            _state.value = _state.value + (info.id to next)
        }

        // 先拿清单：大小与哈希都在里面，后面每一步都靠它对账。
        val manifest = runCatching {
            val request = Request.Builder()
                .url("$base/feature/${info.id}")
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("电脑端回答 HTTP ${response.code}：${text.take(160)}")
                val json = JSONObject(text)
                if (!json.optBoolean("ok", false)) error(json.optString("message", "电脑端没有这个包"))
                if (!json.optBoolean("present", false)) error("清单有了，但电脑上找不到包文件（${info.packageName}）")
                FeaturePackManifest.from(json.optJSONObject("manifest") ?: JSONObject())
                    ?: error("清单缺字段")
            }
        }.getOrElse { err ->
            put(FeatureStage.FAILED, 0f, "", "读取清单失败：${err.message}")
            return@withContext
        }

        // 版本闸门：包要求比这台 App 更新的客户端时，装下去只会坏，不如说清楚。
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        if (manifest.minApp.isNotBlank() && featureVersionCode(appVersion) < featureVersionCode(manifest.minApp)) {
            put(
                FeatureStage.FAILED, 0f, "",
                "这个包要求 App ≥ ${manifest.minApp}，这台手机是 ${appVersion.ifBlank { "未知" }}，请先升级 App",
            )
            return@withContext
        }

        val zip = File(context.cacheDir, manifest.packageName)
        zip.delete()

        // 1. 下载，边下边算哈希：算的是真正收到的字节，不是"假定正确"的文件。
        put(FeatureStage.DOWNLOADING, 0f, "下载中…")
        val digest = MessageDigest.getInstance("SHA-256")
        val downloadError = runCatching {
            val request = Request.Builder()
                .url("$base/feature/${info.id}.pkg")
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
                val body = response.body ?: error("下载失败：没有响应体")
                val total = if (body.contentLength() > 0) body.contentLength() else manifest.sizeBytes
                body.byteStream().use { input ->
                    zip.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = input.read(buffer)
                        var done = 0L
                        while (read >= 0) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            put(
                                FeatureStage.DOWNLOADING,
                                if (total > 0) done.toFloat() / total else 0f,
                                "下载中 ${Storage.format(done)} / ${Storage.format(total)}",
                            )
                            read = input.read(buffer)
                        }
                    }
                }
            }
        }.exceptionOrNull()
        if (downloadError != null) {
            zip.delete()
            put(FeatureStage.FAILED, 0f, "", "下载失败：${downloadError.message}")
            return@withContext
        }

        // 2. 校验：大小和 sha256 都要对上。半个包不能算装好。
        put(FeatureStage.VERIFYING, 1f, "校验完整性…")
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (manifest.sha256.isNotBlank() && actual != manifest.sha256.lowercase()) {
            zip.delete()
            put(
                FeatureStage.VERIFYING, 0f, "",
                "校验不通过：下载到的文件与电脑上的不是同一个（期望 ${manifest.sha256.take(12)}…，实际 ${actual.take(12)}…）",
            )
            return@withContext
        }
        if (manifest.sizeBytes > 0 && zip.length() != manifest.sizeBytes) {
            val length = zip.length()
            zip.delete()
            put(FeatureStage.VERIFYING, 0f, "", "校验不通过：大小不符（期望 ${manifest.sizeBytes}，实际 $length）")
            return@withContext
        }

        // 3+4. 解包 → 落位。WebSocket 那条路也走这一段，别让两条路各写一份。
        place(info, manifest, zip) { stage, progress, note, error -> put(stage, progress, note, error) }
    }

    /**
     * 收到整包字节之后落盘——WebSocket 那条路用它。
     *
     * 字节从连接上来（中继也能到），所以不需要 HTTP 地址，也不需要先有直连。
     */
    suspend fun installBytes(
        info: FeaturePackInfo,
        manifest: FeaturePackManifest,
        bytes: ByteArray,
    ) = withContext(Dispatchers.IO) {
        // 这里不能再用「正在装就退出」那道闸：字节刚收完时状态还停在 DOWNLOADING，
        // 那道闸会把它当成"另一次安装"直接挡掉——包明明收齐了却永远不落地。
        // 真正的并发保护靠传输侧：同一个 id 的分块缓冲只有一份。
        fun put(stage: FeatureStage, progress: Float, note: String, error: String? = null) {
            val next = (_state.value[info.id] ?: FeatureState(id = info.id, name = info.name)).copy(
                stage = stage,
                progress = progress,
                note = if (error != null) error else note,
                available = info,
            )
            _state.value = _state.value + (info.id to next)
        }

        put(FeatureStage.VERIFYING, 1f, "校验完整性…")
        val zip = File(context.cacheDir, "${info.id}-${manifest.version}.pkg")
        runCatching { zip.writeBytes(bytes) }.onFailure {
            put(FeatureStage.FAILED, 0f, "", "写不了临时文件：${it.message}")
            return@withContext
        }
        place(info, manifest, zip) { stage, progress, note, error -> put(stage, progress, note, error) }
    }

    /**
     * 校验一份已经落盘的包体，然后解包、落位。
     *
     * HTTP 下载和 WebSocket 传输都收敛到这里：校验规则（哈希、大小、zip-slip、
     * manifest 里点名的文件）只有一份，两条路不会各自漂。
     */
    private fun place(
        info: FeaturePackInfo,
        manifest: FeaturePackManifest,
        zip: File,
        put: (FeatureStage, Float, String, String?) -> Unit,
    ) {
        put(FeatureStage.VERIFYING, 1f, "校验完整性…", null)
        if (manifest.sizeBytes > 0 && zip.length() != manifest.sizeBytes) {
            val length = zip.length()
            zip.delete()
            put(
                FeatureStage.FAILED, 0f, "",
                "校验不通过：大小不符（期望 ${manifest.sizeBytes}，实际 $length）",
            )
            return
        }

        // 解包到临时目录，成功后再整体改名——中途失败不会留下一个"看起来装了"的包。
        put(FeatureStage.EXTRACTING, 1f, "解包中…", null)
        store.root().mkdirs()
        val staging = File(store.root(), "${info.id}.tmp")
        staging.deleteRecursively()
        val unpackError = runCatching {
            staging.mkdirs()
            unpackZip(zip.inputStream(), staging)
            if (manifest.files.isNotEmpty()) {
                val missing = manifest.files.filterNot { File(staging, it).isFile }
                if (missing.isNotEmpty()) error("解包后少了 ${missing.size} 个文件，例如 ${missing.first()}")
            }
        }.exceptionOrNull()
        zip.delete()
        if (unpackError != null) {
            staging.deleteRecursively()
            put(FeatureStage.FAILED, 0f, "", "解包失败：${unpackError.message}")
            return
        }

        // 落位：先删旧版再改名。顺序反过来会让"装新版"变成"两个版本并存"。
        val target = store.dirOf(info.id)
        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            put(FeatureStage.FAILED, 0f, "", "解包完成，但装不进 ${target.absolutePath}")
            return
        }
        val previous = _state.value[info.id] ?: FeatureState(id = info.id, name = info.name)
        _state.value = _state.value + (info.id to previous.copy(
            stage = FeatureStage.READY,
            progress = 1f,
            note = "已安装 ${manifest.version}",
            available = info,
            installedVersion = store.installedVersion(info.id) ?: manifest.version,
            installedBytes = store.sizeOf(info.id),
        ))
        InstalledFeatures.refresh(store)
    }

    fun remove(id: String) {        store.remove(id)
        InstalledFeatures.refresh(store)
        val prev = _state.value[id]
        _state.value = _state.value + (id to (prev ?: FeatureState(id = id, name = id)).copy(
            stage = FeatureStage.ABSENT,
            progress = 0f,
            installedVersion = null,
            installedBytes = 0L,
            note = "已移除",
        ))
    }

    private companion object {
        const val TAG = "FeatureInstaller"
    }
}

/**
 * 把一个 zip 铺到 [dest]。安装器与 WebSocket 传输共用同一套规矩：不许 zip-slip。
 */
internal fun unpackZip(input: java.io.InputStream, dest: File) {
    java.util.zip.ZipInputStream(input.buffered()).use { entries ->
        while (true) {
            val entry = entries.nextEntry ?: break
            if (!featureEntryPathIsSafe(entry.name)) error("包里有非法路径：${entry.name}")
            val target = File(dest, entry.name)
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> entries.copyTo(out) }
            }
            entries.closeEntry()
        }
    }
}

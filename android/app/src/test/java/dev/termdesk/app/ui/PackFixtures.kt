package dev.termdesk.app.ui

import dev.termdesk.app.data.FeatureSource
import java.io.File

/**
 * 测试用的功能包取数器。
 *
 * 模板、蓝图、底图已经从 `app/src/main/assets` 搬到仓库根的 `packs/<id>/`（它们现在
 * 随包下载，不再编进 APK）。测试仍然想按 `templates/web/official.html` 这种路径读，
 * 所以这里把各包的内容合并到一个临时目录里——路径和以前的 assets 完全一致，测试断言
 * 不用改写法。
 *
 * 生产代码不走这条路：它读的是手机上已安装的包（见 FeatureStore）。
 */
internal object PackFixtures {

    private var merged: File? = null

    /** 仓库根：从工作目录往上找到同时有 `packs` 和 `android` 的那一层。 */
    fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "packs").isDirectory && File(dir, "android").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("找不到仓库根（工作目录：${File("").absolutePath}）")
    }

    /**
     * 一个把五个包摊平后的 assets 目录。
     *
     * 只建一次：整个 JVM 共用同一份，避免每个测试都复制几百 KB。
     */
    @Synchronized
    fun assetsDir(): File {
        merged?.let { if (it.isDirectory) return it }
        val out = File(System.getProperty("java.io.tmpdir"), "termdesk-pack-assets")
        out.deleteRecursively()
        out.mkdirs()
        val packs = File(repoRoot(), "packs")
        packs.listFiles().orEmpty().filter { it.isDirectory }.forEach { pack ->
            val templates = File(pack, "templates")
            if (templates.isDirectory) templates.copyRecursively(File(out, "templates"), overwrite = true)
            val data = File(pack, "data")
            if (data.isDirectory) data.copyRecursively(File(out, "data"), overwrite = true)
        }
        merged = out
        return out
    }

    /** 让模板读取器在测试里也能用：按包内相对路径打开。 */
    fun source(): FeatureSource = FeatureSource { path ->
        File(assetsDir(), path).takeIf { it.isFile }?.inputStream()
    }
}

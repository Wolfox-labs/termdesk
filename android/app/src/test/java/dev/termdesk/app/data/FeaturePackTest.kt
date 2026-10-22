package dev.termdesk.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 功能包里那些"错了不会报错、只会悄悄出事"的纯逻辑。
 *
 * 真正的下载、解包要 Android 的 filesDir/cacheDir，放在模拟器上验（见 P4 的端到端）；
 * 这里钉的是三件靠纯计算就能钉死的事：路径不能跳出包目录、版本能比大小、清单解析
 * 不会把坏 JSON 当成一个包。
 */
class FeaturePackTest {

    @Test
    fun `包内路径只许相对路径，跳出包目录的一律拒绝`() {
        assertTrue(featureEntryPathIsSafe("templates/web/official.html"))
        assertTrue(featureEntryPathIsSafe("feature.json"))

        assertFalse("绝对路径不行", featureEntryPathIsSafe("/etc/passwd"))
        assertFalse("向上跳不行", featureEntryPathIsSafe("../outside.txt"))
        assertFalse("藏在中间也不行", featureEntryPathIsSafe("templates/../../escape.txt"))
        assertFalse("反斜杠不认", featureEntryPathIsSafe("templates\\web\\x.html"))
        assertFalse("空路径不行", featureEntryPathIsSafe(""))
    }

    @Test
    fun `版本号比得出大小，解析不了的当零而不是当最新`() {
        assertTrue(featureVersionCode("1.1.0") > featureVersionCode("1.0.9"))
        assertTrue(featureVersionCode("1.0.10") > featureVersionCode("1.0.9"))
        assertEquals(featureVersionCode("1.0.1-dev.4"), featureVersionCode("1.0.1"))
        assertEquals(0, featureVersionCode("不是版本号"))
    }

    @Test
    fun `清单里缺 id 或包名的条目直接丢掉，不当成一个包`() {
        assertEquals(
            null,
            FeaturePackInfo.from(org.json.JSONObject().put("name", "没有 id")),
        )
        val ok = FeaturePackInfo.from(
            org.json.JSONObject()
                .put("id", "web").put("name", "网页生成").put("version", "1.0.0")
                .put("minApp", "1.0.1").put("packageName", "web-1.0.0.zip")
                .put("sizeBytes", 66999).put("sha256", "abc").put("present", true),
        )
        assertEquals("web", ok?.id)
        assertTrue(ok?.present == true)
    }

    @Test
    fun `电脑上的包列表解析得回来，坏 JSON 是空列表不是崩溃`() {
        val text = """
            {"ok":true,"packs":[
              {"id":"web","name":"网页生成","version":"1.0.0","present":true},
              {"id":"ppt","name":"PPT 生成","version":"1.0.0","present":false}
            ]}
        """.trimIndent()
        val packs = FeaturePackInfo.list(text)
        assertEquals(2, packs.size)
        assertEquals("web", packs[0].id)
        assertFalse("第二个包还没构建", packs[1].present)
        assertTrue(FeaturePackInfo.list("not json").isEmpty())
    }
}

package dev.termdesk.app.data

import dev.termdesk.app.ui.PackFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「图片处理」的选项表来自功能包。
 *
 * 处理本身是本地像素运算，包里只有"能选什么"；读不到包就不该冒出表单来。
 */
class ImagePresetsTest {

    private fun source(text: String?) = FeatureSource { path ->
        if (path == ImagePresets.PACK_PATH && text != null) text.byteInputStream() else null
    }

    @Test
    fun `正常清单读得出格式与默认值`() {
        val presets = ImagePresets.load(
            source("""{"formats":["JPG","PNG","WEBP"],"defaultQuality":72,"defaultFormat":"PNG","watermarkHint":"© 数智融媒"}"""),
        )!!
        assertEquals(listOf("JPG", "PNG", "WEBP"), presets.formats)
        assertEquals(72, presets.defaultQuality)
        assertEquals("PNG", presets.safeDefaultFormat)
        assertEquals("© 数智融媒", presets.watermarkHint)
    }

    @Test
    fun `默认格式写错了就退回第一条，不让单选一个都不选中`() {
        val presets = ImagePresets.load(source("""{"formats":["WEBP","PNG"],"defaultFormat":"TIFF"}"""))!!
        assertEquals("WEBP", presets.safeDefaultFormat)
    }

    @Test
    fun `质量越界会被夹回 0 到 100`() {
        assertEquals(100, ImagePresets.load(source("""{"formats":["JPG"],"defaultQuality":500}"""))!!.defaultQuality)
        assertEquals(0, ImagePresets.load(source("""{"formats":["JPG"],"defaultQuality":-20}"""))!!.defaultQuality)
    }

    @Test
    fun `没有包、坏 JSON、没有格式都是 null，不是空壳`() {
        assertNull(ImagePresets.load(source(null)))
        assertNull(ImagePresets.load(source("不是 JSON")))
        assertNull(ImagePresets.load(source("""{"formats":[]}""")))
    }

    @Test
    fun `真包里带着三种格式`() {
        val real = ImagePresets.load(PackFixtures.source())!!
        assertTrue("真包至少要有 JPG", real.formats.contains("JPG"))
        assertEquals(3, real.formats.size)
    }
}

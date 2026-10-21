package dev.termdesk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 页数分配的穷举验算。
 *
 * 用户的原话是「即使用户没有写到你学习过的 ppt 的页数，写了其他数字，你也要可以正确分配
 * 内容」。这句话只能靠穷举来保证：**3 到 80 页，每一套蓝图，每一个页数都算一遍**，
 * 逐条验不变量——不是抽查两个数字就算完。
 *
 * 这里钉的五条不变量：
 *  1. 要几页就是几页，`sum(pages) == 用户要的页数`；
 *  2. 没有任何一章被排成 0 页（0 页的章节是"幽灵章节"：占了大纲的位置却没有内容）；
 *  3. 封面、目录、结尾这类结构页永远各占 1 页（把它们撑成 2 页是典型的排版事故）；
 *  4. 章节条数不会超过页数（页数比章节还少时，必须先裁章节而不是排出半页的东西）；
 *  5. 章节名不重复（重复会让用户在大纲里看到两条一样的行）。
 */
class DeckAllocationSweepTest {

    private fun assetsDir(): File {
        val direct = File("src/main/assets")
        if (direct.isDirectory) return direct
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/assets")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("找不到 assets 目录")
    }

    /** 封面 / 目录 / 结尾这类"结构页"：永远只占一页。 */
    private val structural = setOf("封面", "目录", "结尾", "封底", "致谢")

    @Test
    fun `3 到 80 页 × 全部蓝图，每一条不变量都成立`() {
        val assets = assetsDir()
        assertTrue("蓝图不能只有一两套", DeckTemplates.all.size >= 8)

        var cases = 0
        for (deck in DeckTemplates.all) {
            val sections = DeckTemplates.parseSections(
                File(assets, deck.assetPath).readText(Charsets.UTF_8),
            )
            assertTrue(deck.id + " 解析不出章节", sections.isNotEmpty())
            assertTrue(
                deck.id + " 的第一章应当是封面",
                sections.first().name.contains("封面") || sections.first().name.contains("标题"),
            )

            for (pages in DeckTemplates.MIN_PAGES..DeckTemplates.MAX_PAGES) {
                cases += 1
                val rows = DeckTemplates.compose(sections, "校园低空巡检无人机", "", pages)
                val where = deck.id + " / " + pages + " 页"

                assertEquals(
                    where + "：要几页就得是几页",
                    pages,
                    rows.sumOf { it.pages },
                )
                assertTrue(
                    where + "：排出了空章节 " + rows.filter { it.pages < 1 }.map { it.section },
                    rows.all { it.pages >= 1 },
                )
                assertTrue(
                    where + "：章节条数(" + rows.size + ")不该超过页数(" + pages + ")",
                    rows.size <= pages,
                )
                assertEquals(
                    where + "：章节名有重复",
                    rows.size,
                    rows.map { it.section }.toSet().size,
                )
                for (row in rows) {
                    val isStructural = structural.any { name ->
                        row.section.contains(name) || name.contains(row.section)
                    }
                    if (isStructural) {
                        assertEquals(
                            where + "：" + row.section + " 这类结构页只该占 1 页",
                            1,
                            row.pages,
                        )
                    }
                }
                // 大纲的行号必须连续、不重叠，最后一行收在总页数上。
                var cursor = 1
                for (row in rows) {
                    assertEquals(where + "：大纲起页对不上", cursor, row.from)
                    assertEquals(where + "：大纲止页对不上", cursor + row.pages - 1, row.to)
                    cursor = row.to + 1
                }
                assertEquals(where + "：大纲没有收在最后一页", pages + 1, cursor)
            }
        }
        assertTrue("至少要算过几千种组合，才算穷举", cases >= 8 * 78)
    }

    @Test
    fun `用户写了个离谱的数字也不会崩，而是夹到能做的范围`() {
        assertEquals(DeckTemplates.MIN_PAGES, DeckTemplates.clampPages(0))
        assertEquals(DeckTemplates.MIN_PAGES, DeckTemplates.clampPages(1))
        assertEquals(DeckTemplates.MIN_PAGES, DeckTemplates.clampPages(-5))
        assertEquals(DeckTemplates.MAX_PAGES, DeckTemplates.clampPages(9999))
        assertEquals(37, DeckTemplates.clampPages(37))
    }
}

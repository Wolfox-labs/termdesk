package dev.termdesk.app.ui

import dev.termdesk.app.data.ChatEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 输入框上方那条任务带的数据部分。
 *
 * 记号是 agent 写的（`[x]` / `[~]` / `[ ]`），手机只负责解析和计数；取最后一条
 * 任务表，因为 todo 是覆盖语义，旧表已经不作数了。
 */
class PlanStripTest {

    private fun plan(text: String, seq: Int = 1) = ChatEvent(
        seq = seq,
        at = 0L,
        kind = "engine_plan",
        role = "engine",
        text = text,
        name = null,
        state = null,
        exitCode = null,
        sourceKind = null,
        streaming = false,
    )

    @Test
    fun `三种记号解析成三种状态`() {
        val rows = parsePlanLines("[x] 读代码\n[~] 改接口\n[ ] 补测试")
        assertEquals(3, rows.size)
        assertEquals(PlanRow.DONE, rows[0].state)
        assertEquals(PlanRow.DOING, rows[1].state)
        assertEquals(PlanRow.TODO, rows[2].state)
        assertEquals("改接口", rows[1].text)
    }

    @Test
    fun `带横线的旧写法仍然当待办`() {
        val rows = parsePlanLines("- 一件没做完的事\n* 另一件")
        assertEquals(2, rows.size)
        assertEquals(PlanRow.TODO, rows[0].state)
        assertEquals("一件没做完的事", rows[0].text)
    }

    @Test
    fun `计数分开算，正在做的那条单独取得出来`() {
        val plan = planOf(listOf(plan("[x] a\n[x] b\n[~] c\n[ ] d\n[ ] e")))
        assertEquals(2, plan!!.done)
        assertEquals(1, plan.doing)
        assertEquals(2, plan.todo)
        assertEquals("c", plan.current?.text)
    }

    @Test
    fun `取最后一条任务表，旧的不算`() {
        val plan = planOf(
            listOf(
                plan("[ ] 旧的", 1),
                plan("[ ] 旧的二", 2),
                plan("[x] 新的一\n[~] 新的二", 3),
            ),
        )
        assertEquals(2, plan!!.rows.size)
        assertEquals(PlanRow.DONE, plan.rows[0].state)
        assertEquals("新的二", plan.current?.text)
    }

    @Test
    fun `没有任务表就不画，空表也不画`() {
        assertNull("一条任务表都没有", planOf(emptyList()))
        assertNull("只有空白", planOf(listOf(plan("   \n  "))))
        // 有内容就要能取到，别把它当成空表。
        assertEquals(1, planOf(listOf(plan("[ ] 一条")))!!.rows.size)
    }
}

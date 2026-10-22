package dev.termdesk.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 「数媒风」调色板（Cyber）。
 *
 * 这套配色是 2026-10-21 那版被挂起的界面工作，`77ba826` 剔除了它；现在它以「配色方案」
 * 的身份回到设置里，供用户和「经典（Monokai）」二选一，而不是替换掉原来的主题。
 *
 * 命名沿用「深色为主、浅色为辅」的两套写法：无前缀 = 深色（默认主题），light* = 浅色主题。
 * 这些是给 [Theme.kt] 内部和需要精确取色的组件用的；普通组件请优先读
 * `MaterialTheme.colorScheme` 或 `Semantic.current`。
 */
object CyberPalette {

    // ---------------- 深色（默认）：赛博暗黑 ----------------

    // 五层底，从深到浅。注意：不使用纯黑 #000000，纯黑会让层次和阴影全部消失。
    val ink = Color(0xFF0B0E14)          // 页面最底：带一点蓝的深夜色
    val card = Color(0xFF12161F)         // 卡片 / 面板 / 输入区
    val overlay = Color(0xFF1A1F2B)      // 浮层 / 弹窗 / 消息气泡
    val border = Color(0xFF232A38)       // 分隔线 / 1px 描边
    val borderStrong = Color(0xFF2E3648) // 按下 / 悬停 / 高亮边框

    // 四档文字。对比度以 #0B0E14 为基准。
    val fg = Color(0xFFE6EDF7)           // 主文字（约 15:1）
    val fgMuted = Color(0xFF9BA7BD)      // 次要文字：说明、时间、路径（约 7.5:1）
    val fgDisabled = Color(0xFF5A6478)   // 禁用 / 占位（约 3:1，只用于装饰性小字）
    val onAccent = Color(0xFF0B0E14)     // 压在亮色按钮上的深色字

    // 强调色
    val cyan = Color(0xFF00E5FF)         // 主色 · 赛博青：主按钮、进度、链接、选中描边
    val cyanPressed = Color(0xFF00B8D4)  // 主色按下一档
    val cyanGlow = Color(0x5900E5FF)     // 外发光用（35% 透明青），只给主按钮
    val cyanWash = Color(0x1F00E5FF)     // 选中底（12% 透明青）
    val cyanDeep = Color(0xFF0A2830)     // 选中底的实色版，给不接受透明度的槽位用
    val magenta = Color(0xFFFF2E88)      // 副色 · 品红：录制中、删除确认
    val violet = Color(0xFF7C4DFF)       // 点缀 · 电紫：只进渐变和插画，不做选中色

    // 语义色
    val lime = Color(0xFF2BE08A)         // 成功 / 已保存 / 在线
    val amber = Color(0xFFFFB020)        // 警告 / 离线 / 有未保存改动
    val red = Color(0xFFFF4D4F)          // 错误 / 连接断开 / 删除
    val neutral = Color(0xFF8B93A7)      // 离线灰点、未选中图标

    // 终端 & 语法高亮（终端底再深一档，语法色沿用霓虹系）
    val terminalBg = Color(0xFF070A0F)
    val terminalFg = Color(0xFFD6E2F0)
    val syntaxComment = Color(0xFF5A6478)
    val syntaxString = Color(0xFF2BE08A)
    val syntaxKeyword = Color(0xFFFF2E88)
    val syntaxFunction = Color(0xFF00E5FF)
    val syntaxType = Color(0xFF7C4DFF)
    val syntaxNumber = Color(0xFFFFB020)

    /** 品牌渐变：启动页标识、发送按钮、空状态插画。135° 方向由使用方决定。 */
    val brandGradient = listOf(cyan, violet)

    // ---------------- 浅色：「日光工作台」 ----------------

    val lightInk = Color(0xFFF4F6FA)          // 页面底：冷调浅灰，比纯白耐看
    val lightCard = Color(0xFFFFFFFF)         // 卡片
    val lightOverlay = Color(0xFFFFFFFF)      // 浮层 / 弹窗
    val lightBorder = Color(0xFFD8DEE9)       // 描边
    val lightBorderStrong = Color(0xFFB9C3D3) // 按下 / 悬停

    val lightFg = Color(0xFF131720)           // 主文字
    val lightFgMuted = Color(0xFF5A6478)      // 次要文字
    val lightFgDisabled = Color(0xFFA3ADBE)   // 禁用 / 占位
    val lightNeutral = Color(0xFF78839A)      // 未选中图标

    // 【重点】亮青 #00E5FF 压在白底上几乎看不清，浅色主题必须加深。
    val lightPrimary = Color(0xFF007A8C)      // 加深青（白底上约 5.1:1）
    val lightPrimaryPressed = Color(0xFF00606F)
    val lightCyanDeep = Color(0xFFDFF3F7)     // 浅色主题的选中底
    val lightMagenta = Color(0xFFC81E6E)      // 加深品红
    val lightViolet = Color(0xFF5B34D6)       // 加深电紫

    val lightSuccess = Color(0xFF0E9F6E)
    val lightWarning = Color(0xFFB26A00)
    val lightDanger = Color(0xFFD92D20)

    val lightTerminalBg = Color(0xFF22262E)   // 浅色模式下终端仍是深色（保持终端观感）
    val lightTerminalFg = Color(0xFFD6E2F0)
}

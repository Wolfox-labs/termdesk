package dev.termdesk.app.ui

/**
 * 壳层的两套布局，在设置里选，不再按构建变体编译成两个 APK。
 *
 * 差别的全部（也就只该有这么多）：侧边面板从哪一侧滑进来、贴着哪一边。
 *
 *  - [Modern]  —— 面板从右侧滑入、贴右。这是主线一直在用的样子，也是默认。
 *  - [Classic] —— 面板从左侧滑入、贴左。2026-10-21 那版挂起的界面工作里
 *    `BuildConfig.UI_LAYOUT == "classic"` 就是这一套。
 *
 * 页面结构不跟着变：五个分区、底部导航、抽屉在两边布局里是同一份代码。这样将来改壳
 * 只需要改一处，不会出现「两个布局各自长歪」。
 */
enum class UiLayout {
    Modern,
    Classic,
    ;

    val label: String
        get() = when (this) {
            Modern -> "新版"
            Classic -> "经典"
        }

    /** 面板贴左还是贴右，直接给 Compose 用。 */
    val panelAtStart: Boolean
        get() = this == Classic
}

# 页面与录像索引

版本：1.0.1-dev.1；日期：2026-10-18。手机为 OPPO PDEM30 / Android 13 / 1080×2376 / 1.35 倍字号；桌面为本轮新构建的原生 Windows app image。无真实模型请求，回复由固定助手生成；文件与 shell 操作实际执行。

| 页面 | 证据 |
|---|---|
| 手机工作台 | [phone-home.png](phone-home.png) |
| 会话输入态 | [phone-chat.png](phone-chat.png) |
| 文件列表 | [phone-files.png](phone-files.png) |
| 文本编辑 | [phone-editor.png](phone-editor.png) |
| 电脑终端 | [phone-terminal.png](phone-terminal.png) |
| 设置 | [phone-settings.png](phone-settings.png) |
| 桌面概览 | [desktop-overview.png](desktop-overview.png) |
| 设备管理 | [desktop-devices.png](desktop-devices.png) |
| 助手管理 | [desktop-kernels.png](desktop-kernels.png) |
| 会话索引 | [desktop-sessions.png](desktop-sessions.png) |
| 会话只读详情 | [desktop-session-detail.png](desktop-session-detail.png) |

[verification.json](verification.json) 保存脱敏的手机流程结果和截图时间。桌面画面使用单独的隔离协议夹具，旧令牌记录不表示真实手机在线；没有点击危险操作或改变使用者真实部署。

本机 output/report/oppo-walkthrough-01.mp4 为 119.19 秒的直接设备录屏，无音频、无剪辑、无截图拼接。通过官方 scrcpy 5.0.1 的设备编码与本机封装生成，参考[录屏说明](https://github.com/Genymobile/scrcpy/blob/master/doc/recording.md)。自动化源码见 [demo-walkthrough.mjs](../../../pc-agent/tools/demo-walkthrough.mjs)。

手机原有偏好已经备份并恢复，恢复后哈希一致；测试服务和 ADB reverse 已清理，未卸载或清除应用数据。视频和 PDF 是本机导出产物，不混入源码目录；原始截图保留在本目录。

PDF 导出在 output/pdf/TermDesk-项目说明书-2026-10-18.pdf，可编辑源为[项目说明书](../../项目说明书.md)。后续复跑默认写入 output/demo，避免覆盖这次已提交的原始截图；脚本的前置检查和通用输出命名在本轮录制后完善，未为此重复操作手机。

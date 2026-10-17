# 本地安卓 UI 测试环境

> 2026-10-17，经使用者明确许可安装。用于没有真机在手时的移动端设计回归。

## 已布置的环境

| 项目 | 配置 |
|---|---|
| 运行方式 | Windows 官方 Android Emulator，不依赖 WSL/Docker 容器 |
| 模拟器 | 37.2.12.0 |
| 系统镜像 | Android 15 / API 35，Google APIs x86_64，revision 9 |
| AVD 名称 | `termdesk-ui-api35` |
| 固定调试目标 | `emulator-5556`，避免误操作无线连接的手机 |
| 资源 | 4 核、4 GiB RAM、6 GiB 数据分区；安装与快照预留约 20 GB 预算 |
| 基础屏幕 | 1200×2608 / density 480，约 400dp 宽 |
| 字号 | 1.45 倍，与本轮 Redmi 测试设置一致 |
| 窄屏检查 | 临时 density 560，约 343dp；验证后恢复 |
| 时区 | Asia/Shanghai |
| 启动方式 | 默认后台无窗口，需要时通过 ADB 检查和截图 |

本机 SDK 使用 E 盘既有 Android SDK，AVD 放在同一 Android 目录下的 `Avd`。没有安装完整 Android Studio，没有改动 WSL/Docker 配置，没有自动重启电脑。

虽然 Windows 可选功能清单曾显示 HypervisorPlatform 为未启用，模拟器自己的 `-accel-check` 实际返回 `WHPX(10.0.26200) is installed and usable`。以实际运行结果为准，本轮无需再启用功能或触发管理员提权。

## 日常启动、安装与关闭

在当前项目根目录，以本机实际 SDK 目录设置 `ANDROID_HOME`，随后运行：

```powershell
.\tools\android-ui.ps1 -Action Start
.\tools\android-ui.ps1 -Action Status
.\tools\android-ui.ps1 -Action Install -Apk android/app/build/outputs/apk/debug/app-debug.apk
.\tools\android-ui.ps1 -Action Stop
```

也可显式传 `-SdkRoot` 和 `-AvdHome`。脚本使用独立端口，安装与停止前核对 AVD 名称；不会自动选取第一台 ADB 设备，不会卸载应用或清空数据。启动后需等 `sys.boot_completed` 为 1 再安装。

开发界面可以通过独立电脑代理和 ADB reverse 联调。合成助手登记见[运行与开发](运行与开发.md)。不要把测试代理的配对凭据提交到仓库。

## 这套环境验证什么

- 工作台和页面布局，400dp / 343dp 与 1.45 倍字体。
- 深浅主题、系统栏对比、输入法与底部导航。
- 会话文档式正文、长回复、草稿、返回层级。
- 文件确认、提示位置和终端关闭/再次新建的交互。
- 使用假助手和合成事件的协议 UI 路径。

本轮具体结果和截图见[演示版验收](演示版验收-2026-10-17.md)。

## 不能替代的真机验证

这是 x86_64 Android 15，不是 Redmi 的 ARM64 Android 17。它不能作为现有 ARM64 Termux 载荷可执行性的验收依据，也不能复现小米/OPPO 的省电策略、真实无线链路和系统定制行为。

模拟器中的会话验证连接电脑上的合成助手，明确显示“无模型”。它不证明手机沙盒中的真实 AI 助手已经安装、登录或可用。

当前还没有完整的 Compose 仪器测试集；本轮以实际 App 的 ADB UI 操作、结构树、截图及协议检查验收。UI 树生成失败时不复用旧树作为新证据。

官方参考：[运行模拟器](https://developer.android.com/studio/run/emulator)、[硬件加速和架构限制](https://developer.android.com/studio/run/emulator-acceleration)。

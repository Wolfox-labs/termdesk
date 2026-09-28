# TermDesk

手机端（Android）远程工作台：把电脑当成后台，在手机上派任务、看状态、收文件。

- **P0（已完成）**：连接链路 + 实时主机状态
- **P1（已完成）**：进程与服务管理（含保护名单）
- **P2（已完成）**：文件管理（浏览/上传/下载/编辑）
- **P3（已完成）**：终端（持久 PowerShell 会话）
- **P4**：AI 双引擎（DSH ⇄ Codex）
- **P5**：通知与打磨

## 目录

```
termdesk/
├─ pc-agent/        电脑端代理（Node，无原生依赖）
│  ├─ src/server.js     WebSocket 服务 + 健康检查 + 传输端点
│  ├─ src/protocol.js   线协议（v1）
│  ├─ src/auth.js       配对令牌
│  ├─ src/system.js     主机指标采集
│  ├─ src/inventory.js  进程/服务清单（带缓存）
│  ├─ src/actions.js    结束进程 / 服务启停（含保护名单）
│  ├─ src/files.js      文件操作（根目录隔离）
│  ├─ src/transfer.js   HTTP 流式上传下载
│  ├─ src/terminal.js   持久 shell 会话
│  └─ tools/            自检脚本
└─ android/         手机端 App（Kotlin + Jetpack Compose）
```

## 运行电脑端代理

```bash
cd pc-agent
npm install
node src/server.js                        # 端口 7420，终端关闭
node src/server.js --enable-shell         # 开启终端（可执行任意命令）
node src/server.js --show-token           # 只打印配对令牌
```

健康检查：<http://127.0.0.1:7420/healthz>

配对令牌存在 `~/.termdesk/token`（首次运行自动生成，权限 600）。

**终端默认关闭**：任意命令执行是这个代理最危险的能力，必须显式用
`--enable-shell`（或 `TERMDESK_ENABLE_SHELL=1`）开启。未开启时手机端会显示
明确提示，而不是静默失败。

**文件访问范围**：默认允许用户主目录与 C/D/E 盘根目录，可用
`TERMDESK_ROOTS="E:\;D:\work"` 收窄。所有路径都会解析并从符号链接层面
校验，越界一律拒绝。

## 自检

代理启动后（终端用例需要 `--enable-shell`）：

```bash
node tools/smoke.js            # P0 鉴权 + 状态推送
node tools/auth-negative.js    # P0 错误令牌 / 跳过鉴权 / 畸形帧
node tools/encoding-check.js   # 中文编码链路
node tools/inventory-test.js   # P1 进程/服务 + 保护名单
node tools/files-test.js       # P2 文件 + 路径逃逸防护
node tools/terminal-test.js    # P3 终端（含 shell 关闭时的拒绝路径）
node tools/p2-e2e.js           # P2 真实链路
node tools/p3-e2e.js           # P3 真实链路
```

`tools/pty-probe*.js` 是设计终端时留下的对照实验，记录了关键结论：
点源 `. { }` 才能让变量和函数跨命令保留，调用运算符 `& { }` 不行（2/5 vs 5/5）。

## 构建 Android App

```bash
cd android
./gradlew :app:assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

安装（USB 或无线调试）：

```bash
E:\Android\Sdk\platform-tools\adb.exe install -r <apk 路径>
```

## 环境（本机已配置）

| 项 | 位置 |
|---|---|
| Android SDK | `E:\Android\Sdk` |
| Gradle 8.13 | `E:\Android\tools\gradle-8.13` |
| JDK 21 | `C:\Program Files\Java\jdk-21` |
| `ANDROID_HOME` | 用户级变量，指向 `E:\Android\Sdk` |

Gradle 官方分发源在本网络不可达，`settings.gradle.kts` 与 wrapper 均指向
可达的镜像。

## 网络

- **主链路**：Tailscale（`ws://192.168.1.10:7420`），不开公网端口
- **局域网**：同网段可用 `ws://192.168.1.8:7420`
- **备用**：VPS + Cloudflare 域名（待配置）

代理默认 `usesCleartextTraffic="true"`，因此当前用 `ws://`；接 Tailscale HTTPS 后可切 `wss://`。

## 协议 v1

一帧一个 JSON 对象，均带 `type` 字段。首个帧必须是 `auth`，否则以 `4401` 关闭。

| 方向 | 帧 |
|---|---|
| C→S | `auth` · `status.get` · `status.subscribe` · `status.unsubscribe` |
| C→S | `procs.list` · `procs.kill` · `services.list` · `services.action` |
| C→S | `fs.list` · `fs.read` · `fs.write` · `fs.mkdir` · `fs.delete` · `fs.rename` · `fs.roots` |
| C→S | `term.open` · `term.run` · `term.interrupt` · `term.close` · `term.list` · `ping` |
| S→C | `auth.ok` · `auth.fail` · `hello` · `status` · `procs` · `services` |
| S→C | `fs.listing` · `fs.file` · `fs.written` · `fs.roots` |
| S→C | `term.opened` · `term.output` · `term.exit` · `term.list` |
| S→C | `action.result` · `error` · `pong` |

文件传输走同端口的 HTTP（`/upload`、`/download`），用 `Authorization: Bearer`
携带同一令牌：整文件走 JSON base64 会让体积膨胀三分之一并把整个文件读进内存。

## 已知取舍

- **终端无真实 PTY**：Windows 上没有原生模块就拿不到 PTY，终端是基于管道的
  JSON-lines 会话。全屏交互式程序（vim、top 的实时刷新）**不能正常工作**，
  但常规命令、目录操作、查看输出都没问题。
- **中断即重启 shell**：`Ctrl+C` 通过重建 PowerShell 进程实现，因此中断会
  丢失当前会话内的变量。这是为可预测性做的权衡（无法从外部打断阻塞的管道）。
- **左右分栏在手机上不成立**：400dp 宽 + 145% 字体缩放下，固定三栏会把主区域
  挤到 60dp。因此改成「图标导航轨 + 全宽主区 + 滑出式状态面板」。


## 目录

```
termdesk/
├─ pc-agent/        电脑端代理（Node，无原生依赖）
│  ├─ src/server.js     WebSocket 服务 + 健康检查
│  ├─ src/protocol.js   线协议（v1）
│  ├─ src/auth.js       配对令牌
│  ├─ src/system.js     主机指标采集
│  └─ tools/            自检脚本
└─ android/         手机端 App（Kotlin + Jetpack Compose）
```

## 运行电脑端代理

```bash
cd pc-agent
npm install
node src/server.js              # 监听 0.0.0.0:7420
node src/server.js --show-token # 只打印配对令牌
```

健康检查：<http://127.0.0.1:7420/healthz>

配对令牌存在 `~/.termdesk/token`（首次运行自动生成，权限 600）。

## 自检

代理启动后：

```bash
node tools/smoke.js          # 鉴权 + 状态推送（正常路径）
node tools/auth-negative.js  # 错误令牌 / 跳过鉴权 / 畸形帧（拒绝路径）
```

## 构建 Android App

```bash
cd android
./gradlew :app:assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

安装到手机（需开启 USB 调试）：

```bash
E:\Android\Sdk\platform-tools\adb.exe install -r <apk 路径>
```

## 环境（本机已配置）

| 项 | 位置 |
|---|---|
| Android SDK | `E:\Android\Sdk` |
| Gradle 8.13 | `E:\Android\tools\gradle-8.13` |
| JDK 21 | `C:\Program Files\Java\jdk-21` |
| `ANDROID_HOME` | 用户级变量，指向 `E:\Android\Sdk` |

## 网络

- **主链路**：Tailscale（`ws://192.168.1.10:7420`），不开公网端口
- **备用**：VPS + Cloudflare 域名（待配置，P4 之后）

代理默认 `usesCleartextTraffic="true"`，因此当前用 `ws://`；接 Tailscale HTTPS 后可切 `wss://`。

## 协议 v1

一帧一个 JSON 对象，均带 `type` 字段。

客户端 → 服务端：`auth` · `status.get` · `status.subscribe` · `status.unsubscribe` · `ping`

服务端 → 客户端：`auth.ok` · `auth.fail` · `hello` · `status` · `error` · `pong`

首个帧必须是 `auth`，否则以 `4401` 关闭。

# TermDesk

手机端（Android）远程工作台：把电脑当成后台，在手机上派任务、看状态、收文件。

> 需求与功能单、缺陷详述、交给下一位开发者的交接说明见 **[REQUIREMENTS.md](REQUIREMENTS.md)**。

---

## 这是什么

人在外面、只有一部手机时，继续使用家里那台 Windows 电脑办公。不是远程桌面——手机屏幕小、
流量贵——而是「把电脑当后台」：

- **对话**：和电脑上的 AI 助手连续对话（原生会话，不是拼接历史）
- **看状态**：CPU / 内存 / 磁盘 / 进程 / 服务
- **收发文件**：浏览、上传、下载、就地编辑
- **终端**：持久 PowerShell 会话
- **看网页 / Word / PDF**：靠更上层的 DSH Web 通道

## 架构

```
┌──────────────┐        ┌─────────────────────────────┐
│  Android App │        │  Windows PC                 │
│  (Compose)   │◄──ws───►│  pc-agent (Node) :7420      │
└──────────────┘        │    ├─ 主机状态 / 进程 / 服务 │
                        │    ├─ 文件系统 + HTTP 传输   │
                        │    ├─ 持久 PowerShell 会话  │
                        │    └─ 每对话一个 DSH 运行时 │
┌──────────────┐        │                             │
│  手机浏览器  │◄──http─►│  DSH Web :3080              │
└──────────────┘        └─────────────────────────────┘
        ▲                            ▲
        └──── Cloudflare Tunnel ─────┘
             (PC 主动出站，零入站端口)
```

### 为什么对话走 DSH SDK 而不是 headless

| 方案 | 会话延续 | 进度事件 |
|---|---|---|
| `dsh --profile headless "<task>"` | ❌ 一次性、无记忆 | ❌ |
| `dsh --profile sdk`（stdio JSON-RPC） | ✅ 同 sessionId 真延续 | ✅ 流式 |

`chat.js` 为每个对话维持一个常驻 SDK 运行时进程，因此跟进追问是**真的续上同一会话**，
而不是把历史拼进新进程的提示词里。

## 目录

```
termdesk/
├─ pc-agent/                电脑端代理（Node，无原生依赖）
│  ├─ src/server.js           WebSocket 服务 + 连接生命周期
│  ├─ src/handlers.js         鉴权后帧分发
│  ├─ src/protocol.js         线协议 v1
│  ├─ src/auth.js             配对令牌
│  ├─ src/system.js           主机指标
│  ├─ src/inventory.js        进程 / 服务清单（带缓存）
│  ├─ src/actions.js          结束进程 / 服务启停（保护名单）
│  ├─ src/files.js            文件操作（根目录隔离）
│  ├─ src/transfer.js         HTTP 流式上传下载
│  ├─ src/terminal.js         持久 shell 会话
│  ├─ src/engines.js          Codex / DSH 一次性任务
│  ├─ src/chat.js             DSH SDK 原生对话
│  ├─ src/sessions.js         磁盘历史会话（只读，双引擎）
│  ├─ src/codexconfig.js      Codex provider 配置读写与回滚
│  └─ tools/                  自检与诊断脚本（probes/ 为历史对照实验）
├─ android/                 手机端 App（Kotlin + Jetpack Compose）
├─ REQUIREMENTS.md          需求与功能单 / 交接说明
└─ tools/                   cloudflared 二进制（不纳入版本控制）
```

## 运行电脑端代理

```bash
cd pc-agent
npm install
node src/server.js                        # 端口 7420，终端关闭
node src/server.js --enable-shell         # 开启终端（可执行任意命令）
node src/server.js --show-token           # 只打印配对令牌
node src/server.js --port 7420 --host 0.0.0.0
```

健康检查：<http://127.0.0.1:7420/healthz>

配对令牌存在 `~/.termdesk/token`（首次运行自动生成，权限 600）。

**终端默认关闭**：任意命令执行是这个代理最危险的能力，必须显式用
`--enable-shell`（或 `TERMDESK_ENABLE_SHELL=1`）开启。未开启时手机端会给出明确
提示，而不是静默失败。

**文件访问范围**：默认允许用户主目录与 C/D/E 盘根目录，可用
`TERMDESK_ROOTS="E:\;D:\work"` 收窄。所有路径都会解析并从符号链接层面校验，
越界一律拒绝。

## 自检

代理启动后（终端类用例需 `--enable-shell`）：

```bash
node tools/smoke.js             # P0 鉴权 + 状态推送
node tools/auth-negative.js     # 错误令牌 / 跳过鉴权 / 畸形帧
node tools/encoding-check.js    # 中文编码链路
node tools/inventory-test.js    # P1 进程 / 服务 + 保护名单
node tools/files-test.js        # P2 文件 + 路径逃逸防护
node tools/terminal-test.js     # P3 终端（含 shell 关闭时的拒绝路径）
node tools/codex-config-test.js # Codex provider 配置
node tools/p2-e2e.js            # P2 真实链路
node tools/p3-e2e.js            # P3 真实链路
node tools/sessions-test.js     # 会话解析（双引擎）
node tools/sessions-live.js     # 会话真实读取
node tools/engines-test.js      # Codex / DSH 一次性任务
node tools/chat-e2e.js          # 原生对话：流式、去重、会话延续
```

公网通道的验证（需要隧道在跑）：

```bash
node tools/tunnel-e2e.js        # 公网 wss 全链路 + 两轮对话延续
node tools/dsh-web-check.js     # DSH Web 过隧道可用性
node tools/mux-probe.js         # DSH Remote mux 线协议
```

### 诊断脚本

`tools/` 里另有一批排查用的只读脚本，记录了几个非显而易见的结论：

- `pty-probe*.js` — 设计终端时留下的对照实验：点源 `. { }` 才能让变量和函数跨命令
  保留，调用运算符 `& { }` 不行（2/5 vs 5/5）。
- `sidebar-*.js` — 手机宽度下用真实浏览器（CDP）取证侧栏布局问题，见
  [REQUIREMENTS.md](REQUIREMENTS.md) 第四节。
- `sdk-probe.js` / `sdk-dump.js` — 验证并记录 DSH SDK 的事件载荷结构。
- `tunnel-diag.js` — 逐帧打印隧道上的收发时序。

## 构建 Android App

```bash
cd android
./gradlew :app:assembleDebug          # 或 gradle -p android :app:assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

安装（USB 或无线调试）：

```bash
adb install -r -t android/app/build/outputs/apk/debug/app-debug.apk
```

### 环境（本机已配置）

| 项 | 位置 |
|---|---|
| Android SDK | `E:\Android\Sdk` |
| Gradle 8.13 | `E:\Android\tools\gradle-8.13` |
| JDK 21 | `C:\Program Files\Java\jdk-21` |
| `ANDROID_HOME` | 用户级变量，指向 `E:\Android\Sdk` |

## 网络

本机部署同时提供两条通道：

| 通道 | 地址 | 说明 |
|---|---|---|
| **公网（主）** | `wss://term.<your-domain>` | Cloudflare Tunnel，PC 主动出站，零入站端口 |
| **公网（浏览器）** | `https://dsh.<your-domain>` | DSH Web，手机浏览器直接用 |
| Tailscale | `ws://<tailscale-ip>:7420` | 备用；部分网络下打洞失败，会绕中继 |
| 局域网 | `ws://<lan-ip>:7420` | 同网段 |

**为什么以 Cloudflare 为主**：本校园网下 Tailscale 打洞失败，流量绕道
纽伦堡 / 孟买中继（144–217 ms）；而 Cloudflare 隧道口 7844 实测全通。

隧道配置在 `~/.cloudflared/termdesk-config.yml`（不属于本仓库；内含本机路径与
隧道凭据）。`tools/cloudflared-windows-amd64.exe` 是下载的二进制，**未纳入版本
控制**——需要时按 <https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/>
自行获取。

### 让 DSH Web 接受隧道域名

DSH 的 `/api` 有一道 Host/Origin 信任围栏：只接受 loopback、绑定派生的局域网 IP、
或显式声明的 `trustedHosts`。隧道域名不属于任何一类，会得到 403——页面能开但连不
上后端。桌面 App 用固定参数启动 dsh，无法传 `--trusted-host`，因此这条配置写在
`~/.dsh/cordis.patch.yml`（home 层，桌面 App 永不覆盖该文件）。

## 协议 v1

一帧一个 JSON 对象，均带 `type` 字段。首帧必须是 `auth`，否则以 `4401` 关闭。
完整帧表见 [REQUIREMENTS.md](REQUIREMENTS.md) 第 5.3 节。

> **坑**：`encodeFrame` 会把 payload 展开在 `type` 之后，因此 payload 里**不能再有
> `type` 字段**。曾因引擎事件用 `type` 覆盖线帧类型，导致 `ai.finished` 永远收不到
> 且没有任何报错。现引擎事件统一用 `event` 字段，并有回归断言守着。

文件传输走同端口的 HTTP（`/upload`、`/download`），用 `Authorization: Bearer`
携带同一令牌：整文件走 JSON base64 会让体积膨胀三分之一并把整个文件读进内存。

## 已知取舍

- **终端无真实 PTY**：Windows 上没有原生模块就拿不到 PTY，终端是基于管道的
  JSON-lines 会话。全屏交互式程序（vim、top 的实时刷新）**不能正常工作**，
  但常规命令、目录操作、查看输出都没问题。
- **中断即重启 shell**：`Ctrl+C` 通过重建 PowerShell 进程实现，因此中断会丢失当前
  会话内的变量。这是为可预测性做的权衡（无法从外部打断阻塞的管道）。
- **无单轮取消**：DSH SDK 协议没有 cancel 方法；`chat.cancel` 实际是终止该对话的
  运行时（会话随之结束）。
- **公网暴露面**：agent 具备任意命令执行与全盘文件权限。除配对 token 外，可设
  `TERMDESK_ACCESS_KEY` 作第二因子（HTTP 头 `X-TermDesk-Key` / WS `?access=`）；
  生产建议再叠 Cloudflare Access。详见 [REQUIREMENTS.md](REQUIREMENTS.md) §4.1。
- **大文件**：Cloudflare 免费版单请求体上限 100 MB。文件传输已支持分片会话
  （`POST /upload/session` → `PUT …&index=N` → `POST …/commit`），单请求体
  4–8 MB，手机端 >32 MB 自动走分片，可断点续传（`GET /upload/session`）。
- **左右分栏在手机上不成立**：400dp 宽 + 145% 字体缩放下，固定三栏会把主区域挤到
  60dp。因此改成「图标导航轨 + 全宽主区 + 滑出式面板」。
- **UI 不做二次概括**：引擎自己的输出（推理、工具调用、注入上下文、压缩摘要）
  原样呈现并加标签，不生成"结果卡片"。

## License

MIT，见 [LICENSE](./LICENSE)。

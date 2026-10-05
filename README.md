# TermDesk

手机端（Android）远程工作台：把电脑当成后台，在手机上派任务、看状态、收文件。

> 需求与功能单、缺陷详述、交给下一位开发者的交接说明见 **[REQUIREMENTS.md](REQUIREMENTS.md)**。

---

## 这是什么

人在外面、只有一部手机时，继续使用家里那台 Windows 电脑办公。不是远程桌面——手机屏幕小、
流量贵——而是「把电脑当后台」：

- **对话**：和电脑上的 AI 助手连续对话（统一对话管线，内核可选 codex / dsh，原生会话不是拼接历史）
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
                        │    └─ 统一对话（内核可选）   │
┌──────────────┐        │         codex / dsh         │
│  手机浏览器  │◄──http─►│  DSH Web :3080              │
└──────────────┘        └─────────────────────────────┘
        ▲                            ▲
        └──── Cloudflare Tunnel ─────┘
             (PC 主动出站，零入站端口)
```

### 一条对话管线，内核可选

产品上只有 `chat.*` 一条对话管线；建会话时用 `engine` 选内核。旧的 `ai.*`
「任务」面已 **deprecated**，协议保留兼容，新能力一律进 `chat.*`。

| 内核 | 机制 | 会话延续 | 进度事件 |
|---|---|---|---|
| `engine=codex` | 官方 `codex app-server`（stdio JSON-RPC）：thread 列表/读取/恢复/打断都由内核提供 | ✅ 原生 thread | ✅ 通知流 |
| `engine=opencode` / `mimo` | 共享的 **ACP 适配器**（`kernels/acp.js`）：`session/new` / `list` / `load` / `prompt` | ✅ 内核自己的 session | ✅ `session/update` 流 |
| `engine=dsh`（默认） | 每对话一个常驻 `dsh --profile sdk` 运行时（stdio JSON-RPC） | ✅ 同 sessionId 真延续 | ✅ 流式 |

**内核的 tier 决定了手机能不能选它**（事实来源：`pc-agent/src/kernels/registry.js`）：

| tier | 含义 | 例子 |
|---|---|---|
| `native` | 为单个产品写的适配器 | Codex、DSH |
| `acp` | 共享 ACP 适配器驱动，**加一条数据就能接一个新内核** | OpenCode、MiMo Code |
| `shim` | Claude Code 形状的 CLI，契约已记录、尚缺实测 | QoderWork CN、Command Code |
| `unsupported` | 已安装但没有可编程接口 | Antigravity、豆包 |

`chat.create` 可带 `engine` / `provider` / `model`；chat 列表与详情均带 `engine`；
`chat.config` 可在会话内改模型与思考强度。对话事件映射到同一套种类
（`message` / `reasoning` / `tool` / `turn` 等），手机一套渲染。

**为什么用 ACP**：会话列表、历史回放和继续对话都由内核自己提供，所以"打开历史"
和"新建对话"是同一条路径、同一个 session id——TermDesk 不再自己重建会话管理。
本地内核只要按同样方式暴露（ACP，或 `TERMDESK_ACP_KERNELS` 环境变量登记），
就自动出现在手机的选单里。

`chat.js` 为每个 dsh 对话维持一个常驻 SDK 运行时进程，因此跟进追问是**真的续上
同一会话**，而不是把历史拼进新进程的提示词里；codex 对话则把 `thread_id` 交给
Codex 自己的 resume 机制，同样不重喂历史。

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
│  ├─ src/engines.js          codex/dsh 运行助手 + 已废弃的 ai.* 任务面
│  ├─ src/chat.js             统一对话管线（每条内核一条适配路径）
│  ├─ src/sessions.js         磁盘历史会话（只读，DSH）
│  ├─ src/codexconfig.js      Codex provider 配置读写与回滚
│  ├─ src/kernels/registry.js 内核注册表：唯一的事实来源（tier / 路径 / 规格）
│  ├─ src/kernels/acp.js      ACP 适配器：一个适配器点亮所有 ACP 内核
│  ├─ src/kernels/codex.js    Codex app-server 适配器
│  ├─ src/tunnel.js           cloudflared（命名隧道 / 配置文件 / 临时地址）
│  └─ tools/                  自检与诊断脚本
├─ android/                 手机端 App（Kotlin + Jetpack Compose）
├─ desktop/                 桌面版窗口（Compose Desktop，复用手机版主题）
├─ TermDesk.bat             手工启动（见下）
├─ REQUIREMENTS.md          需求与功能单 / 交接说明
└─ tools/                   cloudflared 二进制（不纳入版本控制）
```

## 运行电脑端代理

**手工启动（推荐）**：双击仓库根目录的 `TermDesk.bat`。它等价于

```bash
node pc-agent/src/server.js --host 0.0.0.0 --enable-shell --tunnel
```

启动报告按顺序打印：**头部**（监听地址 / shell 状态 / 局域网地址 / 配对页与安装页）、
**内核表**（每个内核一行，带 tier 与真实原因；`TERMDESK_KERNELS_PROBE=1` 时还会做一次
ACP 握手，写出内核自己声明的能力）、**公网**（地址 + 形态 + 一次公网 `/healthz`
自检，通过后才打印配对二维码）。

```bash
cd pc-agent
npm install
node src/server.js                        # 端口 7420，终端关闭
node src/server.js --enable-shell         # 开启终端（可执行任意命令）
node src/server.js --show-token           # 只打印配对令牌
node src/server.js --port 7420 --host 0.0.0.0
node src/server.js --tunnel --enable-shell   # 同时起公网隧道，手机哪里都能连
```

健康检查：<http://127.0.0.1:7420/healthz>

### 扫码配对（内置 cloudflared）

`--tunnel` 让代理自己拉起 `tools/cloudflared-*.exe`，把这个端口发布到一个
Cloudflare 地址上（PC 主动出站，不需要公网 IP、端口映射或 VPN）。启动后：

```text
  pair   : http://127.0.0.1:7420/pair   (扫码配对，仅本机可访问)
  tunnel : https://<随机名>.trycloudflare.com  (临时地址)
  public : wss://<随机名>.trycloudflare.com
```

在电脑上打开 <http://127.0.0.1:7420/pair>，页面里有一个二维码。**用手机相机扫它**，
手机会直接跳到 TermDesk 并自动填入地址与令牌——不用手输那 43 位令牌。终端里也会
打印同一张码（块字符版），没有浏览器时可用。

- 配对页面只监听本机（`127.0.0.1`），因为它包含令牌，不能经隧道暴露。
- **固定地址**：如果本机已有 cloudflared 配置（`~/.cloudflared/<name>-config.yml`，
  即 `cloudflared tunnel create` 生成的那份），代理会直接使用它，地址就是配置里
  指向本端口的那个 hostname。本机当前配置把 `term.wolfoxlabs.xyz` 指向
  `127.0.0.1:7420`，所以手机端固定用 `wss://term.wolfoxlabs.xyz`。
  优先级：`TERMDESK_TUNNEL_CONFIG` → `~/.cloudflared/termdesk-config.yml` →
  目录里其它带 hostname 的配置 → `~/.termdesk/cloudflared.json` 的 token →
  临时 `*.trycloudflare.com`。hostname 按 **service 端口** 挑选（同一份配置里
  `dsh.wolfoxlabs.xyz` 指向 3080，不会被误选）。
- **不需要 VPS 中转**：电脑主动出站连 Cloudflare，到手机只走 443。VPS 中转
  （`~/.termdesk/relay.json`）已改为默认关闭，需要时设 `TERMDESK_RELAY=1`。
- 地址与令牌都进了手机的系统钥匙串/偏好；扫码即完成配对，App 之后自动重连。

配对令牌存在 `~/.termdesk/token`（首次运行自动生成，权限 600）。

**终端默认关闭**：任意命令执行是这个代理最危险的能力，必须显式用
`--enable-shell`（或 `TERMDESK_ENABLE_SHELL=1`）开启。未开启时手机端会给出明确
提示，而不是静默失败。

**文件访问范围**：默认允许用户主目录与 C/D/E 盘根目录，可用
`TERMDESK_ROOTS="E:\;D:\work"` 收窄。所有路径都会解析并从符号链接层面校验，
越界一律拒绝。

## 桌面版（Windows 图形界面）

手机版是 Compose/Material3，而 Compose 有桌面版，所以桌面窗口**直接编译手机版那份主题源码**
（`desktop/build.gradle.kts` 里多挂一个 `kotlin.srcDir` 指向 `android/.../ui/theme`）。
好处不是省事，而是**结构上不可能漂移**：配色、圆角、字号只有一份。不动 `android/` 里的任何文件，
只是读它。

```bash
desktop\TermDesk-Desktop.bat          # 只开窗口
desktop\TermDesk-Desktop.bat -start   # 开窗口并把代理一起起起来
```

窗口里能看到：代理状态（监听地址 / 终端开关 / 允许目录 / 运行时长）、**内核表**（打勾的才是手机
可选的，附上"为什么不行"）、**公网地址与扫码二维码**（地址形态标注"固定域名"或"临时地址"）、
**代理输出**，以及 启动 / 停止 / 重新启动 与打开配对页、安装页。

三条边界，都是刻意的：

- **不是 web 界面**：Compose Desktop 走 Skia 原生渲染，这个程序里没有浏览器。
- **不开机/登录自启**：窗口开着才有代理；窗口关掉时，它会停掉**自己启动的**那个代理。
- **不抢别人的进程**：如果代理是 `TermDesk.bat` 启动的，窗口会如实说明，而不是假装能停掉它。

打包成安装包（`gradlew packageMsi`）留到项目收尾时做——那一步需要机器上有 WiX。

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
node tools/chat-engine-test.js  # 统一对话管线静态检查（不调真实 LLM）
node tools/acp-map-test.js      # ACP 事件映射 / 权限策略（纯函数）
node tools/acp-live-test.js     # ACP 真实握手：initialize / session list / new / load
node tools/kernel-registry-test.js # 内核 tier 诚实性与 spawn 规格
node tools/tunnel-config-test.js   # 隧道选择：按端口挑 hostname
node tools/engines-test.js      # Codex / DSH 一次性任务（ai.*，deprecated）
node tools/chat-e2e.js          # 对话：流式、去重、会话延续（dsh 内核）
```

以上都不调用真实模型：`acp-live-test` 只做协议握手与`session/*` 元数据调用。

桌面版的构建检查：`cd desktop && gradlew.bat compileKotlin`（它同时验证手机版主题仍能编译）。

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

手机在任意网络（移动数据、别家 Wi-Fi）都能连的路径只有一条：公网隧道。局域网直连与
Tailscale 只在特定网络下有效——**换网就断**，这也是为什么隧道是主通道。

| 通道 | 地址 | 说明 |
|---|---|---|
| **公网（主）** | `--tunnel` 打印的 `wss://…trycloudflare.com` | 内置 cloudflared，PC 主动出站，零入站端口 |
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

统一对话入口是 `chat.*`：`chat.create` 可带 `engine: 'codex' | 'dsh'`（缺省
`'dsh'`）以及 `provider` / `model`；chat 列表与详情均带 `engine`。`ai.*` 任务面
已 deprecated，仅作协议兼容。

> **坑**：`encodeFrame` 会把 payload 展开在 `type` 之后，因此 payload 里**不能再有
> `type` 字段**。曾因引擎事件用 `type` 覆盖线帧类型，导致 `ai.finished` 永远收不到
> 且没有任何报错。现引擎/对话事件统一用 `event` / `kind` 字段，并有回归断言守着。

文件传输走同端口的 HTTP（`/upload`、`/download`），用 `Authorization: Bearer`
携带同一令牌：整文件走 JSON base64 会让体积膨胀三分之一并把整个文件读进内存。

## 已知取舍

- **审批**：Codex 的 approval 目前显式拒绝并留痕；ACP 的
  `session/request_permission` 由 `TERMDESK_ACP_APPROVE` 决定（默认 `allow_once`，
  每次决策写进对话记录）。手机上还没有确认界面。
- **shim 内核**（QoderWork CN / Command Code）已记录 CLI 契约但未实测，
  所以保持"不可选"，而不是可选但会失败。

- **终端无真实 PTY**：Windows 上没有原生模块就拿不到 PTY，终端是基于管道的
  JSON-lines 会话。全屏交互式程序（vim、top 的实时刷新）**不能正常工作**，
  但常规命令、目录操作、查看输出都没问题。
- **中断即重启 shell**：`Ctrl+C` 通过重建 PowerShell 进程实现，因此中断会丢失当前
  会话内的变量。这是为可预测性做的权衡（无法从外部打断阻塞的管道）。
- **无单轮取消（dsh）**：DSH SDK 协议没有 cancel 方法；`chat.cancel` 对
  `engine=dsh` 实际是终止该对话的运行时（会话随之结束）。`engine=codex` 可杀掉
  当前回合进程，Codex thread 仍可继续 resume。
- **`ai.*` 任务面已废弃**：协议保留兼容，语义已并入 `chat.*` 统一对话管线；
  新客户端请用 `chat.create` + `engine`，不要新增对 `ai.submit` 的依赖。
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

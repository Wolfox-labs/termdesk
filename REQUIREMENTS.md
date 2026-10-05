# TermDesk 需求与功能单

> 本文是交给下一位开发者的**现状交接**。所有"已验证"的结论都附了复现方式；
> 所有"未验证"的地方都明确标注。请勿把未验证项当成已完成。

---

## 一、这个项目要解决什么

人在外面（校园网 / 蜂窝网络），只有一部 Android 手机，但需要继续用家里那台
Windows 电脑办公。要求不是"远程桌面"（手机屏幕小、流量贵），而是**把电脑当成
后台**：在手机上派任务、看进度、收文件、必要时敲命令。

关键约束来自使用者本人的明确要求：

| 约束 | 说明 |
|---|---|
| **不要双栏** | 手机约 400dp 宽、系统字体 145%，固定三栏会把主区挤到 60dp |
| **不要聊天气泡** | 对话页要像"标注过的文本"，不是微信 |
| **不要总结/结果卡片** | 引擎自己的输出原样呈现，不做二次概括 |
| **左侧栏要能收起** | 主区必须是正常对话，侧栏可折叠 |
| **要能远程联系** | 出门在外必须能跟电脑上的 AI 助手说上话 |

---

## 二、当前状态（已验证）

### 2.1 代码规模

| 部分 | 规模 |
|---|---|
| `pc-agent/src` | 14 文件 / 约 3500 行 |
| `android`（Kotlin） | 23 文件 / 约 5700 行 |
| `pc-agent/tools`（测试） | 33 文件 |

### 2.2 测试套件（全部实测通过）

在本机启动 agent 后运行（终端类用例需 `--enable-shell`）：

| 套件 | 结果 | 覆盖 |
|---|---|---|
| `smoke.js` | **PASS** | 鉴权 + 状态推送 |
| `auth-negative.js` | **ALL PASS** | 错误令牌 / 跳过鉴权 / 畸形帧 |
| `encoding-check.js` | **ALL PASS** | 中文编码链路 |
| `inventory-test.js` | **14/14** | 进程 / 服务 + 保护名单 |
| `files-test.js` | **26/26** | 文件操作 + 路径逃逸防护 |
| `terminal-test.js` | **19/19** | 终端（含 shell 关闭时的拒绝路径） |
| `codex-config-test.js` | **43/43** | Codex provider 配置读写与回滚 |
| `p2-e2e.js` | **10/10** | 文件真实链路 |
| `p3-e2e.js` | **9/9** | 终端真实链路 |
| `sessions-test.js` | **25/25** | 会话解析（Codex + DSH 双引擎） |
| `sessions-live.js` | **21/21** | 会话真实读取 |
| `chat-engine-test.js` | **78/78** | 统一对话管线静态检查（engine 字段、codex 事件映射、resume 参数；**不调真实 LLM**） |
| `engines-test.js` | **31/31** | Codex / DSH 一次性任务（`ai.*`，deprecated 兼容面） |
| `chat-e2e.js` | **38/38** | 原生对话：流式、去重、会话延续（dsh 内核；含 engine=codex 建会话） |
| `tunnel-e2e.js` | **10/10** | 公网隧道全链路 |
| `dsh-web-check.js` | **4/4** | DSH Web 过隧道可用性 |
| `mux-probe.js` | **3/3** | DSH Remote mux 协议 |

### 2.3 已跑通的公网通道

```
手机 ──443──> Cloudflare 边缘 ──隧道(PC 主动出站 7844)──┬─> 127.0.0.1:7420  TermDesk agent
                                                       └─> 127.0.0.1:3080  DSH Web
```

- `https://term.wolfoxlabs.xyz/healthz` → **200**（手机纯蜂窝实测 1.03 s）
- `https://dsh.wolfoxlabs.xyz/` → **200**（手机纯蜂窝实测 1.19 s）

**为什么用 Cloudflare Tunnel 而不是 Tailscale**：本校园网下 Tailscale 打洞失败，
流量绕道中继（PC→纽伦堡 144ms，手机→孟买 217ms）；而 Cloudflare 隧道口 7844
实测 20/20 全通。这与使用者既有的 `api.wolfoxlabs.xyz` 方案同构。

---

## 三、目标功能单

### P0–P3 已完成

| 编号 | 功能 | 状态 |
|---|---|---|
| P0 | 连接链路（token 鉴权、4401 拒绝）+ 实时主机状态 | ✅ |
| P1 | 进程 / 服务管理（含保护名单，按 PID 而非进程名保护） | ✅ |
| P2 | 文件管理（浏览 / 上传 / 下载 / 编辑，根目录隔离） | ✅ |
| P3 | 终端（持久 PowerShell 会话） | ✅ |

### P4 统一对话管线 —— 核心已完成

**关键设计**：产品上只有**一条对话管线**（`chat.*`），内核在建会话时选择；
「任务」不再是独立语义。

| 内核 | 多轮延续 | 进度事件 | 机制 |
|---|---|---|---|
| `engine=dsh`（默认） | ✅ 同 sessionId 真延续 | ✅ 流式 | 每对话一个常驻 `dsh --profile sdk` 运行时（stdio JSON-RPC） |
| `engine=codex` | ✅ `codex exec resume <thread_id>` | ✅ JSONL 事件 | 每轮一个 `codex exec` 进程，thread_id 由 Codex 自己持有 |

- `chat.create` 可带 `engine` / `provider` / `model`；`engine` 缺省为 `dsh`，保持兼容
- codex 的 `provider`/`model` 以 `-c model_provider=` / `-c model=` 传入，不改写
  `config.toml`（持久配置仍归 `codex.get` / `codex.apply`）
- 对话事件统一映射到同一套 chat 事件种类（`message` / `reasoning` / `tool` /
  `tool_result` / `turn` / …），手机端一套渲染
- `ai.*`（一次性任务）**已 deprecated**：协议保留兼容，新能力一律进 `chat.*`

已验证（静态）：`tools/chat-engine-test.js` 78/78 —— 引擎字段、resume 参数、事件映射、
`encodeFrame` 不被 payload `type` 覆盖。真实双轮续聊仍以 `chat-e2e.js`（dsh）与
`engines-test.js`（codex thread resume）的实测为准，本轮未跑真实 LLM。

| 子项 | 状态 |
|---|---|
| `chat.create` 选 engine（codex / dsh） | ✅ 静态已验证 |
| chat 列表 / 详情带 `engine` | ✅ 静态已验证 |
| dsh：每对话一运行时、原生会话延续 | ✅ 已验证（chat-e2e） |
| codex：thread_id resume 多轮 | ✅ 机制沿用已有实现；**未在本轮重新实测** |
| codex 事件映射到统一 chat 事件种类 | ✅ 静态已验证 |
| 流式 delta 逐块下发（dsh） | ✅ 已验证 |
| 用户消息去重（乐观回显 vs 运行时回显） | ✅ 已验证 |
| 注入上下文与用户输入区分（`sourceKind`） | ✅ 已验证 |
| 进程级心跳与空闲回收 | ✅ 已实现 |
| Android 对话页（单栏、可折叠抽屉、原生渲染） | ✅ 已编译，**未真机验证** |

### P5 待办（按优先级）

| 编号 | 事项 | 说明 | 状态 |
|---|---|---|---|
| **P5-1** | **修复：手机浏览器里左栏收不回去** | 见第四节；插件源码已落在 `plugins/sidebar-unhide/`，**待安装到 `~/.dsh/local-plugins` 并真机验证** | 🟡 |
| P5-2 | 开机自启 | agent 与隧道目前都不自启；使用者明确表示**暂不需要** | ⏸ |
| P5-3 | 公网访问加固 | 当前只有 43 字符 token 一道防线；建议上 Cloudflare Access | 🟡 代码侧第二因子 `TERMDESK_ACCESS_KEY` 已实现（`tools/access-key-test.js` 7/7）；Cloudflare Access 部署见 4.1 |
| P5-4 | 大文件上传 | Cloudflare 免费版单请求体 100 MB 上限，超限需分片 | ✅ 分片会话已实现（`/upload/session`），Android 端 >32MB 自动走分片；`tools/upload-chunk-test.js` 11/11 |
| P5-5 | 娱乐功能 | 独立闲聊 + 宠物（使用者提过，未细化） | ⏸ |
| P5-6 | 真机验证 | Android 对话页尚未在真机上跑过完整流程 | 🔴 |

---

## 四、待办详述

### 4.1 P5-3 公网访问加固

**代码侧（已实现）**：设置 `TERMDESK_ACCESS_KEY` 后，`/healthz`、`/upload*`、
`/download` 与 WebSocket 升级都必须同时出示：

| 通道 | 携带方式 |
|---|---|
| HTTP | 头 `X-TermDesk-Key: <key>` 或查询 `?access=<key>` |
| WebSocket | 升级 URL `?access=<key>` |

配对 token 仍是主凭证；access key 是公网暴露时的第二道闸。不设则行为不变
（局域网 / Tailscale 免配置）。

**部署侧（建议，未实施）**：在 Cloudflare Zero Trust 给
`term.<domain>` / `dsh.<domain>` 挂 [Cloudflare Access](https://developers.cloudflare.com/cloudflare-one/access/)，
用邮箱 OTP 或 GitHub 登录做身份闸，再叠加 agent 自身的 token。这样 43 字符
token 不再是唯一防线。

```powershell
# 启用第二因子
$env:TERMDESK_ACCESS_KEY = "<long random>"
node src/server.js
# 手机 App 连接时：ws://host:7420?access=<long random>
```

自检：`node tools/access-key-test.js`（7/7）。

### 4.2 P5-1 缺陷详述

### 现象
手机浏览器打开 `https://dsh.wolfoxlabs.xyz/`，左侧栏展开后**没有任何方式收起**，
只能刷新页面。

### 根因（已用真实浏览器取证，非推断）

`dsh-tauri` 插件在浏览器里注入了一条无条件规则：

```js
CssRender().css([
  t('button[aria-label="收起侧边栏"], button[aria-label="Collapse sidebar"]',
    { display: 'none !important' }),
  t('button[aria-label="新会话"], button[aria-label="New session"]',
    { justifyContent: 'center !important' }),
])
```

**设计意图**：在 Tauri 桌面外壳里，收起侧栏由**原生外壳**负责——标题栏那个图标
通过 `postMessage` 发 `dsh://sidebar:toggle`：

```js
case 'dsh://sidebar:toggle': e.toggleSidebar(); break;
```

所以插件把网页里的按钮藏了，避免两个入口重复。

**缺陷**：手机浏览器里**没有 Tauri 外壳**，没有人发那条消息，而按钮又被藏了
→ 完全死锁。

### 取证方式（可复现）

```bash
node tools/sidebar-find-rule.js http://127.0.0.1:3080/ 412 915
```

输出（真实）：

```
button[aria-label="收起侧边栏"], button[aria-label="Collapse sidebar"]   [regular]
    display: none !important; display: none !important
```

配套诊断脚本（都在 `tools/`，均为只读）：

| 脚本 | 作用 |
|---|---|
| `sidebar-inspect.js` | 手机宽度下测量侧栏与按钮的真实 DOM 布局 |
| `sidebar-open-check.js` | 先展开侧栏，再检查有无可点的收起入口 |
| `sidebar-why.js` | 打印按钮及其祖先链的 computed style |
| `sidebar-find-rule.js` | 用 CDP 的 CSS 域解析级联，找出隐藏它的确切规则 |
| `sidebar-css.js` | 导出所有样式表原文 |

### 两个关键澄清

1. **右上角那个按钮不是左栏的**：那是 `dsh-better-sidebar` 的**右侧栏**按钮。
2. **左栏本身不是 `dsh-better-sidebar`**：左栏原属 DSH 内核
   `@deepseek-ai/dsh-client-ui-sidebar`，但已被 `dsh-tauri-panel` **替换**成一个
   结构相同、类名前缀为 `dshp-` 的版本。

### 修法（源码已实现，待安装验证）

在 `~/.dsh/cordis.patch.yml`（home 层，桌面 App 永不覆盖）里 `insert` 一个小
client 插件，注入一条覆盖规则，**仅在检测不到 Tauri 外壳时**还原该按钮：

```css
button.dshp-panel__toggle[aria-label="收起侧边栏"],
button.dshp-panel__toggle[aria-label="Collapse sidebar"],
button[aria-label="收起侧边栏"],
button[aria-label="Collapse sidebar"] {
  display: inline-flex !important;
  visibility: visible !important;
  pointer-events: auto !important;
}
```

Tauri 检测：`window.__TAURI__` / `__TAURI_INTERNALS__` / `__TAURI_IPC__` 任一存在，
或 `window.parent !== window`（被外壳 iframe 包着）时**不注入**，避免和桌面壳抢按钮。

**插件源码在本仓库 [`plugins/sidebar-unhide/`](plugins/sidebar-unhide/)**，含
`package.json` / `cordis.patch.yml` / `lib/client.js` / 静态自检 `test-unhide.mjs`
（26 项，已通过）。安装步骤见该目录 README；装到 `~/.dsh/local-plugins` 并重启
DSH 后，用 `tools/sidebar-open-check.js` 做真机宽度复验。

`~/.dsh/cordis.patch.yml` 当前已含一处针对本部署的修改（`trustedHosts` 追加
`dsh.wolfoxlabs.xyz`），那是为了让隧道域名通过 Host 围栏，与本缺陷无关。

---

## 五、架构与关键决策

### 5.1 一条对话管线，内核可选

产品共识：**不要**把「对话」和「任务」拆成两条隔离管线。入口只有一个 `chat.*`，
建会话时用 `engine` 选内核；旧的 `ai.*` 任务面保留协议兼容，但语义上已收敛为
"一次性对话"的遗留形态，文档与注释均标 deprecated。

| 内核 | 方案 | 会话延续 | 进度事件 | 结论 |
|---|---|---|---|---|
| dsh | `dsh --profile headless "<task>"` | ❌ 一次性，无记忆 | ❌ | 只能靠拼接历史近似（仅剩 `ai.*` 兼容面在用） |
| dsh | `dsh --profile sdk`（stdio JSON-RPC） | ✅ 同 sessionId 真延续 | ✅ 流式 | **chat 默认内核** |
| codex | `codex exec` + `codex exec resume <thread_id>` | ✅ Codex 原生 thread | ✅ JSONL 事件 | **chat 可选内核**；续聊不重造多轮，直接 resume |

### 5.2 为什么服务端直接对接 DSH，而不是复刻桌面 App

桌面 App 的后端是**进程内 Cordis 图 + Tauri 外壳**，其 HTTP 面是浏览器 BFF 而非
聊天 API；`sdk` profile 才是 DSH 官方给进程外客户端的入口。

### 5.3 协议 v1

一帧一个 JSON 对象，均带 `type`。首帧必须是 `auth`，否则以 `4401` 关闭。

| 方向 | 帧 |
|---|---|
| C→S | `auth` · `status.get` · `status.subscribe` · `status.unsubscribe` |
| C→S | `procs.list` · `procs.kill` · `services.list` · `services.action` |
| C→S | `fs.list` · `fs.read` · `fs.write` · `fs.mkdir` · `fs.delete` · `fs.rename` · `fs.roots` · `fs.search` · `fs.doctext` |
| C→S | `term.open` · `term.run` · `term.interrupt` · `term.close` · `term.list` · `ping` |
| C→S | `ai.engines` · `ai.submit` · `ai.tasks` · `ai.task` · `ai.cancel` · `ai.reset`（**deprecated**，一次性任务兼容面） |
| C→S | `codex.get` · `codex.apply` · `codex.restore` |
| C→S | `sessions.list` · `sessions.read`（磁盘上的历史会话，只读） |
| C→S | `chat.list` · `chat.create` · `chat.resume` · `chat.send` · `chat.read` · `chat.cancel` · `chat.close` · `chat.config` · `chat.approve` |
| S→C | `auth.ok` · `auth.fail` · `hello` · `status` · `procs` · `services` |
| S→C | `fs.listing` · `fs.file` · `fs.written` · `fs.roots` |
| S→C | `term.opened` · `term.output` · `term.exit` · `term.list` |
| S→C | `ai.engines` · `ai.tasks` · `ai.task` · `ai.started` · `ai.event` · `ai.finished`（**deprecated**） |
| S→C | `codex.config` · `sessions` · `session` |
| S→C | `chats` · `chat` · `chat.event` · `chat.status` · `chat.turn` · `chat.sent` · `chat.closed` · `chat.approval` |
| S→C | `action.result` · `error` · `pong` |

**统一对话协议要点**（`chat.*`）：

| 字段 | 说明 |
|---|---|
| `chat.create.engine` | 任何"可选内核"的 id：`'codex'` / `'dsh'`（原生适配）或 ACP 内核（`'opencode'` / `'mimo'`，见 §5.6）；缺省 `'dsh'`；未接入的 tier 回 `bad_engine` 并说明原因 |
| `chat.create.provider` / `model` | 可选。dsh 传给 SDK `initialize`；codex 以 `-c model_provider=` / `-c model=` 覆盖单次执行，不写 `config.toml` |
| `chats[]` / `chat` 详情 | 均带 `engine`；codex 另带 `threadId`（首回合前为 `null`） |
| `chat.event.item` | 事件记录用 `kind`（`message`/`reasoning`/`tool`/`tool_result`/`turn`/…），**禁用 `type` 字段** |
| codex 续聊 | `thread.started` 拿到 `thread_id` 后，后续 `chat.send` 走 `codex exec resume <thread_id>`，不重喂历史 |

**踩过的坑**：`encodeFrame` 会把 payload 展开在 `type` 之后，所以 payload 里
**不能再有 `type` 字段**——曾因引擎事件用 `type` 覆盖了线帧类型，导致
`ai.finished` 永远收不到且无任何报错。现引擎/对话事件统一用 `event` / `kind` 字段，
并有"每个帧都使用已声明的协议类型"的回归断言。

### 5.3.1 审批：所有"能否执行"都走同一条路

两个内核都在问同一个问题，过去各有半套答案：Codex 的 approval **一律显式拒绝**（回合可见地失败），
ACP 的 `session/request_permission` **按固定策略自动放行**并事后留痕。持手机的人才是该决定的那个，
所以两者现在都收敛到 `src/approvals.js` 的 `ApprovalBroker`：

| 方向 | 帧 | 含义 |
|---|---|---|
| S→C | `chat.approval` | 一条待答请求：`requestId` / `chatId` / `engine` / `title` / `detail` / `kind` / `options[]` / `expiresAt`；请求结束时再发一条带 `state:'resolved'` 与 `optionId` 的同名帧 |
| C→S | `chat.approve` | `{ requestId, optionId }`，`optionId` ∈ `allow_once` / `allow_always` / `deny` |

三条规则（都有测试）：

1. **绝不挂起**：每个请求都有期限，超时按 `fallback` 结算并记录 `by:'timeout'`。手机在口袋里睡着，
   不能把一轮对话永久冻住。
2. **不静默猜测**：完全没有客户端连着时立刻按 `fallback` 结算（`by:'offline'`），而不是等一个
   可能永远不回来的客户端。
3. **一定留痕**：任何裁决（含超时与离线）都会写成会话里的一行 `engine_note`。

另外两条安全性质：请求的 `fallback` **永远不会退化成内核根本没提供的"允许"**（无法表达时一律按拒绝／
取消处理）；已经结束的 `requestId`、或该请求没提供的 `optionId`，都会被拒绝，陈旧的一次点击
不可能决定另一个问题。

内核侧映射：ACP 直接复用内核自己的 `optionId`（`allow_once` / `allow_always` / `reject_*`），
`reject_*` 与 `cancel` 这类不在我们词汇里的选项不提供给手机，拒绝时给内核回
`{ outcome: 'cancelled' }`；Codex 侧把选项翻成它的 `ReviewDecision`
（`approved` / `approved_for_session` / `denied`，见 `kernels/codex.js` 的 `decisionFor`，
**尚未在真实 approval 上实测过**，本机 Codex 沙盒宽松，从未触发过审批）。

### 5.3.2 只在本机可访问的 JSON 接口

桌面窗口是**同一个代理的另一个客户端**，它不重新推导任何状态（哪些内核可选、公网地址是什么），
而是读代理自己的回答。三个接口都只允许回环地址访问，因为它们描述这台电脑本身（配对数据里还带着令牌）：

| 接口 | 内容 | 用途 |
|---|---|---|
| `GET /status.json` | 主机名、平台、监听、shell、允许目录、运行时长、隧道状态、是否已构建 APK | 桌面窗口的状态面板 |
| `GET /kernels.json` | 注册表解析后的内核表（tier / 是否可选 / 原因） | 桌面窗口的内核表与手机选单同源 |
| `GET /pair.json` | 配对地址、令牌、payload，以及 `qr` 模块矩阵 | 配对页与桌面窗口的二维码 |

`qr` 是 `{ size, rows: ['0101…'] }` 的布尔网阵：桌面版用 Compose 画方块，不需要引入 SVG 渲染。

### 5.3.3 桌面版窗口

见 README《桌面版》。要点：Compose Desktop 原生窗口（非 web），主题直接编译手机版的
`Theme.kt` / `Monokai.kt`（`desktop/build.gradle.kts` 的 `kotlin.srcDir` 指向 `android/`，
不修改任何 Android 文件）；窗口关闭时停掉自己启动的代理；代理不是自己启动的时如实说明并拒绝接管。

### 5.4 为什么 `chat.event` 要带上完整 item

帧里带整个事件记录（`item`），不只带 `seq`。否则手机每收到一个 token 都要回一次
`chat.read`，流式输出会闪。

---

## 六、已知取舍与限制

| 项 | 说明 |
|---|---|
| 终端无真实 PTY | Windows 无原生模块拿不到 PTY，是基于管道的 JSON-lines 会话；vim/top 等全屏程序不可用 |
| 中断即重启 shell | `Ctrl+C` 通过重建 PowerShell 实现，会丢失会话内变量 |
| 无单轮取消（dsh） | DSH SDK 协议没有 cancel 方法；`chat.cancel` 对 `engine=dsh` 实为"终止该对话的运行时"。`engine=codex` 可杀掉本轮进程，thread 仍可续 |
| `ai.*` 任务面 | **deprecated**：协议兼容保留，语义已并入 `chat.*`；新客户端不要依赖 |
| 大文件 | Cloudflare 免费版单请求体 100 MB 上限 |
| 单客户端路由 | agent 同时只把流推给最后一个认证的客户端（与终端输出一致） |

---

## 七、环境（本机已验证）

| 项 | 位置 |
|---|---|
| Android SDK | `E:\Android\Sdk` |
| Gradle 8.13 | `E:\Android\tools\gradle-8.13` |
| JDK 21 | `C:\Program Files\Java\jdk-21` |
| DSH 安装 | `%APPDATA%\io.github.hairyf.deepseek-harness-desktop\dependencies\dsh` |
| 配对令牌 | `~/.termdesk/token` |

**本网络注意**：`services.gradle.org`、GitHub releases 均不可直连，需走本地代理
`http://127.0.0.1:10808`（v2rayN）；Gradle wrapper 与 `settings.gradle.kts` 已指向
可达镜像。

---

## 八、给接手者的建议起点

按 §5.5 / §5.6 的现状往下接：

1. **先跑一次手工启动**（`TermDesk.bat`），看启动报告里的内核表与公网自检是否符合预期。
2. **shim 实测**（QoderWork CN / Command Code）：两者的 CLI 契约已记录在注册表里，
   但还没有跑过一次真实回合——这是它们从"已发现"变成"可选"的唯一缺口。
3. **审批 UI**：Codex 的 approval 目前是显式拒绝 + 留痕；ACP 的
   `session/request_permission` 目前按 `TERMDESK_ACP_APPROVE` 决定（默认放行并留痕），
   两者都该收敛成一个手机上的确认界面。
4. **本机内核**（手机 Termux 沙盒）见 `documents/本地内核方案.md`，尚未实现。
5. 动公网加固前先与使用者确认——agent 具备任意命令执行与全盘文件权限，
   目前仅靠 token 保护。

## 九、PC 端启动、公网通道与内核接入（本次新增）

### 9.1 手工启动：`TermDesk.bat`

双击即可，等价于：

```
node pc-agent/src/server.js --host 0.0.0.0 --enable-shell --tunnel
```

启动时按顺序打印三件事，不需要在手机上试探：

1. **头部**：监听地址、shell 是否开启、允许的目录、局域网地址（已按"像家庭/办公网"
   排序，虚拟网卡排在后面）、配对页与安装页地址。
2. **内核表**：每个内核一行，带 tier 与真实原因。`✔` = 手机可选，`○` = 已安装但
   未接入，`·` = 未找到。`TERMDESK_KERNELS_PROBE=1` 时还会做一次 ACP 握手，
   把内核自己声明的能力写在同一行（"可回放历史 / 可列会话 / 可恢复 / 可 fork"）。
3. **公网**：启动 cloudflared，打印地址与形态（固定域名 / 临时地址），并用一次
   公网 `/healthz` 请求自检"这个地址真的能连上"，通过后才打印配对二维码。

### 9.2 公网通道：Cloudflare 命名隧道（已实测可用）

本机已有 `~/.cloudflared/termdesk-config.yml`（tunnel `eb39ed5e-…`），
ingress 把 `term.wolfoxlabs.xyz` 路由到 `127.0.0.1:7420`。
agent 现在**优先使用它**，所以地址是固定的：

```
https://term.wolfoxlabs.xyz   →   wss://term.wolfoxlabs.xyz（手机用的就是这个）
```

优先级：`TERMDESK_TUNNEL_CONFIG` 环境变量 → `~/.cloudflared/termdesk-config.yml`
→ 目录里其它带 hostname 的 config → `~/.termdesk/cloudflared.json` 里的 token →
临时 `*.trycloudflare.com` 地址。主机名按 **service 端口**挑选，不是"文件里第一个
hostname"——同一份配置里 `dsh.wolfoxlabs.xyz` 指向的是 3080。

实测（本轮）：命名隧道注册成功，`https://term.wolfoxlabs.xyz/healthz` 返回
`{"ok":true,"service":"termdesk-pc-agent","protocol":1}`，`/app` 返回 200。

不需要 VPS 中转：电脑主动出站连 Cloudflare，到手机只走 443，无需公网 IP、无需端口映射。
VPS 中转（`~/.termdesk/relay.json`）已改为**默认关闭**，需要时设 `TERMDESK_RELAY=1`。

### 9.3 内核接入：一个 ACP 适配器点亮所有 ACP 内核

内核注册表在 `pc-agent/src/kernels/registry.js`，是**唯一的事实来源**：picker、探针、
对话管线都读它，所以三者不会再各自漂移。tier 的含义就是手机能不能选：

| tier | 含义 | 本机现状 |
|---|---|---|
| `native` | 为单个产品写的适配器 | Codex（官方 app-server）、DSH（SDK 运行时） |
| `acp` | 由共享的 ACP 适配器驱动 | OpenCode 1.3.16、MiMo Code 0.1.9 |
| `shim` | Claude Code 形状的 CLI，契约已记录、尚缺实测 | QoderWork CN、Command Code |
| `unsupported` | 已安装但没有可编程接口 | Antigravity、豆包 |

`pc-agent/src/kernels/acp.js` 是那一个适配器：

| ACP | TermDesk 里的含义 |
|---|---|
| `session/new` | 新对话（由 TermDesk 指定工作目录） |
| `session/list` | 内核自己的会话索引 → 手机的历史列表 |
| `session/load` | 打开历史：内核回放自己的记录 → 历史正文；**同一个 session 继续发消息** |
| `session/prompt` | 一轮对话，期间以 `session/update` 通知流式返回 |
| `session/cancel` | 停止本轮（ACP 定义为通知，无返回值） |
| `session/request_permission` | 内核反请求权限；**必须回答**，否则该轮永久挂起 |

因此"历史"和"对话"在 ACP 上不是两套东西：**同一个 session id 既是历史条目也是可继续的
对话**，这正是"历史即对话"想要的形态，而现在它由内核提供，不是 TermDesk 重写的。

新增一个 ACP 内核 = 加一条数据（连代码都不用改）：

```
set TERMDESK_ACP_KERNELS=[{"id":"mine","label":"Mine","bin":"C:\path\mine.exe","args":["acp"]}]
```

**权限策略**：`session/request_permission` 默认选 `allow_once`（本机已开启 shell，
使用者已选择完全控制），每次决策都写进对话记录作为留痕；设 `TERMDESK_ACP_APPROVE=deny`
则改为拒绝。审批 UI 落地后这里会收敛（见 §八）。

### 9.4 shim：Claude Code 形状的 CLI

两者都有完整的会话面（`--print` 非交互、`--output-format` 结构化输出、按 id 恢复），
差的只是"跑一次实测"。契约已记录在注册表里：

| 内核 | 新会话 | 续聊 | 列会话 |
|---|---|---|---|
| QoderWork CN | `--print --output-format stream-json <prompt>` | `--resume <id>` | `--list-sessions`（已实测可返回） |
| Command Code | `--print <prompt> --output-format json` | `--session <id>` | 无（按 transcript 路径） |

### 9.5 本次新增的验证

无真实模型调用，全部为元数据 / 纯函数级：

| 测试 | 结果 |
|---|---|
| `tools/acp-map-test.js` | 30/30（事件映射、权限选择、无 `type` 字段） |
| `tools/acp-live-test.js` | 15/15（OpenCode 与 MiMo 的真实 `initialize` / `session/list` / `session/new` / `session/load`） |
| `tools/kernel-registry-test.js` | 24/24（tier 诚实性、picker 与管线一致、spawn 规格） |
| `tools/tunnel-config-test.js` | 12/12（按端口挑 hostname、注释不误解析、公网自检失败返回而非抛出） |

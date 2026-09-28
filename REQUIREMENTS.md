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
| `engines-test.js` | **31/31** | Codex / DSH 一次性任务 |
| `chat-e2e.js` | **38/38** | 原生对话：流式、去重、会话延续 |
| `tunnel-e2e.js` | **10/10** | 公网隧道全链路 |
| `dsh-web-check.js` | **4/4** | DSH Web 过隧道可用性 |
| `mux-probe.js` | **3/3** | DSH Remote mux 协议 |

### 2.3 已跑通的公网通道

```
手机 ──443──> Cloudflare 边缘 ──隧道(PC 主动出站 7844)──┬─> 127.0.0.1:7420  TermDesk agent
                                                       └─> 127.0.0.1:3080  DSH Web
```

- `https://term.example.com/healthz` → **200**（手机纯蜂窝实测 1.03 s）
- `https://dsh.example.com/` → **200**（手机纯蜂窝实测 1.19 s）

**为什么用 Cloudflare Tunnel 而不是 Tailscale**：本校园网下 Tailscale 打洞失败，
流量绕道中继（PC→纽伦堡 144ms，手机→孟买 217ms）；而 Cloudflare 隧道口 7844
实测 20/20 全通。这与使用者既有的 `api.example.com` 方案同构。

---

## 三、目标功能单

### P0–P3 已完成

| 编号 | 功能 | 状态 |
|---|---|---|
| P0 | 连接链路（token 鉴权、4401 拒绝）+ 实时主机状态 | ✅ |
| P1 | 进程 / 服务管理（含保护名单，按 PID 而非进程名保护） | ✅ |
| P2 | 文件管理（浏览 / 上传 / 下载 / 编辑，根目录隔离） | ✅ |
| P3 | 终端（持久 PowerShell 会话） | ✅ |

### P4 原生对话 —— 核心已完成

**关键设计**：一个对话 = 电脑上一个常驻的 DSH SDK 运行时进程。

- 走 `dsh --profile sdk` 的 stdio JSON-RPC（`initialize` / `session/prompt` / `shutdown`）
- 同一个 `sessionId` 上再次 `session/prompt` 即**真正延续**同一会话
- 这是相对旧方案的实质改进：`dsh --profile headless` 是一次性的，第二轮没有记忆

已验证：**第二轮能复述第一轮的信息**（`chat-e2e.js` 中的 marker 断言）。

| 子项 | 状态 |
|---|---|
| 每对话一运行时、原生会话延续 | ✅ 已验证 |
| 流式 delta 逐块下发 | ✅ 已验证 |
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
手机浏览器打开 `https://dsh.example.com/`，左侧栏展开后**没有任何方式收起**，
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
`dsh.example.com`），那是为了让隧道域名通过 Host 围栏，与本缺陷无关。

---

## 五、架构与关键决策

### 5.1 为什么 DSH 走 SDK 而不是 headless

| 方案 | 会话延续 | 进度事件 | 结论 |
|---|---|---|---|
| `dsh --profile headless "<task>"` | ❌ 一次性，无记忆 | ❌ | 只能靠拼接历史近似 |
| `dsh --profile sdk`（stdio JSON-RPC） | ✅ 同 sessionId 真延续 | ✅ 流式 | **采用** |

### 5.2 为什么服务端直接对接 DSH，而不是复刻桌面 App

桌面 App 的后端是**进程内 Cordis 图 + Tauri 外壳**，其 HTTP 面是浏览器 BFF 而非
聊天 API；`sdk` profile 才是 DSH 官方给进程外客户端的入口。

### 5.3 协议 v1

一帧一个 JSON 对象，均带 `type`。首帧必须是 `auth`，否则以 `4401` 关闭。

| 方向 | 帧 |
|---|---|
| C→S | `auth` · `status.get` · `status.subscribe` · `status.unsubscribe` |
| C→S | `procs.list` · `procs.kill` · `services.list` · `services.action` |
| C→S | `fs.list` · `fs.read` · `fs.write` · `fs.mkdir` · `fs.delete` · `fs.rename` · `fs.roots` |
| C→S | `term.open` · `term.run` · `term.interrupt` · `term.close` · `term.list` · `ping` |
| C→S | `ai.engines` · `ai.submit` · `ai.tasks` · `ai.task` · `ai.cancel` · `ai.reset` |
| C→S | `codex.get` · `codex.apply` · `codex.restore` |
| C→S | `sessions.list` · `sessions.read`（磁盘上的历史会话，只读） |
| C→S | `chat.list` · `chat.create` · `chat.send` · `chat.read` · `chat.cancel` · `chat.close` |
| S→C | `auth.ok` · `auth.fail` · `hello` · `status` · `procs` · `services` |
| S→C | `fs.listing` · `fs.file` · `fs.written` · `fs.roots` |
| S→C | `term.opened` · `term.output` · `term.exit` · `term.list` |
| S→C | `ai.engines` · `ai.tasks` · `ai.task` · `ai.started` · `ai.event` · `ai.finished` |
| S→C | `codex.config` · `sessions` · `session` |
| S→C | `chats` · `chat` · `chat.event` · `chat.status` · `chat.turn` · `chat.sent` · `chat.closed` |
| S→C | `action.result` · `error` · `pong` |

**踩过的坑**：`encodeFrame` 会把 payload 展开在 `type` 之后，所以 payload 里
**不能再有 `type` 字段**——曾因引擎事件用 `type` 覆盖了线帧类型，导致
`ai.finished` 永远收不到且无任何报错。现引擎事件统一用 `event` 字段，
并有"每个帧都使用已声明的协议类型"的回归断言。

### 5.4 为什么 `chat.event` 要带上完整 item

帧里带整个事件记录（`item`），不只带 `seq`。否则手机每收到一个 token 都要回一次
`chat.read`，流式输出会闪。

---

## 六、已知取舍与限制

| 项 | 说明 |
|---|---|
| 终端无真实 PTY | Windows 无原生模块拿不到 PTY，是基于管道的 JSON-lines 会话；vim/top 等全屏程序不可用 |
| 中断即重启 shell | `Ctrl+C` 通过重建 PowerShell 实现，会丢失会话内变量 |
| 无单轮取消 | DSH SDK 协议没有 cancel 方法；`chat.cancel` 实为"终止该对话的运行时" |
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

1. **先修 P5-1**（左栏收不回去），它在浏览器里影响所有手机用户，且根因已定位。
2. **再补 P5-6 真机验证**：Android 对话页只编译过，没在真机跑完整流程。
3. 动 P5-3（公网加固）前先与使用者确认——agent 具备任意命令执行与全盘文件权限，
   目前仅靠 token 保护。

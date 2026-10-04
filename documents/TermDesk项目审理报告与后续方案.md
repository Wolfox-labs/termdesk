# TermDesk 项目审理报告与后续方案

**审理日期：** 2026 年 10 月 4 日  
**审理范围：** 当前代码、现有部署脚本、Windows 端启动方式、VPS Relay 与 Cloudflare 通道、Android 历史会话与实时对话实现  
**本文件性质：** 审理结果与后续方案，不是实施记录。本文形成过程中没有修改项目实现、没有构建安装、没有部署服务，也没有重启任何进程。

## 一 结论先行

### 1 主链路应当固定为 VPS 节点，不再把 Tailscale 当作生产依赖

当前项目已经具备经过 VPS 的反向通道雏形：

```text
Android 客户端
    │  wss / https
    ▼
Cloudflare 边缘
    │  VPS 上的 Cloudflare Tunnel
    ▼
VPS Relay
    │  反向 WebSocket
    ▼
Windows PC Agent
    │
    ├─ Codex
    ├─ DSH
    └─ 未来的其他内核适配器
```

传输代码采用 PC 主动出站连接，不需要为 PC 开放公网入站端口。本机配置指定经 v2rayN 的 SOCKS 代理连接 VPS 公网地址；手机连接该公网入口，不必知道 PC 的地址或加入 Tailscale。目标启动方式是用户手动启动 Agent 后建立连接。**目前本机实际仍由登录计划任务运行，不应把目标方案误写成已经实现的手动启动。**

因此，生产架构的方向是正确的；需要解决的是部署收敛、会话统一和启动策略，而不是再增加一条 Tailscale 主链路。Tailscale 可以保留作诊断或局域网备用，但不应成为 TermDesk 的必要条件。

### 2 “历史记录不能继续对话”是架构分界造成的确定性问题

这不是偶发的输入框故障，也不是单纯缺少一个按钮。当前实现实际存在两套对象：

- `sessions.*`：读取磁盘上的 Codex JSONL 或 DSH 压缩会话文件，负责展示历史；只读，不恢复内核。
- `chat.*`：由 PC Agent 内存中的 `ChatManager` 管理实时对话、运行时、事件流和发送操作。

Android 端打开历史时发送 `sessions.read`，同时清空当前 `activeChat`。UI 进入 `recordedSession` 分支后只渲染 `RecordedTranscript`，没有 `Composer`。所以历史页面没有对话框是代码路径的必然结果。

当前的 `chat.resume` 只是一个过渡桥接：

- Codex 可以把历史会话的原生 thread ID 交给 `codex exec resume`，因此具备真实续聊条件。
- DSH 当前明确返回 `resume_unsupported`，不能把展示文本重新喂给新进程冒充恢复。

### 3 产品上应当只有一条复用管线，但技术上必须经过内核适配层

用户期待的“前端壳直接调用成熟 Agent 内核”是正确方向。真正需要统一的不是把所有内核强行改造成同一种实现，而是统一会话生命周期和协议：

```text
Android 展示层
    ↓
统一会话状态与协议
    ↓
PC Agent Kernel Adapter
    ├─ Codex Adapter
    ├─ DSH Adapter
    └─ 未来适配器
    ↓
内核自己的原生会话、历史、恢复、事件与取消机制
```

历史会话和运行会话不应再是两个产品对象。它们应当是同一个 `KernelSession` 在不同状态下的表现：冷态、已连接、运行中、离线、失败或已关闭。历史读取只是冷态读取；继续对话必须把同一个原生会话重新附着到运行管线，而不是新建一个 TermDesk 会话再复制历史文本。

### 4 当前 PC 端确实存在禁止的自动运行机制

本机实测存在名为 **TermDesk Agent** 的 Windows 计划任务，状态为 `Running`，触发方式为用户登录。它会隐藏运行 `E:\aiPic\termdesk\deploy\run-agent.ps1`，脚本内部还会在 Node 进程退出后等待 5 秒再次启动。

这与用户明确的“绝不允许开机自启”硬约束冲突；登录触发也不能作为规避禁令的替代方式。此前自动重启、隐藏监督运行的建议同样撤回，不纳入后续方案。本轮没有停止、卸载、删除或修改该任务，因为当前授权范围是整理审理文档，而不是实施变更。

同时，仓库中的 VPS 服务文件 `E:\aiPic\termdesk\deploy\termdesk-relay.service` 和 `E:\aiPic\termdesk\deploy\termdesk-tunnel.service` 也写有 `Restart=always` 和 `WantedBy=multi-user.target`。`Restart=always` 表示自动重启，`WantedBy=multi-user.target` 是启用时关联启动目标的声明；**仅有文件声明，不足以证明服务已启用开机启动。** VPS 上实际启用状态本轮没有重新查询。禁令不自行限定在 Windows；后续清理应覆盖 TermDesk 自身的相关配置，同时避免误停 VPS 上其他业务。

## 二 已核实的现状

### 2.1 PC Agent 是怎样启动并接入 VPS 的

当前启动过程可以拆成以下步骤：

1. Agent 的启动入口为 `E:\aiPic\termdesk\pc-agent\src\server.js`，由 Node 运行。当前计划任务传入 `--host 127.0.0.1 --enable-shell`，仅监听回环地址并启用终端能力。**不带 `--host` 的默认值是 `0.0.0.0`，并非仅本机监听；手动启动方案必须保留明确的回环监听设置。**
2. `server.js` 读取或创建 PC 配对令牌，并在本机端口 7420 启动 HTTP/WebSocket 服务。
3. 服务启动后读取 `C:\Users\yaosh\.termdesk\relay.json`。该配置包含 VPS 公网地址、节点标识和节点密钥；本机配置还指定通过 v2rayN 的 `socks5://127.0.0.1:10808` 出站。
4. `relay-client.js` 使用 `SocksProxyAgent` 主动连接 VPS Relay 的 `/agent` WebSocket，并发送 `relay.auth`。
5. VPS Relay 验证节点密钥后返回 `relay.ready`。手机连接 Relay 并完成设备凭据认证后，Relay 向 PC Agent 发送 `relay.open`。
6. PC Agent 再从本机回连 `127.0.0.1:7420`，以本地 PC Agent 令牌认证。此后手机帧被 Relay 转发给 PC Agent，PC Agent 的事件再沿同一路径返回手机。
7. 文件上传下载使用 Relay 的 `/transfer` 通道，数据流经 VPS，但内核执行和文件读写仍发生在 PC。

关键点是：**Relay 不是 Agent 内核，也不在 VPS 上执行 Codex 或 DSH。** 它负责公网入口、节点鉴权、手机与节点配对、连接路由和受限的数据转发。PC Agent 才负责进程、文件、终端和内核管理。

### 2.2 当前启动脚本的具体行为

- `E:\aiPic\termdesk\deploy\install-agent.ps1` 注册登录触发的计划任务，并立即启动任务。
- `E:\aiPic\termdesk\deploy\run-agent.ps1` 隐藏运行 Node，循环启动 Agent；Node 退出后等待 5 秒重启。
- 计划任务设置了 `RestartCount=999` 和 1 分钟重启间隔。
- 当前任务描述是“Local TermDesk kernel with reverse VPS transport and restart supervision”。

因此，当前 PC Agent 是由登录任务启动的桥接进程，不是用户打开 Android App 后才启动。Agent 已在运行不等于 Codex 或 DSH 正在执行任务：Codex 的当前代码按回合启动执行进程，DSH 的运行时按对话创建。PC Agent、内核运行时和手机连接是三个不同生命周期。现有启动方式与禁止自启要求不一致。

### 2.3 历史与实时对话为何分裂

证据链如下：

| 位置 | 已核实行为 | 直接后果 |
|---|---|---|
| `E:\aiPic\termdesk\pc-agent\src\sessions.js` | 扫描 Codex JSONL 和 DSH zstd 文件并转换为展示事件；磁盘访问只读 | 能显示历史，但不会让内核恢复运行 |
| `E:\aiPic\termdesk\pc-agent\src\chat.js` | 内存 `ChatManager` 自建 `c-UUID`；DSH 每个 Chat 使用运行时；Codex 使用原生 thread ID | 实时 Chat 有自己的生命周期和 ID |
| `E:\aiPic\termdesk\pc-agent\src\handlers.js` | 同时保留 `sessions.list/read` 与 `chat.*` | 协议层仍把历史和实时分开 |
| `E:\aiPic\termdesk\android\app\src\main\java\dev\termdesk\app\ui\ChatSection.kt` | `recordedSession != null` 时显示 `RecordedTranscript`，不显示 `Composer` | 历史页没有输入框 |
| `E:\aiPic\termdesk\android\app\src\main\java\dev\termdesk\app\data\AgentClient.kt` | `openSession` 发送 `sessions.read` 并清空 `activeChat` | 打开历史会离开实时 Chat 视图 |
| `E:\aiPic\termdesk\pc-agent\src\chat.js` | Codex 采用 `codex exec resume <thread_id>`；DSH 当前返回 `resume_unsupported` | Codex 有条件续聊，DSH 尚未恢复 |

因此，用户提出的“理论上应该直接调用成熟 Agent 应用的会话管理”并不矛盾，反而是正确的修复方向。当前问题在于项目先实现了一个只读历史投影，再另建一个实时管理器；没有让两者共同指向内核的原生会话。

### 2.4 当前已有的绑定和离线能力是部分实现

代码中已经出现以下方向：

- Android 使用 Android Keystore 加密保存设备凭据。
- Relay 支持首次配对后返回长期设备凭据。
- UI 已区分“暂时断开”和“忘记此设备绑定”。
- App 有历史索引和历史详情缓存，PC 离线时可以显示已缓存内容。
- Relay 在节点离线时返回明确的 `node_offline`，而不是伪造发送成功。

这些是实现线索，不应直接当作验收完成。仍需要在真实设备上验证：首次绑定、重启 App 后复用、断开后重连、忘记后重新配对、PC 离线时的导航与错误提示，以及发送失败时草稿是否保留。

## 三 VPS 节点性能与开支评估

### 3.1 资源结论

VPS 的主要成本不是流量，而是常驻进程和瞬时内存峰值。Relay 本身不执行模型，不需要为 Codex 或 DSH 预留运行内存；它的工作是 WebSocket、HTTP 转发、鉴权和少量路由状态。

此前调查记录中的待机样本如下，但本轮没有重新测量，也没有压力测试，不能视为容量保证：

| 组件 | 早先待机样本 | 解释 |
|---|---:|---|
| VPS 可用内存 | 约 252–265 MiB | 当时的观察值，不代表当前状态 |
| Swap 已使用 | 约 170 MiB | 说明内存余量需要谨慎对待 |
| Relay cgroup | 约 19.6 MiB | 统计口径与 PSS 不同 |
| Relay PSS | 约 50 MiB | 不能与 cgroup 数值直接相加 |
| Tunnel cgroup | 约 21.7 MiB | 早先待机观察 |
| Tunnel PSS | 约 32 MiB | 早先待机观察 |

这些样本只能提示 Relay 与 Tunnel 的待机开销可能是数十 MiB 量级，不能据此确认当前机器还有足够余量。只有取得同一时段、同一统计口径的整体内存与压力数据，才能给出可部署结论。主要风险包括 Node 堆、WebSocket 缓冲、JSON 解析和序列化、Tunnel 进程以及 VPS 上既有业务叠加后的峰值。

### 3.2 现有保护上限

当前代码已经设置了若干上限：

- WebSocket 单帧上限约 8 MiB。
- Relay 单连接发送积压超过约 16 MiB 时断开慢消费者。
- 文件传输最多约 8 路并发。
- 单次 HTTP 上传约 40 MiB；更大的文件应走分片会话。
- WebSocket 压缩已关闭，避免压缩带来的 CPU 和内存波动。
- Relay 每个节点当前只允许一个活动手机连接；PC 事件路由也按单客户端处理。

当前明确限制是**每个节点最多一个活动手机**，并不等于 Relay 配置只能包含一个节点。多节点承载能力尚未压测，多手机订阅也没有实现。8 MiB 帧和 16 MiB 积压阈值是协议保护，不是进程的绝对内存上限：帧解析、对象和重新序列化可能同时占用内存。

### 3.3 VPS 最小可行形态

在当前目标下，VPS 只需要承担：

1. 一个轻量 Relay Node 进程；
2. 一个 Cloudflare Tunnel 进程；
3. 必要的系统网络和日志能力。

不应把模型、DSH、Codex 或完整会话运行时搬到 VPS。PC Agent 继续承担内核执行，VPS 只做节点和转发。这样可以把 VPS 内存开支控制在较低范围，也符合“流量富裕、内存紧张”的约束。

Cloudflare 免费额度足够是用户提供的规划前提，本轮未核对其账户计划及当前限制。VPS 流量充足与 Cloudflare 的产品限制是两件事；控制帧、文件分片和大历史传输都应按实际入口验收，不能只验证 `/healthz`。

仓库为 Relay 和 Tunnel 各设 `MemoryMax=256M`。这不是预先占用或预留 256 MiB，也不是推荐预算；如果两者接近各自上限，再叠加其他服务，小内存 VPS 仍可能承压。后续应根据实测分别设定预算和余量，避免依靠 Swap 掩盖问题。若 VPS 已有可复用的 Tunnel 进程，可单独评估复用入口是否节省常驻开销，但不能未经审阅修改既有业务隧道。

### 3.4 必须补做的压力测试

后续实施阶段建议只做测量，不先扩大功能范围：

| 场景 | 记录指标 | 通过条件建议 |
|---|---|---|
| VPS 空载 30 分钟 | RSS、PSS、Swap、CPU、重启次数 | 无异常增长，无非预期重启 |
| 单手机连续对话 | 延迟、帧积压、内存峰值 | 不进入 Swap 风暴，连接稳定 |
| 大历史读取 | PC 与 VPS 的内存峰值、最大帧、读取时长、手机内存 | 不因一次读取拖垮 Agent、Relay 或 App |
| 32–100 MiB 文件分片 | 带宽、内存、失败重试 | 流式传输，不把整文件读入 VPS 内存 |
| PC 断网再恢复 | Relay 状态、重连次数、事件补齐 | 明确离线，不伪造成功；恢复后可继续 |
| 多次错误鉴权 | CPU、连接数、封禁行为 | 失败不会形成重连风暴 |

另一个独立瓶颈在 PC：`E:\aiPic\termdesk\pc-agent\src\sessions.js` 会同步读取整份 Codex 文件，并对 DSH 压缩文件进行整文件读取和解压。`MAX_EVENTS=4000` 只限制返回事件数量，不限制前面的读取与解压内存。改造时需要分页或流式读取，以及按字节限制每页响应。

这些数据拿到之前，不能给 VPS 资源做“肯定足够”的承诺。

## 四 建议的目标架构

### 4.1 分层职责

| 层 | 责任 | 不应承担的责任 |
|---|---|---|
| Android 展示层 | 导航、渲染事件、输入、状态提示 | 解释 Codex/DSH 私有历史格式 |
| Android 会话状态层 | 维护当前节点、内核、会话状态和缓存 | 把缓存文本当作上下文权威 |
| VPS Relay | 公网入口后的设备认证、节点路由、受限转发 | 执行 Agent、保存完整会话、生成摘要 |
| PC Agent | 本机权限、文件/终端、适配器调度、事件补齐 | 让 UI 直接依赖某个内核私有命令 |
| Kernel Adapter | 发现、列出、读取、附着、发送、取消、订阅原生会话 | 另造一套与内核无关的“伪历史” |
| Codex/DSH 等内核 | 原生会话、上下文、模型调用、工具执行、持久化 | 适配 Android UI 细节 |

TLS 与公网入口由 Cloudflare 及 Tunnel 链路协作提供。当前 Relay 会解析 JSON、鉴权并重新转发，不能称为完全不理解内容的盲转发，也没有实现应用层端到端加密；这些应作为当前的信任边界明确记录。

“同一条管线”意味着同一会话身份、生命周期和命令事件语义，不意味着所有文件字节必须塞进一条 WebSocket。现有 HTTP 文件流可以保留，前提是其身份与权限仍绑定到同一节点。

### 4.2 统一 `KernelSession` 语义

建议把以下字段作为跨内核的稳定身份：

```text
nodeId             节点身份
kernelId           内核实例或适配器身份
engine             codex / dsh / future
nativeSessionId    内核原生会话 ID
workspace          工作目录
capabilities       可恢复、可发送、可取消、可订阅等能力
state              offline / available / running / failed / closed
cursor             历史和事件补齐游标
```

TermDesk 可以保留运行时句柄、UI ID 和 request ID，但它们必须明确映射到原生会话，不能成为另一套上下文的权威。持久身份应由**节点、内核实例、原生会话 ID**共同确定，而不是仅用 `engine + nativeSessionId`，避免未来多主机或多安装实例碰撞。新会话尚未获得原生 ID 时可以有临时创建状态，获得 ID 后必须归入同一身份。

### 4.3 适配器最小接口

每个内核适配器都应提供同一组语义，具体命令由适配器内部处理：

```text
probe()                         检查已安装、版本、可执行性和健康状态
discoverCapabilities()          返回可用能力
listSessions(cursor)            分页列出原生会话
readSession(id, cursor)         读取历史事件并返回下一游标
attachOrResume(id)              附着或恢复原生会话
send(id, prompt)                发送用户输入
cancel(id, turnId)              取消回合或诚实返回不支持
subscribe(id, cursor)           订阅实时事件并补齐断线期间的事件
close(id)                       释放运行资源
```

### 4.4 Codex 与 DSH 的具体判断

- **Codex：** 当前已有原生 thread ID 和 `exec resume` 机制，短期可以保留并完善；中期应核实 `app-server` 是否能提供更稳定的常驻会话、事件游标和取消语义。本报告不把未核实的 Codex API 当作已完成能力。
- **DSH：** 当前代码通过 `--profile sdk` 使用长驻 stdio JSON-RPC 运行时；旧测试说明记载了多轮延续，本轮没有重跑。当前 TermDesk 的历史恢复分支明确拒绝 DSH，但这不证明 DSH 内核本身没有恢复能力。安装目录中可以看到 `dsh-api-session-controller`、`dsh-session-query`、`dsh-session-persistence` 等候选包；具体调用签名、profile 要求、跨进程恢复和真机效果仍需最小验证。不能修改第三方安装目录来“猜”出恢复功能。
- **未来内核：** 实现适配器与能力声明后，可复用现有展示层；内核特有的审批、输入请求、工具事件仍需协议与渲染支持，不能为了统一而静默丢弃。无法标准化的事件应保留可追踪的原始类型或扩展元数据。

当前 `E:\aiPic\termdesk\pc-agent\src\engines.js` 已有 `probeEngines()`，但主要依赖已知路径与文件存在性；不能等同于主动检查内核的可执行性、认证状态和恢复能力。发现阶段可以做不触发模型推理的版本与握手检查，不应自动安装、升级或修改第三方内核。

## 五 绑定、离线和启动策略

### 5.1 绑定策略

目标应当是“首次绑定一次，之后复用设备凭据”：

1. 首次输入节点地址和短时配对码；
2. Relay 返回设备凭据；
3. Android 保存节点信息，并使用 Keystore 保护设备凭据；
4. 用户正常打开客户端时复用绑定，自动建立网络连接，不再重复填写地址和配对码；**不注册手机开机启动，也不替用户启动 PC Agent**；
5. “暂时断开”只停止本次连接；“忘记绑定”才删除设备凭据并回到配对流程。

节点地址和设备凭据仍应允许用户查看、替换和撤销，但不应让每次登录都重复手工输入。

### 5.2 PC 离线时 App 的行为

PC Agent 离线不应使客户端失去全部价值。允许的行为包括：

- 保留导航、设置、节点和内核状态；
- 展示已经缓存的会话索引和历史详情；
- 明确显示“VPS 在线但 PC 内核离线”或“VPS 也不可达”；
- 保留用户草稿，不把发送失败显示成成功；
- 节点恢复后重新获取会话索引，并按游标补齐遗漏事件。

离线缓存是可用性保障，不是新的上下文来源。真正的继续对话必须在原生内核会话重新附着后进行。

### 5.3 明确禁止自动启动

本项目后续设计和实现不得包含以下内容：

- Windows 开机自启；
- Windows 登录自启；
- 计划任务隐藏运行；
- 进程退出后的自动重启循环；
- 以“守护”“监督”“后台保活”“可靠性服务”等名称替代上述行为；
- TermDesk 自身 VPS 组件的开机自启；现有自动重启策略也不纳入建议方案。

本报告不自行为 PC、Android 或 VPS 设置自启豁免。现有配置的停止和清理会改变实际运行状态，必须等用户审阅并授权后执行。用户明确启动进程后的网络连接、正常打开 App 后的绑定复用，与开机启动进程不同；网络重连不得重新拉起已退出的进程，用户选择断开后也不得立即偷偷重连。

## 六 后续实施顺序

以下是建议的工作顺序，不代表本轮已执行：

### 阶段一 先处理启动策略和可观测性

- 在用户确认范围后，清理现有 Windows 计划任务及其脚本中的自动重启逻辑。
- 核查 VPS 上 TermDesk Relay 和 Tunnel 的实际启用状态，列明与禁令冲突的配置及对其他业务的影响，再按授权清理。
- 保留凭据、日志和现有代码改动，先做可回滚备份。
- 增加明确的手动启动、停止和状态检查路径，但不做隐藏驻留。

### 阶段二 固化 VPS 主链路

- 验证 Android → Cloudflare → VPS Relay → PC Agent 的全链路。
- 验证 PC 通过 v2rayN SOCKS 出站时的连接、断线、重连和节点状态。
- 把 Relay 的单节点单客户端限制、文件大小限制和错误码写入协议文档。
- 完成上文压力测试，再决定是否需要调整内存上限或并发限制。

### 阶段三 统一原生会话

- 先定义 `KernelSession`、原生身份、能力声明、历史游标和事件去重规则。
- Codex 先用当前代码已有的原生 thread resume 机制做真实闭环，不把代码路径存在视为续聊验收通过。
- DSH 先做独立的 session controller 最小探针，确认是否能跨进程 attach/resume；未确认前保持诚实的不可恢复状态。
- 将 `sessions.*` 的只读历史投影逐步收敛到统一会话接口，而不是继续给历史页单独增加输入框。

### 阶段四 Android 统一会话视图

- 历史、实时、离线缓存使用同一 `Conversation` 视图。
- 发送按钮由 `capabilities.canSend`、节点状态和会话附着状态决定是否可用。
- 历史打开后不再清空会话身份；若不能恢复，必须明确说明原因并保留只读状态。
- 真机验证绑定复用、离线导航、历史打开、继续对话、发送失败和断线补齐。

### 阶段五 扩展内核适配

- 以适配器注册表管理 Codex、DSH 和未来内核。
- `kernel.discover` 或等价能力接口返回安装、版本、可执行性、profile、健康状态和能力，而不是只判断文件是否存在。
- 新增内核时只实现适配器和事件映射，不复制 Android 页面。

## 七 验收标准

| 领域 | 验收标准 |
|---|---|
| VPS 主链路 | 手机不加入 Tailscale 也能经 VPS 访问；PC 只需主动出站连接 |
| 绑定 | 首次配对后，后续连接不重复输入地址和配对码；断开与忘记绑定行为不同 |
| 自动启动 | PC、Android、VPS 的 TermDesk 组件不注册开机或登录自启；不加入隐藏监督和异常自动重启方案 |
| PC 离线 | App 保留导航、缓存和明确状态；发送失败不伪造成功 |
| 历史会话 | Codex 可用原生身份继续；DSH 若不支持则明确显示能力，不伪造恢复 |
| 统一管线 | 历史、实时、缓存和恢复使用同一会话身份与事件模型 |
| 事件可靠性 | 重连后可按游标补齐，不重复、不丢失已确认事件 |
| 内核发现 | 能区分“文件存在”“可执行”“健康”“可恢复”和“支持取消”等能力 |
| VPS 性能 | 在目标并发和文件规模下，RSS、PSS、Swap、CPU 和连接数均有实测记录 |
| 安全 | Relay 不执行内核；令牌、设备凭据和节点密钥不写入报告、日志或客户端界面 |

## 八 当前不应采纳的结论

1. 不能因为已有 `chat.*` 就宣布历史和实时已经统一；`sessions.*` 与 `chat.*` 仍是两套生命周期。
2. 不能通过给历史页面补一个输入框来解决续聊；没有原生会话附着时，输入框只会制造伪恢复。
3. 不能把展示历史重新拼接到新 Prompt 中，作为成熟内核的会话恢复替代品。
4. 不能根据 AI 撰写的 `E:\aiPic\termdesk\documents\本地内核方案.md` 直接降低 targetSdk、引入 Termux 载荷或实施本地内核。该文件只能作为假设清单，所有决定性技术点都需要独立验证。
5. 不能把早先的待机内存样本当成 VPS 压力测试结果，也不能据此保证 Cloudflare 免费额度一定覆盖所有文件和历史场景。
6. 不能把当前代码里已经存在的凭据缓存、离线状态字段或静态测试，通过文字表述成真机闭环已验收。

## 九 待用户审阅的决策项

在开始修改前，只需要用户确认以下范围：

1. 现有自动运行配置的具体停止与清理范围，以及是否影响 VPS 上其他业务；禁令本身不需要重新确认，不自行缩小为 Windows 专属；
2. 清理现有自动运行配置时，是否允许停止并删除当前的 `TermDesk Agent` 计划任务；
3. 是否先只做 Codex 原生续聊闭环，再单独验证 DSH 跨进程恢复；
4. VPS 压力测试的目标并发、最大历史大小和文件传输规模；
5. 是否保留 Relay 的单手机限制，还是进入多设备设计。

这些是后续实施的决策项，不要求用户在本轮逐项作答。未获实施授权前，不进行代码合并、部署脚本执行、服务状态修改或第三方内核改造。


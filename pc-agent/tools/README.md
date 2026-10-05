# pc-agent tools

## 核心自检（日常跑这些）

需要代理已启动。**不要**给这些用例开 `--enable-shell`：

| 脚本 | 覆盖 |
|---|---|
| `check-symbols.js` | 协议/README/处理器一致性（无需代理，`npm test` 会跑） |
| `smoke.js` | P0 鉴权 + 状态推送 |
| `auth-negative.js` | 错误令牌 / 跳过鉴权 / 畸形帧 |
| `encoding-check.js` | 中文编码链路 |
| `inventory-test.js` | P1 进程/服务 + 保护名单 |
| `files-test.js` | P2 文件 + 路径逃逸防护 |
| `upload-chunk-test.js` | P5-4 分片上传（乱序、断点、拒绝路径） |
| `p2-e2e.js` | P2 真实链路 |

终端用例需要 `--enable-shell`，单独跑：

| 脚本 | 覆盖 |
|---|---|
| `terminal-test.js` | P3 终端（含 shell 关闭时的拒绝路径） |
| `p3-e2e.js` | P3 真实链路 |

AI 层（依赖本机 Codex / DSH 安装）：

| 脚本 | 覆盖 |
|---|---|
| `engines-test.js` | Codex / DSH headless 任务 |
| `chat-e2e.js` | DSH SDK 长连接会话 |
| `codex-config-test.js` | Codex 供应商配置读写 |
| `sessions-test.js` | 磁盘会话回放 |

侧栏缺陷取证（P5-1，只读诊断，需本地 DSH Web）：

| 脚本 | 作用 |
|---|---|
| `sidebar-inspect.js` | 手机宽度下测量侧栏与按钮 DOM |
| `sidebar-open-check.js` | 展开侧栏后检查收起入口是否可点 |
| `sidebar-why.js` | 打印按钮及祖先链 computed style |
| `sidebar-find-rule.js` | CDP CSS 域定位隐藏按钮的确切规则 |
| `sidebar-css.js` | 导出所有样式表原文 |
| `sidebar-owner.js` / `sidebar-diag.html` | 归属与离线对照 |

修复插件源码见 [`plugins/sidebar-unhide/`](../../plugins/sidebar-unhide/)。

## probes/（历史对照实验，不必跑）

设计终端、SDK、隧道时留下的探测脚本。结论已沉淀进源码注释：

- `pty-probe*.js` — 实测点源 `. {}` 才能跨命令保留变量/函数（5/5），调用运算符 `& {}` 不行（2/5）
- `sdk-probe.js` / `sdk-dump.js` — DSH SDK 协议握手与事件
- `session-share-probe.js` — 会话共享边界
- `tunnel-*.js` / `dsh-*-check.js` — 隧道与远程可用性
- `probe-*.ps1` — 进程/服务字段可读性

内核层（ACP / 内核清单，元数据优先、不调模型）：

| 脚本 | 覆盖 |
|---|---|
| `acp-live-test.js` | 逐个 ACP 内核握手：initialize / list / new / load |
| `acp-session-models.mjs` | 某个内核新会话能选的模型 id（例如 `node tools/acp-session-models.mjs opencode deepseek`） |
| `acp-model-list.mjs` | 走手机协议问同一件事（需要代理已启动） |
| `phone-acp-stub-e2e.mjs` | **整条手机链路**：假内核 + 真实服务进程 + 真实 WebSocket 帧（`npm test` 会跑，零成本） |
| `fake-acp-agent.mjs` | 上面的假内核本体；用 `TERMDESK_ACP_KERNELS` 注册，生产代码不知道它存在 |
# Agent 工具与测试

从仓库根目录执行命令。文件名带 test/e2e 不保证只用假助手；真实模型许可按[开发约束](../../documents/开发约束.md)，历史授权不自动延续。

## 默认回归

运行 `npm --prefix pc-agent test`，以 [package.json](../package.json) 命令链为准。覆盖语法、符号、协议、能力、状态、控制权、假 ACP/CLI、多客户端、通知和中转。需 relay 依赖；隧道配置用例要求与跳过项见[运行与开发](../../documents/运行与开发.md)。默认命令不应调用真实模型。

`fake-acp-agent.mjs`、`fake-cli-agent.mjs` 和 `lib/machine-token.mjs` 是测试支撑。`ui-demo-assistant.mjs` 是显式登记的固定回复助手，不能当真实模型能力演示。

## 连接现有 Agent

`npm --prefix pc-agent run test:live` 检查认证、状态、编码、会话读取与文件路径；`test:shell` 需启用 shell。它们连接实际环境，可能写测试文件或启动终端，应使用隔离目录与明确目标。

ACP 元数据工具为 `acp-live-test.js`、`acp-session-models.mjs`、`acp-model-list.mjs`；另有中转检查 `relay-public-check.mjs`。它们需对应助手/服务，不属于默认离线回归。

## 按需测试与维护

| 工具 | 用途与边界 |
|---|---|
| `chat-config-test.js` | 会话模型/推理参数静态夹具，保留，尚未接入默认命令 |
| `codex-config-test.js` | 隔离配置目录读写/恢复，不修改真实账号配置 |
| `acp-permission-roundtrip-test.mjs` | 假助手审批探针，含本机路径及打印式检查，需整理后才是可靠回归 |
| `acp-read-session-test.mjs` | 读取助手历史的诊断，需本机助手 |
| `cleanup-test-threads.js` | 预览/归档测试会话，本轮文件清理未执行 |
| `build-local-kernel.mjs` | 从已有 Termux tar 构建运行环境，原始输入必须保留 |
| `build-dsh-sandbox.mjs` | 构建手机 DSH 载荷，平台/版本条件见源码 |

`chat-e2e.js`、`chat-codex-appserver-test.js`、`codex-adapter-test.js`、`codex-ws-e2e.js`、`codex-toolcall-check.mjs` 和 `acp-wire-watch.mjs` 等含真实助手路径或真实回合。执行前读源码、确认目标与模型授权，不能因位于 tools 就自动运行。

## 外部界面与历史诊断

保留侧栏复验入口 `sidebar-open-check.js`、`sidebar-find-rule.js`，服务于[外部 DSH 插件](../../plugins/sidebar-unhide/README.md)，不属于 TermDesk 原生 UI。

SDK、恢复和隧道探针见 [probes 说明](probes/README.md)。2026-10-18 移除 18 个历史脚本，旧实现可查 v1.0.0 与[整理记录](../../documents/reviews/仓库整理-2026-10-18.md)。

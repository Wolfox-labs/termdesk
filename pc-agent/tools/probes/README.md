# 按需诊断探针

这些工具不是产品运行依赖，也不进入默认回归。从 `pc-agent` 目录运行，例如 `node tools/probes/session-handover-probe.mjs`。先阅读源码；真实模型路径须按[开发约束](../../../documents/开发约束.md)另行取得许可。

| 文件 | 用途 |
|---|---|
| `dsh-acp-resume-probe.mjs` | 核查 DSH ACP 恢复；默认不发 prompt，可选真实回合需许可 |
| `sdk-probe.js` | DSH SDK 多轮诊断，包含真实 prompt，不是免费静态测试 |
| `session-handover-probe.mjs` | 对现有 Agent 读取历史会话、核对接管信息 |
| `session-share-probe.js` | DSH 会话共享实验，执行前核查真实助手路径 |
| `sessions-live.js` | 现有 Agent 历史会话协议读取 |

早期五个 PTY 方案、一次性系统字段和旧 DSH Web/mux 探针已移除，结论可查 v1.0.0 Git 历史。保留探针仍有平台/环境假设，不代表已完成跨平台整理。

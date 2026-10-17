# TODO：会话连续性与 Agent 配置（2026-10-11）

> 历史材料，保留原规划与当日状态；不代表当前完成度。当前实现、能力边界和目标结构见[架构设计](架构设计.md)、[功能清单](功能清单.md)及[本轮验收](演示版验收-2026-10-17.md)。原文游标增量方案已被本轮按需全量快照方案取代。

## 当前切片

- [x] 审查 Codex、DSH、ACP、CLI shim 的会话恢复边界。
- [x] 审查模型、上下文长度、最大输出、模态和新模型接入链路。
- [x] 建立独立工作树和 `codex/termdesk-audit-fix` 分支。
- [x] 增加统一 Kernel manifest，并通过 `kernels` 帧下发。
- [x] 增加 live chat 的 `eventCursor`、`oldestEventSeq` 与 `continuity` 元信息。
- [x] Android 增加 manifest 与 continuity 的解析模型。
- [x] 增加 PC manifest 专项测试。
- [x] 增加 Android parser 单元测试。
- [x] 运行 Android 单元测试和 debug 构建。
- [ ] 连接 OPPO 真机，安装 APK 并回归关键会话/配置流程。

## P0：会话无缝衔接

- [ ] 建立持久化 conversation registry，统一 live chat 与磁盘 SessionInfo/SessionDetail。
- [ ] 增加 session lease、设备 owner、只读/接管/冲突状态。
- [ ] 实现 `chat.sync(chatId, afterSeq)` 的事件补发和完整快照回退。
- [ ] 为事件游标增加幂等去重和重复到达处理。
- [ ] 为 Codex、ACP 增加 PC Agent 重启后的端到端恢复测试。
- [ ] 对 DSH：实现 resume，或在产品上明确保持“历史可读、重启后不可继续”。

## P1：Agent 配置

- [ ] 统一 provider、model、effort、contextWindow、maxOutput、modalities、mode 的 schema 与作用域。
- [ ] 建立 provider/model registry，区分发现、启用、默认值和来源。
- [ ] 接通 max output 的真实请求参数和校验。
- [ ] 接通 input/output modalities 的模型能力发现与请求选择。
- [ ] 支持配置变更的 dry-run、错误回滚和生效范围提示。
- [ ] 设计新 provider/model 的导入、校验和版本兼容流程。

## P1：结构化与分包

- [ ] PC：拆分 `chat.js` 为 conversation manager、kernel adapters、event router、approval broker。
- [ ] PC：让 `handlers.js` 只做协议路由和校验。
- [ ] PC：拆分 `sessions.js` 的扫描、标准化、resume 判断。
- [ ] Android：按 chat/session/config/files/terminal feature 拆分 repository 和 UI 状态。
- [ ] Android：让 `AgentClient` 只保留连接、请求和 StateFlow 聚合。
- [ ] 为 manifest、session sync、config mutation 建立版本化协议。

## P2：体验与验证

- [ ] 配置页显示“当前值、支持状态、生效范围、失败原因”，不显示无效编辑控件。
- [ ] 历史会话区分“可继续”“只能查看”“需要接管”。
- [ ] 断线重连时显示同步进度和缺失事件提示。
- [ ] 真机验证 OPPO 的旋转、后台恢复、弱网、重复点击和键盘遮挡。
- [ ] 将 PC 专项测试、Android 单测、真机构建和回归步骤写入 CI/发布清单。

# 手机端沙盒：Android 16 实测与 DSH「空回合」根因修复

> 记录日期：2026-10-06
> 设备：vivo V2548A（PD2548）· Android 16 / SDK 36 · arm64-v8a
> 应用：`dev.termdesk.app` 0.3.0-demo（targetSdk 28，debug 构建）
> 性质：**全部实测**。每条结论都附了复现方式，未验证的地方明确标注。

---

## 0 结论速览

| # | 结论 | 状态 |
|---|---|---|
| 1 | targetSdk 28 的 execve 在 **Android 16 上依然可用**（此前只在 Android 13 验证过） | ✅ 实测 |
| 2 | 沙盒可完整安装、解包、补符号链接、健康检查、跑 bash/python3/node | ✅ 实测 |
| 3 | 前台服务保活：App 切后台 **5 分钟以上**，沙盒任务不中断 | ✅ 实测 |
| 4 | **DSH「空回合」的根因是 Android 禁止 App 数据目录建硬链接** | ✅ 已定位并修复 |
| 5 | DSH 的凭据文件权限必须是 **600**，666 会被拒绝启动 | ✅ 实测 |
| 6 | `mimo-v2.6-flash` 在当前中转上**不返回**（45 s 超时，HTTP 000）；`spe/deepseek-v4.1-flash` 正常 | ✅ 实测 |
| 7 | 主页在「本机（沙盒）」模式下**从不渲染内核列表**（已修） | ✅ 已修 |

---

## 1 execve 在 Android 16 上仍然可行（最重要的风险排除）

`documents/本地内核.md` §3.1 的结论此前只在 Android 13 上验证。本次在新设备、Android 16 上重测：

- 载荷 `bootstrap-v1.tar.gz`（sha256 `76da67c4…`）下载、校验、解包成功；
- `files/local-kernel-installed.json` 记录 `health: "termdesk-local-kernel-ok"`，
  说明**健康检查在 App 进程内真的执行了沙盒里的 bash**；
- 终端实测：`uname -a` → `6.12.58-android16-…-abogki521987229-4k … aarch64 Android`，
  `pwd` → `/data/user/0/dev.termdesk.app/files/home`，`python3 -V` → `Python 3.14.6`。

**复现**：安装 APK → 连接电脑端代理 → 设置 → 内核 → 本机（沙盒）→ 安装本地内核。

---

## 2 DSH「空回合」的根因：Android 不允许 App 数据目录创建硬链接

### 2.1 现象

沙盒里 DSH 会话能创建、回合会走完，但**不产生任何正文**，界面只显示：

```
──── 完成 · started ────
──── 完成 · ended ────
```

### 2.2 定位过程

在沙盒里用 `@deepseek-ai/dsh-sdk-protocol` 手动驱动一轮（脚本见 §4），拿到完整 `turn/end`：

```
turn/end  reason: { kind: "error",
  error: { message: "EACCES: permission denied,
    link '.../files/home/.dsh/sessions/--data-user-0-dev.termdesk.app-files-home--/probe-…'" } }
```

随后直接验证硬链接是否可用（App 上下文内）：

```sh
cd /data/user/0/dev.termdesk.app/files/home
echo hi > hl_a.txt
ln hl_a.txt hl_b.txt        # → ln exit=1
                            #   cannot create hard link …: Permission denied
```

**写入普通文件正常，创建硬链接被拒绝。** 这是 Android 的安全策略
（SELinux `untrusted_app` 不允许在 App 数据目录内 `link`），不是权限位或路径写错。

### 2.3 DSH 为什么会用到硬链接

`@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js` 的 `materializePosix()`：
先把会话日志写进临时文件，再用 `link(tmp, finalPath)` 把最终文件"原子发布"出来
（`link` 天然带"目标已存在就失败"的语义，作为 TOCTOU 保护）。

Android 上这一步必然失败，整个回合因此以 error 收尾 —— 而 App 把 error 后的空内容
渲染成了"完成"，所以看起来像"跑完了但没说话"。

### 2.4 修复

同一文件系统上 `rename()` 同样原子、且 Android 允许。

```diff
-import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";
+import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rename, rm, stat, truncate } from "node:fs/promises";

-			await link(tmp, finalPath);
+			// Android denies hard links inside an app's private data dir
+			// (SELinux untrusted_app cannot link in app data), so publish with
+			// rename instead. rename is atomic on the same filesystem; the
+			// rejectExistingLog() check above still guards create-new.
+			await rename(tmp, finalPath);
```

目标文件（沙盒内）：
`files/home/.dsh/profiles/sdk/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js`

修复后同一探针完整跑通：

```
assistant/chunk  reasoning-delta "The user wants me to reply with exactly PROBE-OK."
assistant/chunk  text-delta "PROBE-OK"
assistant/message  content: [reasoning, text "PROBE-OK"]
turn/end  reason: { kind: "completed" }
session.status  idle
```

App 界面上同样成功（`SANDBOX-OK`）。

> **注意**：这是对第三方包（DSH）的运行时补丁。若重新分发 `.dsh` 载荷，
> 必须在构建脚本 `build-dsh-sandbox.mjs` 里加上这一步，否则新设备会重现同一问题。

---

## 3 另外两个必修项

### 3.1 凭据文件必须是 600

网关凭据 `.dsh/.credentials.yaml` 若权限不是 600，DSH 会拒绝启动：

```
dsh: 1 entry did not activate
@deepseek-ai/dsh-credentials-local: Error: credentials-local:
  ….credentials.yaml is readable beyond its owner (mode 666);
  run "chmod 600 …" before starting again
```

用 `cat > file` 写入时会被 umask 设成 666，**写完必须 `chmod 600`**。

### 3.2 默认模型要选能用的

| 模型 | 实测 |
|---|---|
| `spe/deepseek-v4.1-flash` | ✅ HTTP 200，正常返回 |
| `mimo-v2.6-flash` | ❌ 45 s 超时，HTTP 000 |

`android/.../data/LocalAgent.kt` 里沙盒默认模型原为 `mimo-v2.6-flash`，
已改为 `spe/deepseek-v4.1-flash`（与桌面端默认一致）。

---

## 4 复现用的探针

把下面这段存成 `files/home/sdkprobe.mjs`，在沙盒里用沙盒的 node 运行即可拿到逐步事件：

```
node "$HOME/sdkprobe.mjs"
```

关键环境变量（由 `LocalAgent.env()` 注入）：

```
PREFIX=/data/user/0/dev.termdesk.app/files/usr
PATH=$PREFIX/bin:$PREFIX/bin/applets:/system/bin
LD_LIBRARY_PATH=$PREFIX/lib
HOME=/data/user/0/dev.termdesk.app/files/home
DSH_HOME=$HOME/.dsh
TERMDESK_DSH=$HOME/.dsh/profiles/sdk/node_modules/@deepseek-ai/dsh/lib/bin.js
TERMDESK_CHAT_PROVIDER=wolfox
TERMDESK_CHAT_MODEL=spe/deepseek-v4.1-flash
WOLFOX_API_KEY=<来自 .credentials.yaml>
```

协议：一行一个 JSON-RPC 2.0 消息；`initialize` → `session/prompt`；
服务端推 `session.event` / `session.status`。参考实现
`pc-agent/tools/probes/sdk-probe.js`。

---

## 5 本次改动的代码

| 文件 | 改动 |
|---|---|
| `android/.../data/LocalAgentService.kt` | 新增：前台服务（常驻通知 + 停止按钮），防止后台被杀 |
| `android/.../res/drawable/ic_termdesk_notify.xml` | 新增：通知图标 |
| `android/app/src/main/AndroidManifest.xml` | 新增 `FOREGROUND_SERVICE` 等权限 + 服务声明 |
| `android/.../data/LocalAgent.kt` | 启动/停止联动前台服务；默认模型改为可用模型 |
| `android/.../ui/HomeSection.kt` | 修复：本机（沙盒）模式下现在会渲染沙盒的内核列表 |
| `.gitignore` | 忽略 172 MB 的分包 zip（超出 Gitee 单文件限制） |
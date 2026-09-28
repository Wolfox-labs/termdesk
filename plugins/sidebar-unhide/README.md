# dsh-sidebar-unhide

修复 P5-1：手机浏览器里 DSH Web 左侧栏**收不回去**。

## 问题

`dsh-tauri` 插件无条件隐藏了网页里的收起按钮：

```js
CssRender().css([
  t('button[aria-label="收起侧边栏"], button[aria-label="Collapse sidebar"]',
    { display: 'none !important' }),
])
```

设计意图：Tauri 桌面外壳的标题栏图标通过 `postMessage` 发
`dsh://sidebar:toggle` 来收起侧栏，网页按钮只是重复入口。

缺陷：手机浏览器没有 Tauri 外壳，没人发那条消息，按钮又被藏了 → 死锁。

## 修法

本插件是 DSH **client 插件**，只在**检测不到 Tauri 外壳**时注入一条更高优先级的
CSS，把按钮还原为 `inline-flex`：

| 检测信号 | 含义 |
|---|---|
| `window.__TAURI__` / `__TAURI_INTERNALS__` / `__TAURI_IPC__` | Tauri 运行时存在 |
| `window.parent !== window` | 被外壳 iframe 包着，不抢控制权 |

桌面 App 内本插件是 no-op，不会和外壳抢按钮。

## 安装（本机一次性）

把本目录复制到 DSH 的 `local-plugins`，再在 profile 的 patch 层声明：

```powershell
# 1. 复制插件（源码在本仓库，安装副本在 ~/.dsh）
Copy-Item -Recurse -Force `
  "plugins\sidebar-unhide" `
  "$env:USERPROFILE\.dsh\local-plugins\sidebar-unhide"

# 2. 追加到 ~/.dsh/profiles/<profile>/cordis.patch.yml（home 层，桌面 App 不覆盖）
#    若文件已存在，在 YAML 数组末尾追加下面这段；不存在则创建。
```

`cordis.patch.yml` 追加内容（也可直接用本目录的 `cordis.patch.yml` 整文件覆盖
空补丁层）：

```yaml
- insert:
    - id: sidebar-unhide
      name: dsh-sidebar-unhide
```

> 该文件是 **YAML 数组**。已有补丁时请追加数组元素，不要整文件替换，以免丢掉
> `trustedHosts` 等既有部署配置。

3. 重启 DSH（或重启桌面 App），手机浏览器强制刷新页面。

## 验证

```bash
# 1. 手机宽度下打开侧栏，确认收起按钮可点
node pc-agent/tools/sidebar-open-check.js https://<your-dsh-host>/ 412 915

# 2. 级联里应看到本插件的还原规则压过 dsh-tauri 的隐藏规则
node pc-agent/tools/sidebar-find-rule.js https://<your-dsh-host>/ 412 915
```

本地无 DSH 时，可先用静态自检：

```bash
node plugins/sidebar-unhide/test-unhide.mjs
```

## 文件

| 文件 | 作用 |
|---|---|
| `package.json` | DSH 插件清单（`dsh.client` / `dsh.bundle.patch`） |
| `cordis.patch.yml` | 把本插件 insert 进 loader |
| `lib/index.js` | 服务端空实现（修复全在客户端） |
| `lib/client.js` | Tauri 检测 + CSS 注入 |
| `test-unhide.mjs` | 无 DSH 时的静态自检 |

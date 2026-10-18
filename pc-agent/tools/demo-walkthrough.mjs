/** Reproducible UI evidence: isolated Agent, synthetic assistant, explicit Android target. */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import net from 'node:net';
import { spawn, spawnSync, execFileSync } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import WebSocket from 'ws';

const repo = path.resolve(fileURLToPath(new URL('../..', import.meta.url)));
const args = process.argv.slice(2);
const option = (name, fallback) => { const i = args.indexOf(`--${name}`); return i < 0 ? fallback : args[i + 1]; };
const phase = option('phase', 'run');
const adb = option('adb', process.env.ANDROID_HOME ? path.join(process.env.ANDROID_HOME, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb') : 'adb');
const serial = option('serial');
if (!serial) throw new Error('Specify --serial; this tool never selects the first connected device.');
const privateDir = path.resolve(option('private', path.join(repo, '.local-notes', 'report-demo')));
const output = path.resolve(option('output', path.join(repo, 'output', 'demo')));
fs.mkdirSync(privateDir, { recursive: true });
fs.mkdirSync(output, { recursive: true });
const stateFile = path.join(privateDir, 'session.json');
const app = 'dev.termdesk.app';
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
function device(parts, input) {
  try { return execFileSync(adb, ['-s', serial, ...parts], { input, maxBuffer: 24 * 1024 * 1024, timeout: 25000, windowsHide: true }); }
  catch { throw new Error(`ADB operation failed: ${parts.slice(0, 3).join(' ')}; inspect the selected device.`); }
}
const shell = (...parts) => device(['shell', ...parts]).toString().trim();
const readState = () => { const s = JSON.parse(fs.readFileSync(stateFile)); if (s.serial !== serial) throw new Error('Session belongs to another Android device'); return s; };
const writeState = s => fs.writeFileSync(stateFile, JSON.stringify(s, null, 2));
const frameLog = [];
let snapshot = [];
function xml() {
  shell('uiautomator', 'dump', '/sdcard/termdesk-report-ui.xml');
  const raw = device(['exec-out', 'cat', '/sdcard/termdesk-report-ui.xml']).toString();
  fs.writeFileSync(path.join(privateDir, 'latest-ui.xml'), raw);
  const decode = s => s.replaceAll('&amp;', '&').replaceAll('&quot;', '"').replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&#10;', '\n');
  snapshot = [...raw.matchAll(/<node\s+([^>]+)>?/g)].map(match => Object.fromEntries([...match[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(m => [m[1], decode(m[2])])));
  return snapshot;
}
function locate(text, nodes = xml()) { return nodes.find(n => (n.text === text || n['content-desc'] === text) && n.bounds !== '[0,0][0,0]'); }
async function tap(text) {
  const n = locate(text); if (!n) throw new Error(`UI control absent: ${text}`);
  const b = [...n.bounds.matchAll(/\d+/g)].map(x => Number(x[0]));
  shell('input', 'tap', String(Math.round((b[0] + b[2]) / 2)), String(Math.round((b[1] + b[3]) / 2)));
  await wait(650);
}
async function enter(value, replace = false) {
  if (!/^[\x20-\x7E]+$/.test(value)) throw new Error('ADB text entry accepts ASCII fixture text only');
  const field = xml().find(n => n.class === 'android.widget.EditText' && n.bounds !== '[0,0][0,0]');
  if (!field) throw new Error('No observed editable field');
  const b = [...field.bounds.matchAll(/\d+/g)].map(x => Number(x[0]));
  shell('input', 'tap', String((b[0] + b[2]) >> 1), String((b[1] + b[3]) >> 1));
  if (replace) shell('input', 'keycombination', '113', '29');
  // Quote remote shell arguments; the fixture does not use quotes or percent signs.
  if (/['"%]/.test(value)) throw new Error('Unsupported fixture text');
  shell('input', 'text', `'${value.replaceAll(' ', '%s')}'`);
  await wait(350);
}
async function expect(text, timeout = 18000) {
  const until = Date.now() + timeout;
  while (Date.now() < until) { if (xml().some(n => `${n.text} ${n['content-desc']}`.includes(text))) return; await wait(300); }
  throw new Error(`UI expectation not met: ${text}`);
}
async function capture(name, description) {
  const file = `${name}.png`;
  fs.writeFileSync(path.join(output, file), device(['exec-out', 'screencap', '-p']));
  const s = readState(); s.scenes ??= []; s.scenes.push({ file, description, at: new Date().toISOString() }); writeState(s);
  console.log(`Captured ${file}: ${description}`);
}
async function connect(s, kind = 'desktop') {
  const ws = new WebSocket(`ws://127.0.0.1:${s.port}`);
  ws.on('message', data => frameLog.push(JSON.parse(data.toString())));
  await once(ws, 'open'); ws.send(JSON.stringify({ type: 'auth', token: s.master, deviceId: 'report-observer', deviceName: 'Report observer', clientKind: kind, v: 1 }));
  for (let n = 0; n < 100 && !frameLog.some(f => f.type === 'auth.ok'); n++) await wait(50);
  if (!frameLog.some(f => f.type === 'auth.ok')) throw new Error('Demo observer authentication failed');
  return ws;
}
async function prepare() {
  if (fs.existsSync(stateFile) && !readState().cleaned) throw new Error('An unfinished demo session exists; use --phase cleanup before preparing again.');
  if (device(['get-state']).toString().trim() !== 'device') throw new Error('Target is unavailable');
  // The APK must already be installed with a matching signature; this tool never uninstalls it.
  shell('am', 'force-stop', app);
  // The app must have been opened once and initialized its local storage.
  if (shell('run-as', app, 'sh', '-c', "'test -d shared_prefs && echo ready'") !== 'ready') {
    throw new Error('Open the debug app once before preparing; preferences must exist for a restorable demo.');
  }
  if (shell('run-as', app, 'sh', '-c', "'if test -d files/pending-sends; then echo present; fi'") !== 'present') {
    throw new Error('This walkthrough requires initialized files/pending-sends storage; no preferences were changed.');
  }
  const backup = device(['exec-out', 'run-as', app, 'tar', '-cf', '-', 'shared_prefs', 'files/pending-sends']);
  fs.writeFileSync(path.join(privateDir, 'phone-preferences.tar'), backup);
  const beforeHash = shell('run-as', app, 'sh', '-c', "'find shared_prefs -type f -exec sha256sum {} \\;'" );
  const home = path.join(privateDir, 'home'); const work = path.join(privateDir, 'workspace');
  fs.mkdirSync(path.join(home, '.termdesk'), { recursive: true }); fs.mkdirSync(work, { recursive: true });
  const master = crypto.randomBytes(32).toString('base64url'); fs.writeFileSync(path.join(home, '.termdesk', 'token'), master);
  fs.writeFileSync(path.join(work, 'brief.txt'), 'TermDesk demonstration\nA real phone, isolated files and synthetic assistant output.\n');
  fs.writeFileSync(path.join(work, 'checklist.md'), '# Demo checklist\n\n- Conversation\n- File edit\n- Terminal\n- Settings\n');
  const listener = net.createServer(); listener.listen(0, '127.0.0.1'); await once(listener, 'listening'); const port = listener.address().port; await new Promise(resolve => listener.close(resolve));
  const missing = path.join(home, 'not-installed');
  const env = { ...process.env, HOME: home, USERPROFILE: home, TERMDESK_ROOTS: work, TERMDESK_RELAY: '0', TERMDESK_KERNELS_PROBE: '0',
    TERMDESK_CODEX: missing, TERMDESK_DSH: missing, TERMDESK_OPENCODE: missing, TERMDESK_MIMO: missing, TERMDESK_QODER: missing, TERMDESK_COMMAND_CODE: missing,
    TERMDESK_KERNEL_MANIFEST_DIR: path.join(home, 'kernels'), TERMDESK_UI_DEMO_STORE: path.join(home, 'ui-demo.json'),
    TERMDESK_CLI_KERNELS: JSON.stringify([{ id: 'report-demo', label: '交互演示（无模型）', bin: process.execPath, preArgs: [path.join(repo, 'pc-agent/tools/ui-demo-assistant.mjs')],
      shim: { newArgs: ['--print'], resumeArgs: ['--print', '--resume'], listArgs: ['--list-sessions'], sessionTitle: 'title', sessionCwd: 'cwd', sessionUpdatedAt: 'updated_at' } }]), TERMDESK_ACP_KERNELS: '[]' };
  const log = fs.openSync(path.join(privateDir, 'agent-private.log'), 'a');
  const server = spawn(process.execPath, [path.join(repo, 'pc-agent/src/server.js'), '--host', '127.0.0.1', '--port', String(port), '--enable-shell'], { env, cwd: path.join(repo, 'pc-agent'), stdio: ['ignore', log, log], detached: true, windowsHide: true }); server.unref(); fs.closeSync(log);
  const s = { serial, home, work, master, port, pid: server.pid, beforeHash, startedAt: new Date().toISOString(), scenes: [], cleaned: false }; writeState(s);
  for (let i = 0; i < 150; i++) { try { if ((await fetch(`http://127.0.0.1:${port}/healthz`)).ok) break; } catch {} await wait(100); }
  device(['reverse', `tcp:${port}`, `tcp:${port}`]);
  const pair = await (await fetch(`http://127.0.0.1:${port}/pair.json`)).json();
  const uri = `termdesk://pair?url=${encodeURIComponent(`ws://127.0.0.1:${port}`)}&token=${encodeURIComponent(pair.token)}&name=${encodeURIComponent('项目演示电脑')}`;
  shell('am', 'start', '-W', '-n', `${app}/.MainActivity`, '-a', 'android.intent.action.VIEW', '-d', `'${uri}'`);
  await wait(2400);
  console.log(`Demo ready on loopback port ${port}; isolated workspace and original preferences preserved privately.`);
}
async function walkthrough() {
  const s = readState(); const observer = await connect(s);
  let recorder;
  let recorderDone;
  try {
    await expect('工作台');
    if (!locate('新建会话', snapshot)) await tap('工作台');
    if (locate('关闭', xml())) await tap('关闭');
    await tap('远程电脑');
    await expect('交互演示（无模型）');
    await tap('选择工作目录'); await enter(s.work.replaceAll('\\', '/'), true); shell('input', 'keyevent', 'KEYCODE_BACK'); await tap('选择目录');
    await expect('新建会话');
    const scrcpy = option('scrcpy');
    if (scrcpy) {
      const video = path.resolve(option('video', path.join(output, 'walkthrough.mp4')));
      fs.mkdirSync(path.dirname(video), { recursive: true });
      if (fs.existsSync(video)) throw new Error('Video output already exists; choose a new --video name.');
      const recordLog = fs.openSync(path.join(privateDir, 'recording.log'), 'a');
      recorder = spawn(scrcpy, ['--serial', serial, '--no-audio', '--no-playback', '--no-window', '--no-control', '--max-size=1600', '--max-fps=30', '--video-bit-rate=3M', '--time-limit=120', `--record=${video}`], { windowsHide: true, stdio: ['ignore', recordLog, recordLog] });
      recorderDone = once(recorder, 'exit'); fs.closeSync(recordLog);
      await wait(1600);
      if (recorder.exitCode !== null) throw new Error('Device recording failed; inspect private recording.log');
    }
    await capture('phone-home', '手机工作台：已连接隔离电脑与固定回复助手');
    await tap('新建会话'); await expect('输入消息');
    await enter('Please show the project walkthrough.'); await tap('发送');
    await expect('未调用模型'); await wait(2200);
    await capture('phone-chat', '真实协议流式回复；内容由固定回复助手生成');
    shell('input', 'keyevent', 'KEYCODE_BACK'); await wait(600);
    if (!locate('文件', xml())) shell('input', 'keyevent', 'KEYCODE_BACK');
    await tap('文件'); await expect('brief.txt'); await capture('phone-files', '隔离项目目录及文件列表');
    await tap('brief.txt'); await expect('保存');
    await enter(' Edited on the test phone.'); shell('input', 'keyevent', 'KEYCODE_BACK');
    await capture('phone-editor', '文本编辑器与保存操作');
    await tap('保存'); await expect('已保存');
    if (!fs.readFileSync(path.join(s.work, 'brief.txt'), 'utf8').includes('Edited on the test phone.')) throw new Error('Phone save did not reach actual host file');
    await tap('返回'); await tap('终端'); await wait(1000);
    if (locate('新建终端', xml())) await tap('新建终端');
    await expect('输入命令'); await enter('Write-Output TERMDESK_DEMO_OK'); await tap('执行'); await expect('TERMDESK_DEMO_OK');
    await capture('phone-terminal', '实际电脑 shell 输出，与合成助手分开验证');
    shell('input', 'keyevent', 'KEYCODE_BACK'); await wait(300); await tap('工作台'); await tap('分区'); await tap('设置');
    await expect('运行设备与助手');
    if (xml().some(n => n.text === '工作台' || n.text === '终端')) throw new Error('Settings still displays primary navigation');
    await capture('phone-settings', '设置页面无底部主导航，旧 Codex 配置入口隐藏');
    s.completedAt = new Date().toISOString(); s.protocolEvents = frameLog.filter(f => f.type === 'chat.event').length; s.verifiedFileWrite = true;
    const current = readState(); Object.assign(current, { completedAt: s.completedAt, protocolEvents: s.protocolEvents, verifiedFileWrite: true }); writeState(current);
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ startedAt: s.startedAt, completedAt: s.completedAt, device: shell('getprop', 'ro.product.model'), android: shell('getprop', 'ro.build.version.release'), sourceVersion: JSON.parse(fs.readFileSync(path.join(repo, 'pc-agent/package.json'))).version, modelCalls: 0, protocolEvents: s.protocolEvents, verifiedFileWrite: true, scenes: current.scenes }, null, 2));
    console.log(`UI walkthrough passed; observer received ${s.protocolEvents} chat events and file write was verified on disk.`);
  } catch (error) {
    fs.writeFileSync(path.join(privateDir, 'failure.png'), device(['exec-out', 'screencap', '-p']));
    throw error;
  } finally {
    observer.close();
    // A device-timed recording finishes cleanly before user preferences are restored.
    if (recorderDone) await recorderDone;
  }
}
async function cleanup() {
  const s = readState(); shell('am', 'force-stop', app);
  device(['shell', 'run-as', app, 'tar', '-xf', '-'], fs.readFileSync(path.join(privateDir, 'phone-preferences.tar')));
  const after = shell('run-as', app, 'sh', '-c', "'find shared_prefs -type f -exec sha256sum {} \\;'" );
  if (after !== s.beforeHash) throw new Error('Restored preference hashes differ; leave private backup intact and review.');
  device(['reverse', '--remove', `tcp:${s.port}`]);
  // Verify process identity before terminating the test server tree; never kill by image name.
  if (process.platform === 'win32') {
    const ps = `(Get-CimInstance Win32_Process -Filter "ProcessId = ${Number(s.pid)}").CommandLine`;
    const line = spawnSync('powershell.exe', ['-NoProfile', '-Command', ps], { encoding: 'utf8', windowsHide: true }).stdout || '';
    if (line.includes(path.join(repo, 'pc-agent/src/server.js')) && line.includes(String(s.port))) spawnSync('taskkill.exe', ['/PID', String(s.pid), '/T', '/F'], { windowsHide: true });
    else throw new Error('Demo server identity changed; refusing to terminate another process');
  } else process.kill(s.pid, 'SIGTERM');
  s.cleaned = true; s.preferencesRestored = true; writeState(s);
  console.log('Original preference hashes restored; only the owned test server and its reverse mapping stopped.');
}

try {
  if (phase === 'prepare') await prepare();
  else if (phase === 'walkthrough') await walkthrough();
  else if (phase === 'cleanup') await cleanup();
  else if (phase === 'observe') { console.log(xml().filter(n => n.text || n['content-desc']).map(n => ({ text: n.text, description: n['content-desc'], bounds: n.bounds, class: n.class }))); }
  else if (phase === 'tap') await tap(option('text'));
  else if (phase === 'capture') await capture(option('name'), option('description', 'manual checkpoint in the real application'));
  else if (phase === 'run') { await prepare(); try { await walkthrough(); } finally { await cleanup(); } }
  else throw new Error('Use --phase run, prepare, walkthrough, observe, tap, capture or cleanup');
} catch (error) { console.error(error.message); process.exitCode = 1; }

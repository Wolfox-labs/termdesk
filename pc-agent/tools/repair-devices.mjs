// 修一次 devices.json：名字在某个环节被 GBK 误解码又截断，连引号都丢了，
// 于是整份 JSON 不再是合法 JSON，注册表读出来就是 0 台。
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const file = path.join(os.homedir(), '.termdesk', 'devices.json');
const raw = fs.readFileSync(file, 'utf8');
fs.writeFileSync(`${file}.bak-before-repair`, raw, 'utf8');

// 坏名字长这样：  "name": "寰呭懡鍚嶆墜鏈?,
// 引号被吃掉，所以只匹配到逗号前。
const fixed = raw.replace(/"name":\s*"[^"]*\?,/g, '"name": "待命名手机",');

try {
  const data = JSON.parse(fixed);
  fs.writeFileSync(file, fixed, 'utf8');
  console.log(`修好了：${data.devices.length} 台设备`);
  for (const d of data.devices) {
    console.log(`  ${d.id}  ${d.name}  ${d.revoked ? '(已吊销)' : ''}`);
  }
} catch (err) {
  console.error(`还是解析不了：${err.message}`);
  process.exit(1);
}

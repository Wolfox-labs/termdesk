/**
 * P1 checks for process/service inventory and privileged actions.
 *
 * Starts the agent on a scratch port so it never disturbs a running instance.
 *
 *   node tools/inventory-test.js
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';
import { machineTokenOrSkip } from './lib/machine-token.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const AGENT = path.join(__dirname, '..', 'src', 'server.js');
const PORT = Number(process.env.TERMDESK_TEST_PORT || 7441);
const token = machineTokenOrSkip('inventory-test');

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed, detail });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

// --- start a scratch agent -------------------------------------------------
const agent = spawn(process.execPath, [AGENT, '--port', String(PORT)], {
  cwd: path.join(__dirname, '..'),
  stdio: ['ignore', 'pipe', 'pipe'],
});
agent.stdout.on('data', () => {});
agent.stderr.on('data', (d) => process.stderr.write(`[agent] ${d}`));

await new Promise((r) => setTimeout(r, 2500));

// --- helper: one request/response conversation -----------------------------
function session(handler) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
    const timer = setTimeout(() => {
      ws.close();
      reject(new Error('timeout'));
    }, 60000);
    ws.on('open', () => ws.send(JSON.stringify({ type: 'auth', token })));
    ws.on('message', async (raw) => {
      const frame = JSON.parse(raw.toString());
      if (frame.type === 'auth.ok') {
        try {
          const done = await handler(ws, frame);
          if (done) {
            clearTimeout(timer);
            ws.close();
            resolve();
          }
        } catch (err) {
          clearTimeout(timer);
          ws.close();
          reject(err);
        }
      }
    });
    ws.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

const send = (ws, obj) => ws.send(JSON.stringify(obj));

try {
  // --- processes -----------------------------------------------------------
  const procs = await new Promise((resolve, reject) => {
    session((ws) => {
      send(ws, { type: 'procs.list' });
      return new Promise((res, rej) => {
        const t = setTimeout(() => rej(new Error('no procs frame')), 45000);
        const onMsg = (raw) => {
          const f = JSON.parse(raw.toString());
          if (f.type === 'procs') {
            clearTimeout(t);
            ws.off('message', onMsg);
            res(f);
          }
          if (f.type === 'error') {
            clearTimeout(t);
            rej(new Error(f.message));
          }
        };
        ws.on('message', onMsg);
      }).then((f) => {
        resolve(f);
        return true;
      });
    }).catch(reject);
  });

  check('procs frame received', Array.isArray(procs.items), `${procs.items.length} items`);
  check('procs total reported', typeof procs.total === 'number' && procs.total > 0, `total=${procs.total}`);
  check('procs sorted by memory desc', (() => {
    for (let i = 1; i < Math.min(procs.items.length, 50); i += 1) {
      if (procs.items[i].memBytes > procs.items[i - 1].memBytes) return false;
    }
    return true;
  })());
  check('procs names non-empty', procs.items.every((p) => typeof p.name === 'string' && p.name.length > 0));

  // --- services ------------------------------------------------------------
  const svcs = await new Promise((resolve, reject) => {
    session((ws) => {
      send(ws, { type: 'services.list' });
      return new Promise((res, rej) => {
        const t = setTimeout(() => rej(new Error('no services frame')), 45000);
        const onMsg = (raw) => {
          const f = JSON.parse(raw.toString());
          if (f.type === 'services') {
            clearTimeout(t);
            ws.off('message', onMsg);
            res(f);
          }
          if (f.type === 'error') {
            clearTimeout(t);
            rej(new Error(f.message));
          }
        };
        ws.on('message', onMsg);
      }).then((f) => {
        resolve(f);
        return true;
      });
    }).catch(reject);
  });

  check('services frame received', Array.isArray(svcs.items), `${svcs.items.length} items`);
  check('services have displayName', svcs.items.every((s) => typeof s.displayName === 'string'));

  // --- protection: must refuse to kill critical processes ------------------
  const protectedCases = ['explorer', 'lsass'];
  for (const name of protectedCases) {
    const target = procs.items.find((p) => p.name.toLowerCase() === name);
    if (!target) {
      check(`refuses to kill ${name}`, true, 'skipped: process not running');
      continue;
    }
    const res = await new Promise((resolve, reject) => {
      session((ws) => {
        send(ws, { type: 'procs.kill', pid: target.pid });
        return new Promise((res2, rej2) => {
          const t = setTimeout(() => rej2(new Error('no action.result')), 30000);
          const onMsg = (raw) => {
            const f = JSON.parse(raw.toString());
            if (f.type === 'action.result') {
              clearTimeout(t);
              ws.off('message', onMsg);
              res2(f);
            }
          };
          ws.on('message', onMsg);
        }).then((f) => {
          resolve(f);
          return true;
        });
      }).catch(reject);
    });
    check(`refuses to kill ${name}`, res.ok === false && res.code === 'protected', res.message);
  }

  // --- the agent must refuse to kill itself --------------------------------
  const selfKill = await new Promise((resolve, reject) => {
    session((ws) => {
      send(ws, { type: 'procs.kill', pid: agent.pid });
      return new Promise((res2, rej2) => {
        const t = setTimeout(() => rej2(new Error('no action.result')), 30000);
        const onMsg = (raw) => {
          const f = JSON.parse(raw.toString());
          if (f.type === 'action.result') {
            clearTimeout(t);
            ws.off('message', onMsg);
            res2(f);
          }
        };
        ws.on('message', onMsg);
      }).then((f) => {
        resolve(f);
        return true;
      });
    }).catch(reject);
  });
  check('refuses to kill its own agent process', selfKill.ok === false && selfKill.code === 'protected', selfKill.message);

  // --- killing a disposable process must actually work ---------------------
  const victim = spawn(process.execPath, ['-e', 'setTimeout(()=>{},120000)'], { stdio: 'ignore' });
  await new Promise((r) => setTimeout(r, 1200));
  const victimPid = victim.pid;

  const killRes = await new Promise((resolve, reject) => {
    session((ws) => {
      send(ws, { type: 'procs.kill', pid: victimPid });
      return new Promise((res2, rej2) => {
        const t = setTimeout(() => rej2(new Error('no action.result')), 30000);
        const onMsg = (raw) => {
          const f = JSON.parse(raw.toString());
          if (f.type === 'action.result') {
            clearTimeout(t);
            ws.off('message', onMsg);
            res2(f);
          }
        };
        ws.on('message', onMsg);
      }).then((f) => {
        resolve(f);
        return true;
      });
    }).catch(reject);
  });

  const stillAlive = (() => {
    try { process.kill(victimPid, 0); return true; } catch { return false; }
  })();

  check('kill disposable process succeeds', killRes.ok === true, killRes.message);
  check('killed process is really gone', stillAlive === false);

  // --- protection: must refuse to stop a critical service ------------------
  const svcRes = await new Promise((resolve, reject) => {
    session((ws) => {
      send(ws, { type: 'services.action', name: 'Dnscache', action: 'stop' });
      return new Promise((res2, rej2) => {
        const t = setTimeout(() => rej2(new Error('no action.result')), 30000);
        const onMsg = (raw) => {
          const f = JSON.parse(raw.toString());
          if (f.type === 'action.result') {
            clearTimeout(t);
            ws.off('message', onMsg);
            res2(f);
          }
        };
        ws.on('message', onMsg);
      }).then((f) => {
        resolve(f);
        return true;
      });
    }).catch(reject);
  });
  check('refuses to stop protected service', svcRes.ok === false && svcRes.code === 'protected', svcRes.message);

  // --- bad input -----------------------------------------------------------
  const badAction = await new Promise((resolve, reject) => {
    session((ws) => {
      send(ws, { type: 'services.action', name: 'Spooler', action: 'destroy' });
      return new Promise((res2, rej2) => {
        const t = setTimeout(() => rej2(new Error('no action.result')), 30000);
        const onMsg = (raw) => {
          const f = JSON.parse(raw.toString());
          if (f.type === 'action.result') {
            clearTimeout(t);
            ws.off('message', onMsg);
            res2(f);
          }
        };
        ws.on('message', onMsg);
      }).then((f) => {
        resolve(f);
        return true;
      });
    }).catch(reject);
  });
  check('rejects unknown service action', badAction.ok === false && badAction.code === 'bad_action', badAction.message);

  const badPid = await new Promise((resolve, reject) => {
    session((ws) => {
      send(ws, { type: 'procs.kill', pid: -5 });
      return new Promise((res2, rej2) => {
        const t = setTimeout(() => rej2(new Error('no action.result')), 30000);
        const onMsg = (raw) => {
          const f = JSON.parse(raw.toString());
          if (f.type === 'action.result') {
            clearTimeout(t);
            ws.off('message', onMsg);
            res2(f);
          }
        };
        ws.on('message', onMsg);
      }).then((f) => {
        resolve(f);
        return true;
      });
    }).catch(reject);
  });
  check('rejects invalid pid', badPid.ok === false && badPid.code === 'bad_pid', badPid.message);
} catch (err) {
  check('test harness completed', false, err.message);
}

agent.kill();

const failures = results.filter((r) => !r.passed).length;
console.log(`\nP1: ${results.length - failures}/${results.length} passed`);
process.exit(failures === 0 ? 0 : 1);

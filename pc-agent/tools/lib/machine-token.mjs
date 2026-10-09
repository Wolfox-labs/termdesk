/**
 * The machine's own token, or an honest exit.
 *
 * Every end-to-end suite here spawns its own agent and authenticates as if it were the
 * phone, using the token that agent writes to `~/.termdesk/token`. That works on the
 * machine the agent has actually run on and fails everywhere else — which is fine for a
 * developer and fatal for CI, where the file simply does not exist. It used to be a raw
 * `readFileSync`, so a fresh checkout failed with ENOENT inside a suite whose name says
 * nothing about a token, and the whole job went red for a reason that was not a defect.
 *
 * Skipping says so out loud: a suite that quietly reports nothing is indistinguishable
 * from one that passed.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export function machineTokenPath() {
  return path.join(os.homedir(), '.termdesk', 'token');
}

/**
 * Returns the token, or exits 0 with a `skip` line when this machine has never run the
 * agent. Call it at the top level of a suite; the name is only for the message.
 */
export function machineTokenOrSkip(name) {
  const file = machineTokenPath();
  try {
    const token = fs.readFileSync(file, 'utf8').trim();
    if (token) return token;
  } catch {
    // fall through to the skip below: no file, or one we may not read
  }
  console.log(`skip ${name}: ${file} 不存在（这台机器没跑过 TermDesk PC 代理，或没有权限读它）`);
  process.exit(0);
}

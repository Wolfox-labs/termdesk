/**
 * What a turn says it produced.
 *
 * The DSH runtime records `deliverables/presented` when an agent hands over a file, and that
 * record is the only per-turn statement of what the work left behind. The alternative —
 * diffing the working directory around a turn — reports every build artifact as a
 * deliverable and misses anything written outside the workspace.
 *
 * The shapes below are copied from real session files (`~/.dsh/sessions/**`), not invented:
 * the point of this test is the mapping from what the runtime writes to what the phone
 * draws, so the input has to be what the runtime actually writes.
 *
 * Free, offline, no agent, no model, no session file read.
 *
 *   node tools/dsh-deliverables-test.js
 */
import { dshEventToChatEvent } from '../src/sessions.js';

const results = [];
const check = (name, passed, detail = '') => {
  results.push({ name, passed });
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

/** One recorded line, verbatim in shape. */
const presented = {
  type: 'deliverables/presented',
  seq: 704,
  time: 1791468272023,
  data: {
    turn: 10,
    callId: 'call_00_L7jNCpz9VcnEfDNJbXbg2577',
    files: [
      {
        description: 'Go 重构完整规划：事实基线、六个问题的评估（含实测数据与 file:line）、分阶段计划',
        path: 'E:\\aiPic\\servers\\go-pool-plan-2026-10-08.md',
      },
    ],
  },
};

const event = dshEventToChatEvent(presented);
check('a presented deliverable becomes a chat event', Boolean(event));
check('of its own kind, so the phone can draw it as one', event?.kind === 'deliverable',
  event?.kind);
check('and it is attributed to the assistant', event?.role === 'assistant', event?.role);

// The paths are data because the phone makes them tappable.
check('the path survives as data', event?.meta?.files?.[0]?.path === presented.data.files[0].path,
  event?.meta?.files?.[0]?.path);
check('with its description', event?.meta?.files?.[0]?.description?.startsWith('Go 重构完整规划') === true);

// ...and the descriptions are ALSO the text, because a recorded session keeps no meta: the
// alternative is a history that shows a blank line where the work was handed over.
check('and the line itself says what the file was for', event?.text?.startsWith('Go 重构完整规划') === true,
  event?.text);

// Two files in one turn is normal (a report and the data behind it).
const pair = dshEventToChatEvent({
  ...presented,
  data: {
    ...presented.data,
    files: [
      { description: '报告', path: 'E:\\a\\report.md' },
      { description: '', path: 'E:\\a\\data.csv' },
    ],
  },
});
check('every file in the turn is carried', pair?.meta?.files?.length === 2,
  `${pair?.meta?.files?.length}`);
check('a file with no description still has a path to open', pair?.meta?.files?.[1]?.path === 'E:\\a\\data.csv');
check('and falls back to its path in the text', pair?.text?.includes('E:\\a\\data.csv') === true,
  pair?.text?.replace(/\n/g, ' / '));

// Nothing usable means no event: a deliverable line with no file on it is worse than none.
check('a record with no files is dropped',
  dshEventToChatEvent({ ...presented, data: { ...presented.data, files: [] } }) === null);
check('so is one whose entries have no path',
  dshEventToChatEvent({ ...presented, data: { ...presented.data, files: [{ description: 'x' }] } }) === null);
check('and a malformed payload does not throw',
  dshEventToChatEvent({ type: 'deliverables/presented', seq: 1, time: 1, data: {} }) === null);

const failed = results.filter((r) => !r.passed);
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`);
process.exit(failed.length === 0 ? 0 : 1);

/**
 * The allowlist shape the round-16 regression gates share.
 *
 * A gate scans the tree and produces findings, each with a stable `id`
 * (`file::rule::anchor`, never a line number). The baseline pins how many
 * findings with that id exist today and WHY — a QA defect id (`D976`) or a
 * one-line reason a person wrote after looking. The gate fails on
 *
 *  - a finding whose id is not pinned, or pinned fewer times than it occurs:
 *    the class of bug came back somewhere new;
 *  - a pin with more occurrences than the tree has: the fix landed, so the
 *    pin must shrink in the same change (the gain cannot be spent again).
 *
 * `--report-only` prints and never fails; `--write-baseline` rewrites the file
 * from the tree — read the diff, it is the list of what you are excusing.
 *
 * Baseline file:
 * {
 *   "$comment": [...],
 *   "pinned": { "<id>": { "count": 2, "why": "D976" }, ... }
 * }
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';

export function loadBaseline(path) {
  if (!existsSync(path)) return { pinned: {} };
  const doc = JSON.parse(readFileSync(path, 'utf8'));
  return { pinned: doc.pinned ?? {} };
}

/** Group findings by id, in document order. */
export function tally(findings) {
  const byId = new Map();
  for (const f of findings) {
    const entry = byId.get(f.id);
    if (entry) {
      entry.count++;
      entry.all.push(f);
    } else byId.set(f.id, { count: 1, all: [f] });
  }
  return byId;
}

/**
 * @returns {{ fresh: object[], stale: string[] }} `fresh` = the occurrences
 *   beyond their pin — the LAST ones in document order, since a new copy of an
 *   already-pinned control is usually appended below the old ones — `stale` =
 *   pins the tree no longer fills.
 */
export function compare(findings, baseline) {
  const byId = tally(findings);
  const fresh = [];
  for (const [id, { count, all }] of byId) {
    const allowed = baseline.pinned[id]?.count ?? 0;
    for (const f of all.slice(allowed)) fresh.push({ ...f, count, allowed });
  }
  const stale = [];
  for (const [id, pin] of Object.entries(baseline.pinned)) {
    const count = byId.get(id)?.count ?? 0;
    if (count < pin.count) stale.push(`${id} (pinned ${pin.count}, found ${count})`);
  }
  fresh.sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line);
  return { fresh, stale };
}

export function writeBaseline(path, findings, comment, previous = { pinned: {} }) {
  const pinned = {};
  for (const [id, { count }] of [...tally(findings)].sort(([a], [b]) => a.localeCompare(b))) {
    pinned[id] = { count, why: previous.pinned[id]?.why ?? 'TODO: defect id or reason' };
  }
  writeFileSync(path, `${JSON.stringify({ $comment: comment, pinned }, null, 2)}\n`, 'utf8');
}

/**
 * The one exit path every gate shares: print the fresh findings with file:line
 * and the class of bug, print the stale pins, exit 1 unless `--report-only`.
 *
 * @param {object} o
 * @param {string} o.name gate name for the summary line
 * @param {string} o.baselinePath
 * @param {object[]} o.findings
 * @param {string[]} o.comment the `$comment` written by `--write-baseline`
 * @param {string} o.hint one line telling the reader how to fix a fresh finding
 * @param {string} o.root paths in the report are relative to this
 */
export function runGate({ name, baselinePath, findings, comment, hint }) {
  const argv = process.argv.slice(2);
  const reportOnly = argv.includes('--report-only');
  const baseline = loadBaseline(baselinePath);
  if (argv.includes('--write-baseline')) {
    writeBaseline(baselinePath, findings, comment, baseline);
    console.log(`${name}: wrote ${tally(findings).size} pin(s) to ${baselinePath} — read the diff`);
    return;
  }
  const { fresh, stale } = compare(findings, baseline);
  for (const f of fresh) {
    console.error(`${f.file}:${f.line}: [${f.rule}] ${f.message}`);
    if (f.allowed > 0) {
      console.error(
        `    ${f.count} occurrence(s) of this site, ${f.allowed} pinned — a new one appeared`,
      );
    }
  }
  if (fresh.length > 0) console.error(`\n${hint}`);
  for (const s of stale) {
    console.error(`stale pin: ${s} — lower or delete it in ${baselinePath}`);
  }
  const pinnedTotal = Object.values(baseline.pinned).reduce((n, p) => n + p.count, 0);
  console.log(
    `${name}: ${findings.length} site(s) found, ${pinnedTotal} pinned, ` +
      `${fresh.length} new, ${stale.length} stale pin(s)`,
  );
  if (!reportOnly && (fresh.length > 0 || stale.length > 0)) process.exit(1);
}

#!/usr/bin/env node
/**
 * Coverage ratchet gate (task 21, Phase 4).
 *
 * Reads the Istanbul json-summary produced by `ng test --code-coverage`
 * (coverage/coverage-summary.json) and enforces the floors declared in
 * coverage-thresholds.json:
 *
 *   {
 *     "global":  { "statements": 60, "branches": 50, "functions": 60, "lines": 60 },
 *     "modules": {
 *       "src/app/billing/": { "statements": 80, "lines": 80 }
 *     }
 *   }
 *
 * - "global" is checked against the summary's "total" entry.
 * - Each "modules" entry aggregates every covered file whose repo path
 *   starts with the given prefix (forward slashes), then checks the
 *   aggregate percentages against that module's floors. Metrics omitted
 *   from an entry are not checked.
 *
 * Ratchet discipline: floors are set at (or slightly below) the coverage
 * a module actually has, so coverage can only stay level or improve.
 * When you raise a module's coverage, raise its floor in the same PR.
 * Never lower a floor to make a failing build pass — that deletes the
 * ratchet's entire value. Legacy flat files ({"statements": 60, ...})
 * are still accepted and treated as "global".
 *
 * Usage:
 *   node scripts/check-coverage.mjs                # gate (non-zero exit on failure)
 *   node scripts/check-coverage.mjs --report-only  # print table, never fail
 */

import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(SCRIPT_DIR, '..');
const SUMMARY_PATH = join(ROOT, 'coverage', 'coverage-summary.json');
const THRESHOLDS_PATH = join(ROOT, 'coverage-thresholds.json');
const METRICS = ['statements', 'branches', 'functions', 'lines'];
const REPORT_ONLY = process.argv.includes('--report-only');

if (!existsSync(SUMMARY_PATH)) {
    console.error(
        `coverage summary not found at ${SUMMARY_PATH} — run "npm run test:coverage" first`
    );
    process.exit(2);
}
if (!existsSync(THRESHOLDS_PATH)) {
    console.error(`thresholds file not found at ${THRESHOLDS_PATH}`);
    process.exit(2);
}

const summary = JSON.parse(readFileSync(SUMMARY_PATH, 'utf8'));
const rawThresholds = JSON.parse(readFileSync(THRESHOLDS_PATH, 'utf8'));

// Legacy flat form ({"statements": 60, ...}) → treat as global-only.
const thresholds = rawThresholds.global || rawThresholds.modules
    ? { global: rawThresholds.global ?? {}, modules: rawThresholds.modules ?? {} }
    : { global: rawThresholds, modules: {} };

/** Normalize an absolute summary key to a repo-relative, forward-slash path. */
function normalizeKey(key) {
    const slashed = key.replaceAll('\\', '/');
    const marker = 'hospital-portal/';
    const idx = slashed.lastIndexOf(marker);
    return idx >= 0 ? slashed.slice(idx + marker.length) : slashed;
}

/** Aggregate raw covered/total counts across the files under a path prefix. */
function aggregate(prefix) {
    const totals = Object.fromEntries(METRICS.map(m => [m, { covered: 0, total: 0 }]));
    let files = 0;
    for (const [key, metrics] of Object.entries(summary)) {
        if (key === 'total') continue;
        if (!normalizeKey(key).startsWith(prefix)) continue;
        files += 1;
        for (const m of METRICS) {
            totals[m].covered += metrics[m]?.covered ?? 0;
            totals[m].total += metrics[m]?.total ?? 0;
        }
    }
    const pct = Object.fromEntries(
        METRICS.map(m => [
            m,
            totals[m].total === 0 ? 100 : (totals[m].covered / totals[m].total) * 100,
        ])
    );
    return { files, pct };
}

let failed = false;
const rows = [['Scope', 'Files', ...METRICS.map(m => m[0].toUpperCase() + m.slice(1)), 'Status']];

function check(label, files, pct, floors) {
    const problems = [];
    for (const m of METRICS) {
        if (floors[m] === undefined) continue;
        if (pct[m] + 1e-9 < floors[m]) {
            problems.push(`${m} ${pct[m].toFixed(2)}% < ${floors[m]}%`);
        }
    }
    if (problems.length > 0) failed = true;
    rows.push([
        label,
        String(files),
        ...METRICS.map(m =>
            floors[m] === undefined
                ? `${pct[m].toFixed(1)}`
                : `${pct[m].toFixed(1)}/${floors[m]}`
        ),
        problems.length === 0 ? '✓' : `✗ ${problems.join(', ')}`,
    ]);
}

// Global floor from the "total" entry.
const total = summary.total;
if (total && Object.keys(thresholds.global).length > 0) {
    const pct = Object.fromEntries(METRICS.map(m => [m, total[m]?.pct ?? 0]));
    const files = Object.keys(summary).length - 1;
    check('global', files, pct, thresholds.global);
}

// Per-module floors.
for (const [prefix, floors] of Object.entries(thresholds.modules)) {
    const { files, pct } = aggregate(prefix);
    if (files === 0) {
        failed = true;
        rows.push([prefix, '0', ...METRICS.map(() => '—'), '✗ no files matched prefix']);
        continue;
    }
    check(prefix, files, pct, floors);
}

const widths = rows[0].map((_, c) => Math.max(...rows.map(r => r[c].length)));
const fmt = r => r.map((cell, i) => cell.padEnd(widths[i])).join('  ');
console.log('Coverage ratchet (cell = actual% or actual%/floor%)');
console.log(fmt(rows[0]));
console.log(widths.map(w => '-'.repeat(w)).join('  '));
rows.slice(1).forEach(r => console.log(fmt(r)));

if (failed) {
    const verdict = REPORT_ONLY ? 'WARN (report-only)' : 'FAIL';
    console.error(
        `\n${verdict}: coverage fell below a ratchet floor. Add or fix specs — do not lower the floor.`
    );
    if (!REPORT_ONLY) process.exit(1);
} else {
    console.log('\nOK: all coverage floors met.');
}

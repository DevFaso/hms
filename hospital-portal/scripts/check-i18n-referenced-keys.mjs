#!/usr/bin/env node
/**
 * i18n "used but undefined" gate.
 *
 * check-i18n-coverage.mjs compares the locale files against EACH OTHER, so it
 * catches a key that FR has and ES lacks. It structurally cannot catch a key
 * that is missing from every locale — with nothing to diff, the parity check
 * is perfectly happy while the UI renders the raw key.
 *
 * That is not hypothetical: on 2026-08-23 the drug-interactions and slot-admin
 * pages shipped with their ENTIRE namespaces absent (~130 keys), plus
 * NAV.ON_CALL / NAV.DRUG_INTERACTIONS / NAV.SLOT_ADMIN in the sidebar, and the
 * parity gate had been green the whole time.
 *
 * So this script works from the other direction: scan the source for keys that
 * are actually handed to ngx-translate, and fail if EN does not define them.
 * EN is the baseline (parity then guarantees FR/ES).
 *
 * Pure Node, no dependencies — same shape as its sibling gates.
 *
 * Usage:
 *   node scripts/check-i18n-referenced-keys.mjs
 *   node scripts/check-i18n-referenced-keys.mjs --report-only   # never exit non-zero
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const SRC = join(ROOT, 'src', 'app');
const EN = join(ROOT, 'src', 'assets', 'i18n', 'en.json');
const reportOnly = process.argv.includes('--report-only');

/**
 * Keys built by concatenation — `'NS.PREFIX_' + value | translate` — cannot be
 * resolved statically. Each entry lists the runtime suffixes so the real keys
 * are still checked. Add to this map when you introduce a new dynamic key;
 * leaving it out silently drops that key from the gate.
 */
const DYNAMIC_SUFFIXES = {
  'DRUG_INTERACTIONS.SEVERITY_': ['CONTRAINDICATED', 'MAJOR', 'MODERATE', 'MINOR', 'UNKNOWN'],
  'SLOT_ADMIN.STATUS_': ['OPEN', 'HELD', 'BOOKED', 'BLOCKED'],
  'SLOT_ADMIN.DAY_': ['1', '2', '3', '4', '5', '6', '7'],
  'RECEPTION.RECALL_STATUS_': ['PENDING', 'NOTIFIED', 'SCHEDULED', 'CLOSED', 'CANCELLED'],
};

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, out);
    else if (/\.(html|ts)$/.test(entry) && !entry.endsWith('.spec.ts')) out.push(full);
  }
  return out;
}

function flatten(node, prefix = '', out = new Set()) {
  for (const [key, value] of Object.entries(node)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object') flatten(value, path, out);
    else out.add(path);
  }
  return out;
}

// A key is only reported when it is demonstrably handed to ngx-translate:
// the `| translate` pipe, translate.instant/get/stream, or the shell's
// `translationKey:` nav field. Bare uppercase strings are NOT assumed to be
// keys — enum values and constants look identical.
const KEY = '([A-Z][A-Z0-9_]*(?:\\.[A-Z0-9_]+)+)';
const PATTERNS = [
  new RegExp(`['"\`]${KEY}['"\`]\\s*\\|\\s*translate`, 'g'),
  new RegExp(`translate\\.(?:instant|get|stream)\\(\\s*['"\`]${KEY}['"\`]`, 'g'),
  new RegExp(`translationKey:\\s*['"\`]${KEY}['"\`]`, 'g'),
];

const defined = flatten(JSON.parse(readFileSync(EN, 'utf8')));
const referenced = new Map(); // key -> Set<file>

for (const file of walk(SRC)) {
  const text = readFileSync(file, 'utf8');
  for (const pattern of PATTERNS) {
    for (const match of text.matchAll(pattern)) {
      const key = match[1];
      // 'NS.PREFIX_' + value captures as the bare prefix; the suffix loop
      // below expands it into the real keys, so skip the stub here.
      if (Object.prototype.hasOwnProperty.call(DYNAMIC_SUFFIXES, key)) continue;
      if (!referenced.has(key)) referenced.set(key, new Set());
      referenced.get(key).add(relative(ROOT, file));
    }
  }
  for (const [prefix, suffixes] of Object.entries(DYNAMIC_SUFFIXES)) {
    if (!text.includes(prefix)) continue;
    for (const suffix of suffixes) {
      const key = prefix + suffix;
      if (!referenced.has(key)) referenced.set(key, new Set());
      referenced.get(key).add(relative(ROOT, file));
    }
  }
}

const missing = [...referenced.keys()].filter((k) => !defined.has(k)).sort();

console.log(
  `i18n referenced-key check: ${referenced.size} keys used in templates, ${defined.size} defined in en.json`,
);

if (missing.length === 0) {
  console.log('OK: every referenced key is defined in en.json.');
  process.exit(0);
}

console.error(`\nFAIL: ${missing.length} key(s) are used but defined in NO locale:\n`);
const byNamespace = new Map();
for (const key of missing) {
  const ns = key.split('.')[0];
  if (!byNamespace.has(ns)) byNamespace.set(ns, []);
  byNamespace.get(ns).push(key);
}
for (const [ns, keys] of [...byNamespace.entries()].sort((a, b) => b[1].length - a[1].length)) {
  console.error(`  ${ns} (${keys.length}):`);
  for (const key of keys.slice(0, 12)) {
    console.error(`    ${key}   ← ${[...referenced.get(key)].slice(0, 2).join(', ')}`);
  }
  if (keys.length > 12) console.error(`    … and ${keys.length - 12} more`);
}
console.error(
  '\nAdd them to src/assets/i18n/{en,fr,es}.json. A key missing from all three\n' +
    'renders as the raw key in the UI and the parity gate cannot see it.',
);
process.exit(reportOnly ? 0 : 1);

#!/usr/bin/env node
/**
 * i18n *raw enum* gate — the blind spot check-i18n-enum-coverage.mjs names in
 * its own header.
 *
 * That gate asks: for every domain a template PIPES, is every value keyed? It
 * cannot ask the other half — whether a field that SHOULD be piped is. A
 * template that writes
 *
 *     {{ order.status }}   instead of   {{ order.status | enumLabel: 'labOrderStatus' }}
 *
 * puts the wire token on screen in every language, and every other gate stays
 * green: the key exists, it is translated, it is simply never asked for.
 *
 * Deciding a site needs a person. `appt.reason`, `med.frequency` and
 * `lab.result` are free text a clinician typed and MUST stay raw, and a real
 * enum's domain has to be traced to the DTO field the API fills rather than
 * guessed from the variable name. So this gate does not decide: it pins every
 * site that exists today in scripts/i18n-raw-enums-baseline.json and fails on
 * one that is not pinned — the same shape as check-i18n-untranslated.mjs
 * pinning the words French genuinely shares with English.
 *
 * A pin is a claim that someone looked. It is NOT a numeric ceiling: a count
 * cannot name the site that broke it, and cannot tell a newly-added free-text
 * field (legitimate) from a newly-added enum render (a defect).
 *
 * Remove pins as tranches land; a pin whose site is gone is reported stale, so
 * the file cannot quietly outlive what it was granted for.
 *
 * Usage:
 *   node scripts/check-i18n-raw-enums.mjs
 *   node scripts/check-i18n-raw-enums.mjs --report-only     # never exit non-zero
 *   node scripts/check-i18n-raw-enums.mjs --write-baseline  # READ THE DIFF
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './lib/walk.mjs';
import { rawEnumRenders } from './lib/raw-enum-scan.mjs';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const PORTAL_DIR = resolve(SCRIPT_DIR, '..');
const SRC = resolve(PORTAL_DIR, 'src', 'app');
const BASELINE_PATH = resolve(SCRIPT_DIR, 'i18n-raw-enums-baseline.json');

const REPORT_ONLY = process.argv.includes('--report-only');
const WRITE_BASELINE = process.argv.includes('--write-baseline');

// `template::expression`, deliberately without a line number: a site must stay
// pinned when the lines around it move, and must stop being pinned when it is
// piped or deleted.
const seen = new Map();
const files = new Set();
for (const file of walk(SRC)) {
  const rel = relative(PORTAL_DIR, file).replaceAll('\\', '/');
  for (const hit of rawEnumRenders(readFileSync(file, 'utf8'))) {
    files.add(rel);
    const id = `${rel}::${hit.expr}`;
    if (!seen.has(id)) seen.set(id, { ...hit, file: rel, id });
  }
}

if (WRITE_BASELINE) {
  const doc = {
    $comment: [
      'Enum-shaped fields rendered without | enumLabel, as they stand today.',
      'Each entry is "template::expression". A site not listed here fails the',
      'build. Remove entries as tranches land.',
      '',
      'Pinning one is a claim that a person looked and decided it is free text a',
      'human typed, or that its domain is not yet traced — not a way to make the',
      'build green.',
    ],
    pinned: [...seen.keys()].sort(),
  };
  writeFileSync(BASELINE_PATH, `${JSON.stringify(doc, null, 2)}\n`, 'utf8');
  console.log(`[i18n-raw-enums] baseline rewritten with ${seen.size} site(s) — READ THE DIFF.`);
  process.exit(0);
}

const pinned = new Set(JSON.parse(readFileSync(BASELINE_PATH, 'utf8')).pinned);
const unpinned = [...seen.values()].filter((hit) => !pinned.has(hit.id));
const stale = [...pinned].filter((id) => !seen.has(id));

console.log(
  `[i18n-raw-enums] ${seen.size} raw enum render(s) across ${files.size} template(s), ` +
    `${pinned.size} pinned`,
);

for (const hit of unpinned) {
  console.error(
    `  RAW ENUM ${hit.file}:${hit.line}  ${hit.expr} — renders the wire token in every ` +
      `language. Trace the DTO field it binds and pipe it through | enumLabel: '<domain>', ` +
      `or pin it as ${JSON.stringify(hit.id)} in scripts/i18n-raw-enums-baseline.json if it ` +
      `is free text a person typed.`,
  );
}
for (const id of stale) {
  console.error(`  STALE PIN ${id} no longer renders raw; drop it from the baseline.`);
}

if ((unpinned.length || stale.length) && !REPORT_ONLY) process.exit(1);

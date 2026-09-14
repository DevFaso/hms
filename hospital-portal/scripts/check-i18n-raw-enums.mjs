#!/usr/bin/env node
/**
 * i18n *raw enum* ratchet — the blind spot the enum-coverage gate names.
 *
 * check-i18n-enum-coverage.mjs asks: for every domain a template PIPES, is
 * every value keyed? It cannot ask the other half — whether a field that
 * should be piped is. A template that writes
 *
 *     {{ order.status }}        instead of        {{ order.status | enumLabel: 'labOrderStatus' }}
 *
 * puts the wire token on the screen in every language, and every other gate
 * stays green: the key exists, it is translated, it is simply never asked for.
 *
 * Deciding each site needs a person. `leave.reason`, `appt.reason` and
 * `med.frequency` are free text a clinician typed and MUST stay raw; the
 * tranche this ratchet was introduced with piped fifteen sites only after
 * tracing each to the DTO field the API fills. So this does not fail on a
 * finding — it counts them and refuses to let the count grow, exactly as
 * check-i18n-untranslated.mjs ratchets the Spanish backlog.
 *
 * Lower RAW_ENUM_CEILING whenever a tranche lands. It only ever comes down.
 *
 * Usage:
 *   node scripts/check-i18n-raw-enums.mjs
 *   node scripts/check-i18n-raw-enums.mjs --list        # every site, to pick the next tranche
 *   node scripts/check-i18n-raw-enums.mjs --report-only
 */
import { readFileSync } from 'node:fs';
import { resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './lib/walk.mjs';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const PORTAL_DIR = resolve(SCRIPT_DIR, '..');
const SRC = resolve(PORTAL_DIR, 'src', 'app');

const CEILING = Number(process.env.RAW_ENUM_CEILING ?? '187');
const LIST = process.argv.includes('--list');
const REPORT_ONLY = process.argv.includes('--report-only');

/**
 * Field names that usually hold an enum. `encounterType` is why the tail is
 * matched rather than the whole name: the first version of this scan looked
 * for a field CALLED `.type` and so never saw `.encounterType`, and six of
 * those were found by hand afterwards.
 */
const WORDS = [
  'status',
  'state',
  'type',
  'severity',
  'priority',
  'urgency',
  'disposition',
  'category',
  'kind',
  'outcome',
  'method',
  'mode',
  'level',
  'route',
  'gender',
  'relationship',
  'specialty',
  'modality',
  'reason',
  'result',
  'frequency',
  'flag',
  'phase',
  'stage',
  'source',
  'action',
  'channel',
];

const FIELD = new RegExp(
  String.raw`\{\{\s*([A-Za-z_$][\w.$]*\??\.(?:\w*?(?:${WORDS.join('|')})))(?:\(\))?\s*` +
    String.raw`(?:\|\|[^}|]*?)?\s*\}\}`,
  'gis',
);

const found = [];
for (const file of walk(SRC)) {
  const text = readFileSync(file, 'utf8');
  for (const match of text.matchAll(FIELD)) {
    found.push({
      file: relative(PORTAL_DIR, file).replaceAll('\\', '/'),
      line: text.slice(0, match.index).split('\n').length,
      expr: match[1].split(/\s+/).join(''),
    });
  }
}

if (LIST) {
  for (const hit of found) console.log(`${hit.file}:${hit.line}  ${hit.expr}`);
}

const files = new Set(found.map((h) => h.file)).size;
console.log(
  `[i18n-raw-enums] ${found.length} enum-shaped field(s) rendered raw across ${files} template(s), ceiling ${CEILING}`,
);

if (found.length > CEILING) {
  console.error(
    `  ${found.length} > ${CEILING}. A new one of these puts the wire token on screen in every ` +
      `language. Pipe it through | enumLabel: '<domain>' after checking which enum its DTO field ` +
      `holds — or, if it is free text a human typed, leave it and raise nothing: the ceiling only ` +
      `ever comes down.`,
  );
  if (!REPORT_ONLY) process.exit(1);
} else if (found.length < CEILING) {
  console.log(
    `  down ${CEILING - found.length} from the ceiling — lower RAW_ENUM_CEILING to lock it in.`,
  );
}

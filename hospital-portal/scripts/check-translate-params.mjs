#!/usr/bin/env node
/**
 * Translation-parameter gate (QA D1035).
 *
 * A translated value that names a placeholder the call does not pass shows
 * the raw "{{count}}" to the reader. This gate reads every template and
 * component for a translate call with a literal key and an object-literal
 * argument, and compares the argument's property names with the
 * placeholders of the key's English value. Rules:
 * lib/translate-params-scan.mjs. Sites that exist today are pinned in
 * scripts/translate-params-baseline.json with their defect id.
 *
 * Usage:
 *   node scripts/check-translate-params.mjs
 *   node scripts/check-translate-params.mjs --report-only
 *   node scripts/check-translate-params.mjs --write-baseline   # READ THE DIFF
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { translateParamFindings, flatten } from './lib/translate-params-scan.mjs';
import { scanTemplates, PORTAL_DIR } from './lib/template-gate.mjs';
import { scanComponents } from './lib/component-sources.mjs';
import { runGate, dedupe, isMain } from './lib/baseline.mjs';

let locales;
/** The key's value in every locale, joined, so a placeholder any one of them names is checked. */
function valueOf(key) {
  locales ??= ['en', 'fr', 'es'].map((l) =>
    flatten(
      JSON.parse(readFileSync(resolve(PORTAL_DIR, 'src', 'assets', 'i18n', `${l}.json`), 'utf8')),
    ),
  );
  const values = locales.map((m) => m.get(key)).filter((v) => typeof v === 'string');
  return values.length > 0 ? values.join('\n') : undefined;
}

export const gate = {
  name: 'translate params',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'translate-params-baseline.json'),
  findings: (ctx) =>
    dedupe(
      scanTemplates((html) => translateParamFindings(html, valueOf), {}, ctx),
      scanComponents((source) => translateParamFindings(source, valueOf), ctx),
    ),
  comment: [
    'Translate calls that leave a placeholder of the value unfilled, as they stand today.',
    'Each key is "file::rule::anchor"; "why" is the QA defect id or the reason.',
    'A site not listed fails the build; pass the parameter (or fix the value), then delete its pin.',
  ],
  hint: 'Pass every {{placeholder}} the English value names, under the same name, or change the value to the names the call passes.',
};

if (isMain(import.meta.url)) runGate(gate);

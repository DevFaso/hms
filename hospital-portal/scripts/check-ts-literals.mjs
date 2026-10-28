#!/usr/bin/env node
/**
 * English-literal gate for component code (the portal layer of QA D940, D977,
 * D1000: an English sentence reaching a French or Spanish screen).
 *
 * The locale gates only see text that goes through ngx-translate. A toast or a
 * status signal written as an English sentence in a `.ts` file is visible to
 * none of them. The tree holds none today; this gate keeps it that way.
 *
 * Rules: lib/ts-literal-scan.mjs. The baseline (scripts/ts-literals-baseline.json)
 * is empty on purpose.
 *
 * Usage:
 *   node scripts/check-ts-literals.mjs
 *   node scripts/check-ts-literals.mjs --report-only
 */
import { resolve } from 'node:path';

import { tsLiteralFindings } from './lib/ts-literal-scan.mjs';
import { scanComponents } from './lib/component-sources.mjs';
import { PORTAL_DIR } from './lib/template-gate.mjs';
import { runGate } from './lib/baseline.mjs';

runGate({
  name: 'ts literals',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'ts-literals-baseline.json'),
  findings: scanComponents((source) => tsLiteralFindings(source)),
  comment: [
    'English sentences handed to a toast or a status signal from component code.',
    "Empty on purpose: translate.instant('KEY') is the only way text reaches a toast.",
  ],
  hint: "Put the sentence in the three locale files and pass translate.instant('SECTION.KEY').",
});

#!/usr/bin/env node
/**
 * English-literal gate for component code (the portal layer of QA D940, D977,
 * D1000: an English sentence reaching a French or Spanish screen).
 *
 * The locale gates only see text that goes through ngx-translate. A toast or a
 * status signal written as an English sentence in a `.ts` file is visible to
 * none of them. Every toast in the tree is translated already; the five
 * English error strings of reset-password.ts and one `?? 'Already in use'`
 * fallback in user-list.ts are the only sites, pinned in
 * scripts/ts-literals-baseline.json with that reason until they are keyed.
 *
 * Rules: lib/ts-literal-scan.mjs.
 *
 * Usage:
 *   node scripts/check-ts-literals.mjs
 *   node scripts/check-ts-literals.mjs --report-only
 *   node scripts/check-ts-literals.mjs --write-baseline   # READ THE DIFF
 */
import { resolve } from 'node:path';

import { tsLiteralFindings } from './lib/ts-literal-scan.mjs';
import { scanComponents } from './lib/component-sources.mjs';
import { PORTAL_DIR } from './lib/template-gate.mjs';
import { runGate, isMain } from './lib/baseline.mjs';

export const gate = {
  name: 'ts literals',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'ts-literals-baseline.json'),
  findings: (ctx) => scanComponents((source) => tsLiteralFindings(source), ctx),
  comment: [
    'English sentences handed to a toast or a status signal from component code,',
    "as they stand today. A site not listed fails the build; translate.instant('KEY')",
    'is the only way text reaches a toast. Key a site, then delete its pin.',
  ],
  hint: "Put the sentence in the three locale files and pass translate.instant('SECTION.KEY').",
};

if (isMain(import.meta.url)) runGate(gate);

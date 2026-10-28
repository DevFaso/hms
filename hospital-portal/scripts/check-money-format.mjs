#!/usr/bin/env node
/**
 * Money-format gate (QA D948, D1008; earlier D621).
 *
 * Every amount in francs CFA is written by formatCfa() / the `| cfa` pipe.
 * Round 16 found the front-desk payment dialog showing "Solde: 1 500,00" with
 * no currency (`| number: '1.2-2'`, D948) and Spanish 4-digit amounts with no
 * grouping (D1008, in formatCfa itself — its spec covers that). This gate reads
 * every template and component for the other ways of printing money.
 *
 * Rules: lib/money-scan.mjs. Sites that exist today are pinned in
 * scripts/money-format-baseline.json with their defect id.
 *
 * Usage:
 *   node scripts/check-money-format.mjs
 *   node scripts/check-money-format.mjs --report-only
 *   node scripts/check-money-format.mjs --write-baseline   # READ THE DIFF
 */
import { resolve } from 'node:path';

import { moneyTemplateFindings, moneyComponentFindings } from './lib/money-scan.mjs';
import { scanTemplates, PORTAL_DIR } from './lib/template-gate.mjs';
import { scanComponents } from './lib/component-sources.mjs';
import { runGate, dedupe } from './lib/baseline.mjs';

const LOCALE_MODULE = 'src/app/shared/i18n/app-locale.ts';

runGate({
  name: 'money format',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'money-format-baseline.json'),
  // An inline template's `.toFixed(` is seen by both scans; dedupe keeps one finding per site.
  findings: dedupe([
    ...scanTemplates(moneyTemplateFindings),
    ...scanComponents((source, file) => moneyComponentFindings(source, file === LOCALE_MODULE)),
  ]),
  comment: [
    'Amounts printed without formatCfa / | cfa, as they stand today.',
    'Each key is "file::rule::anchor"; "why" is the QA defect id or the reason.',
    'A site not listed fails the build; fix it, then delete its pin.',
  ],
  hint: 'Use {{ amount | cfa }} in templates and formatCfa(amount) in code (shared/i18n/app-locale.ts).',
});

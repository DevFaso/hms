#!/usr/bin/env node
/**
 * Identifier-fragment gate (QA D992; D1009 for file names).
 *
 * Triage and nursing-intake forms showed the first eight characters of the
 * encounter UUID as the visit's name. Every named thing has a human code; a
 * sliced id is a placeholder that shipped. This gate reads every template and
 * component for `| slice: 0 : 8` and `.slice/substring/substr(0, 8)` on an
 * id-shaped expression.
 *
 * Rules: lib/uuid-fragment-scan.mjs. Sites that exist today are pinned in
 * scripts/uuid-fragments-baseline.json with their defect id.
 *
 * Usage:
 *   node scripts/check-uuid-fragments.mjs
 *   node scripts/check-uuid-fragments.mjs --report-only
 *   node scripts/check-uuid-fragments.mjs --write-baseline   # READ THE DIFF
 */
import { resolve } from 'node:path';

import { uuidFragmentFindings } from './lib/uuid-fragment-scan.mjs';
import { scanTemplates, PORTAL_DIR } from './lib/template-gate.mjs';
import { scanComponents } from './lib/component-sources.mjs';
import { runGate, dedupe, isMain } from './lib/baseline.mjs';

export const gate = {
  name: 'uuid fragments',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'uuid-fragments-baseline.json'),
  // An inline template is seen by both scans; dedupe keeps one finding per site.
  findings: (ctx) =>
    dedupe(
      scanTemplates((html) => uuidFragmentFindings(html), {}, ctx),
      scanComponents((source) => uuidFragmentFindings(source), ctx),
    ),
  comment: [
    'UUID fragments shown on screen, as they stand today.',
    'Each key is "file::rule::anchor"; "why" is the QA defect id or the reason.',
    'A site not listed fails the build; show the human code, then delete its pin.',
  ],
  hint: 'Show the human code the API already carries (encounterNumber, mrn, invoiceNumber, orderNumber), not a slice of the id.',
};

if (isMain(import.meta.url)) runGate(gate);

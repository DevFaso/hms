#!/usr/bin/env node
/**
 * Person-search gate (QA D1019, D1020).
 *
 * The patient list compared names and phone numbers with
 * `x.toLowerCase().includes(term)`: accent-sensitive, blind to full names and
 * to every written phone format, while the server's search folds all three.
 * This gate reads every component for that shape on a person-name or phone
 * field. Rules: lib/person-search-scan.mjs. Sites that exist today are pinned
 * in scripts/person-search-baseline.json with their defect id.
 *
 * Usage:
 *   node scripts/check-person-search.mjs
 *   node scripts/check-person-search.mjs --report-only
 *   node scripts/check-person-search.mjs --write-baseline   # READ THE DIFF
 */
import { resolve } from 'node:path';

import { personSearchFindings } from './lib/person-search-scan.mjs';
import { PORTAL_DIR } from './lib/template-gate.mjs';
import { scanComponents } from './lib/component-sources.mjs';
import { runGate, isMain } from './lib/baseline.mjs';

export const gate = {
  name: 'person search',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'person-search-baseline.json'),
  findings: (ctx) => scanComponents((source) => personSearchFindings(source), ctx),
  comment: [
    'People filtered by a raw toLowerCase().includes(), as they stand today.',
    'Each key is "file::rule::anchor"; "why" is the QA defect id or the reason.',
    'A site not listed fails the build; use shared/person-search, then delete its pin.',
  ],
  hint: 'Filter people with textMatches / personNameMatches / phoneMatches from src/app/shared/person-search.ts.',
};

if (isMain(import.meta.url)) runGate(gate);

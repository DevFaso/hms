#!/usr/bin/env node
/**
 * Silent list-cap gate (QA D1347, round 30; the D1213 / D1295 / D1346 class).
 *
 * A list read from ONE page of a fixed size and treated as the whole list:
 * the lab orders page read the 200 newest of 216 orders and counted that
 * page, so an older pending order could not be found and the counters lied.
 * This gate reads every component and service for a literal page size, a
 * page reduced to its rows, and a page's rows read on a screen that shows no
 * total. Rules: lib/list-cap-scan.mjs. Sites that exist today are pinned in
 * scripts/list-caps-baseline.json, each with why it is not a silent cap (a
 * pager with the server's total, a preview that says so, a typeahead) or the
 * defect that still owns it.
 *
 * Usage:
 *   node scripts/check-list-caps.mjs
 *   node scripts/check-list-caps.mjs --report-only
 *   node scripts/check-list-caps.mjs --write-baseline   # READ THE DIFF
 */
import { resolve } from 'node:path';

import { listCapFindings } from './lib/list-cap-scan.mjs';
import { PORTAL_DIR } from './lib/template-gate.mjs';
import { scanComponents } from './lib/component-sources.mjs';
import { runGate, isMain } from './lib/baseline.mjs';

export const gate = {
  name: 'list caps',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'list-caps-baseline.json'),
  findings: (ctx) => scanComponents((source, file) => listCapFindings(source, file), ctx),
  comment: [
    'Fixed-size list reads, as they stand today (QA D1347).',
    'Each key is "file::rule::member:detail"; "why" says why the site is not a silent cap, or its defect id.',
    'A site not listed fails the build: read every page (allRows / allPages in src/app/shared/all-pages.ts)',
    "or page on screen with the server's total, then delete its pin.",
  ],
  hint:
    "Read every page (allRows / allPages, src/app/shared/all-pages.ts) or page on screen with the server's " +
    'total; a preview or typeahead that is capped on purpose is pinned with that reason.',
};

if (isMain(import.meta.url)) runGate(gate);

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
import { readFileSync } from 'node:fs';
import { relative, resolve } from 'node:path';

import {
  listCapFindings,
  serverLimitFindings,
  serverLimitedPaths,
  serverQueryTopFindings,
  serverStreamLimitFindings,
  sliceCountFindings,
} from './lib/list-cap-scan.mjs';
import { PORTAL_DIR, scanTemplates } from './lib/template-gate.mjs';
import { walk } from './lib/walk.mjs';
import { scanComponents } from './lib/component-sources.mjs';
import { runGate, isMain } from './lib/baseline.mjs';

const PORTAL_SERVICE = 'src/app/services/patient-portal.service.ts';
const PORTAL_CONTROLLER =
  '../hospital-core/src/main/java/com/example/hms/controller/PatientPortalController.java';

/** QA D1399: a patient-portal read of a list the server caps by default, not paged. */
function portalServerLimitFindings() {
  const limited = serverLimitedPaths(readFileSync(resolve(PORTAL_DIR, PORTAL_CONTROLLER), 'utf8'));
  return serverLimitFindings(
    readFileSync(resolve(PORTAL_DIR, PORTAL_SERVICE), 'utf8'),
    limited,
  ).map((hit) => ({
    ...hit,
    file: PORTAL_SERVICE,
    id: `${PORTAL_SERVICE}::${hit.rule}::${hit.anchor}`,
  }));
}

/** The backend services, for the cuts no portal rule can see (QA CX-003). */
const SERVICES_DIR = resolve(
  PORTAL_DIR,
  '..',
  'hospital-core/src/main/java/com/example/hms/service',
);

/** Round 33: a controller calling a `findTopN` query directly is the same cut. */
const CONTROLLERS_DIR = resolve(SERVICES_DIR, '..', 'controller');

function serverFindings() {
  const out = [];
  const add = (path, hits) => {
    const file = relative(resolve(PORTAL_DIR, '..'), path).replaceAll('\\', '/');
    for (const hit of hits) out.push({ ...hit, file, id: `${file}::${hit.rule}::${hit.anchor}` });
  };
  for (const path of walk(SERVICES_DIR, ['.java'])) {
    const source = readFileSync(path, 'utf8');
    add(path, [...serverStreamLimitFindings(source), ...serverQueryTopFindings(source)]);
  }
  for (const path of walk(CONTROLLERS_DIR, ['.java'])) {
    add(path, serverQueryTopFindings(readFileSync(path, 'utf8')));
  }
  return out;
}

export const gate = {
  name: 'list caps',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'list-caps-baseline.json'),
  // QA D1362: a "+ N more" counted in a template from the rows on hand.
  findings: (ctx) => [
    ...scanComponents((source, file) => listCapFindings(source, file), ctx),
    ...scanTemplates((html) => sliceCountFindings(html), {}, ctx),
    // QA D1399: a portal read of a list the server caps by default.
    ...portalServerLimitFindings(),
    ...serverFindings(),
  ],
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

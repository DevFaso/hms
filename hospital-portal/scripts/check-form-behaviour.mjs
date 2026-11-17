#!/usr/bin/env node
/**
 * Behavioural form gate (round 29: D1256 / D1272 / D1245 class, D1329, D1330,
 * D1338, D1339).
 *
 * Every submit of the portal is driven empty by a Karma spec
 * (`expectEmptySubmitRefusal`, src/app/testing/form-refusal-behaviour.ts), and
 * every guarded form marks the fields a server 400 names under `fieldErrors`.
 * Rules: lib/form-behaviour-scan.mjs. Today's sites are pinned in
 * scripts/form-behaviour-baseline.json with their defect id; they shrink only.
 *
 * Usage:
 *   node scripts/check-form-behaviour.mjs
 *   node scripts/check-form-behaviour.mjs --report-only
 *   node scripts/check-form-behaviour.mjs --write-baseline   # READ THE DIFF
 */
import { resolve } from 'node:path';

import { formBehaviourFindings } from './lib/form-behaviour-scan.mjs';
import { PORTAL_DIR, scanTemplates } from './lib/template-gate.mjs';
import { runGate, isMain } from './lib/baseline.mjs';

export const gate = {
  name: 'form behaviour',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'form-behaviour-baseline.json'),
  findings: (ctx) =>
    scanTemplates(
      (html, componentSource, file) => formBehaviourFindings(html, componentSource, file),
      {},
      ctx,
    ),
  comment: [
    'Submits no spec drives empty, and guarded forms that never mark a server 400 fieldErrors, as they stand today.',
    'Each key is "file::rule::anchor"; "why" is the QA defect id or the reason.',
    'A site not listed fails the build: drive the submit with expectEmptySubmitRefusal (src/app/testing/form-refusal-behaviour.ts)',
    'and map fieldErrors with refusedFieldReasons + refuseFields, then delete its pin.',
  ],
  hint:
    'Give the submit a data-testid and drive it empty in the spec beside it with expectEmptySubmitRefusal; ' +
    'mark a 400 fieldErrors with refusedFieldReasons + refuseFields and assert it with expectFieldsMarked.',
};

if (isMain(import.meta.url)) runGate(gate);

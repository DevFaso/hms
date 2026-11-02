#!/usr/bin/env node
/**
 * Unmarked client-side refusal gate (QA D1072).
 *
 * A form that refuses on the browser side ("required", "missing") must mark
 * the field (`aria-invalid` + a message under it) and move the focus to it,
 * through `shared/forms/invalid-fields.ts`. Rules:
 * lib/required-refusal-scan.mjs. Sites that exist today are pinned in
 * scripts/required-refusal-baseline.json with their defect id; they shrink only.
 *
 * Usage:
 *   node scripts/check-required-refusal.mjs
 *   node scripts/check-required-refusal.mjs --report-only
 *   node scripts/check-required-refusal.mjs --write-baseline   # READ THE DIFF
 */
import { resolve } from 'node:path';

import { requiredRefusalFindings } from './lib/required-refusal-scan.mjs';
import { PORTAL_DIR } from './lib/template-gate.mjs';
import { scanComponents } from './lib/component-sources.mjs';
import { runGate, isMain } from './lib/baseline.mjs';

export const gate = {
  name: 'required refusal',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'required-refusal-baseline.json'),
  findings: (ctx) => scanComponents((source) => requiredRefusalFindings(source), ctx),
  comment: [
    'Client-side "required / missing" refusals that mark no field, as they stand today.',
    'Each key is "file::rule::method::KEY"; "why" is the QA defect id or the reason.',
    'A site not listed fails the build; call refuseFields()/focusFirstInvalid(), then delete its pin.',
  ],
  hint: 'Mark the refused field and focus it: refuseFields(host, invalidIds, allIds, message) from shared/forms/invalid-fields.',
};

if (isMain(import.meta.url)) runGate(gate);

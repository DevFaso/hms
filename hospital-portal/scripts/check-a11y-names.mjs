#!/usr/bin/env node
/**
 * Accessible-name gate (QA D988, D999, D1006, D993; earlier D173, D200).
 *
 * Round 16 reported icon buttons announced as "person_search" in the reception
 * queue, unnamed edit/delete buttons on admission rows, every /billing icon
 * button read by its icon name, and MAR/eMAR fields with no label. Shell links
 * had the same defect in round 2 (D173) and search boxes in round 3 (D200);
 * those two were guarded for their own templates only. This scan reads every
 * template: an icon-only button or an unlabelled field cannot ship again.
 *
 * Rules: lib/icon-button-scan.mjs. Sites that exist today are pinned in
 * scripts/a11y-names-baseline.json with their defect id; name the control,
 * then delete its pins.
 *
 * Usage:
 *   node scripts/check-a11y-names.mjs
 *   node scripts/check-a11y-names.mjs --report-only
 *   node scripts/check-a11y-names.mjs --write-baseline   # READ THE DIFF
 */
import { resolve } from 'node:path';

import { nameFindings } from './lib/icon-button-scan.mjs';
import { scanTemplates, PORTAL_DIR } from './lib/template-gate.mjs';
import { runGate } from './lib/baseline.mjs';

runGate({
  name: 'a11y names',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'a11y-names-baseline.json'),
  findings: scanTemplates(nameFindings),
  comment: [
    'Icon-only buttons and unlabelled fields, as they stand today.',
    'Each key is "template::rule::anchor"; "count" is how many such controls the',
    'template holds and "why" the QA defect id (or the reason a person wrote).',
    'A control not listed fails the build; name a control, then delete its pin.',
  ],
  hint:
    'Icon-only button: [attr.aria-label]="\'SECTION.ACTION\' | translate" and aria-hidden="true" on the icon. ' +
    'Field: a <label for>, or [attr.aria-label] on the control.',
});

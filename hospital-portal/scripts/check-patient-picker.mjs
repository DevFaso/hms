#!/usr/bin/env node
/**
 * Shared patient-picker gate (QA D947; D943/D942 as seen from each copy).
 *
 * The portal has one patient picker, <app-patient-picker>. Round 16 found the
 * new-appointment form's hand-written one unusable from the keyboard (D947);
 * earlier rounds found other copies that missed accents, phone formats or
 * the hospital scope. Each copy gets it wrong its own way, so the fix is not
 * to patch the copy but to stop new copies: this gate fails on any template
 * that pairs a patient search input with its own suggestion list.
 *
 * Rules: lib/patient-picker-scan.mjs. Copies that exist today are pinned in
 * scripts/patient-picker-baseline.json; replace one with the shared picker,
 * then delete its pin.
 *
 * Usage:
 *   node scripts/check-patient-picker.mjs
 *   node scripts/check-patient-picker.mjs --report-only
 *   node scripts/check-patient-picker.mjs --write-baseline   # READ THE DIFF
 */
import { resolve } from 'node:path';

import { pickerFindings, PICKER_COMPONENT } from './lib/patient-picker-scan.mjs';
import { scanTemplates, PORTAL_DIR } from './lib/template-gate.mjs';
import { runGate } from './lib/baseline.mjs';

runGate({
  name: 'patient picker',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'patient-picker-baseline.json'),
  findings: scanTemplates(pickerFindings, { skip: (rel) => rel.startsWith(PICKER_COMPONENT) }),
  comment: [
    'Hand-written patient pickers (search input + own suggestion list), as they',
    'stand today. Each key is "template::rule::<iterable>"; "why" is the QA',
    'defect id or the reason. A copy not listed fails the build; replace a copy',
    'with <app-patient-picker>, then delete its pin.',
  ],
  hint: 'Replace the input + list with <app-patient-picker (selected)="..."> (src/app/shared/patient-picker).',
});

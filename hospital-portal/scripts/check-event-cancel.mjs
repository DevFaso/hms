#!/usr/bin/env node
/**
 * Cancelled-event gate (QA D1451, round 35): a template event expression that
 * can evaluate to `false` on an element holding a field, a label, a link or a
 * button. Angular cancels the event, and the control inside stops working.
 * Rules: lib/event-cancel-scan.mjs.
 *
 * Usage:
 *   node scripts/check-event-cancel.mjs
 *   node scripts/check-event-cancel.mjs --report-only
 *   node scripts/check-event-cancel.mjs --write-baseline   # READ THE DIFF
 */
import { resolve } from 'node:path';

import { eventCancelFindings } from './lib/event-cancel-scan.mjs';
import { PORTAL_DIR, scanTemplates } from './lib/template-gate.mjs';
import { runGate, isMain } from './lib/baseline.mjs';

export const gate = {
  name: 'cancelled event',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'event-cancel-baseline.json'),
  findings: (ctx = {}) =>
    scanTemplates((html, source) => eventCancelFindings(html, source), {}, ctx),
  comment: [
    'Template event expressions that can answer false, as they stand today (QA D1451). It must stay empty.',
  ],
  hint: 'A backdrop takes `appBackdropClose (backdropClose)="close()"`; anything else calls a method returning void.',
};

if (isMain(import.meta.url)) runGate(gate);

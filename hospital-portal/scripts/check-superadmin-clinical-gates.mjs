#!/usr/bin/env node
/**
 * Super-admin clinical action gate (QA D2120, r59): a component permission that offers a
 * clinical action to ROLE_SUPER_ADMIN. Rules: lib/superadmin-clinical-gate-scan.mjs.
 *
 * Usage:
 *   node scripts/check-superadmin-clinical-gates.mjs
 *   node scripts/check-superadmin-clinical-gates.mjs --report-only
 *   node scripts/check-superadmin-clinical-gates.mjs --write-baseline   # READ THE DIFF
 */
import { resolve } from 'node:path';

import { superAdminClinicalGateFindings } from './lib/superadmin-clinical-gate-scan.mjs';
import { scanComponents } from './lib/component-sources.mjs';
import { PORTAL_DIR } from './lib/template-gate.mjs';
import { runGate, isMain } from './lib/baseline.mjs';

export const gate = {
  name: 'super-admin clinical action',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'superadmin-clinical-gates-baseline.json'),
  findings: (ctx) => scanComponents((source) => superAdminClinicalGateFindings(source), ctx),
  comment: [
    'Component permissions that offer an action to ROLE_SUPER_ADMIN alongside a clinical role (QA D2120).',
    'Each pin is a NON-clinical action (configuration, demographics, records office) with its reason;',
    'a clinical action is never pinned: drop ROLE_SUPER_ADMIN from its list, as the server does.',
  ],
  hint: "Remove 'ROLE_SUPER_ADMIN' from the list: the server gates clinical writes with ClinicalActor.holdsOwnRole.",
};

if (isMain(import.meta.url)) runGate(gate);

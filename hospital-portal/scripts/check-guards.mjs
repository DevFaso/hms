#!/usr/bin/env node
/**
 * The six round-16 regression gates in one process (`npm run guards`).
 *
 * Each gate can run on its own (`node scripts/check-a11y-dialogs.mjs`), but CI
 * runs them together: the templates (every `.html` plus every inline
 * `template:`, parsed with the TypeScript compiler) and the `.ts` sources are
 * read ONCE and handed to all six, and every gate is evaluated even when an
 * earlier one failed — so one push shows every class that regressed, not just
 * the first.
 *
 * Flags are passed through: `--report-only` prints and never fails,
 * `--write-baseline` rewrites every gate's pins (read the diff).
 *
 * The gates and what each catches: docs/qa/regression-guards.md.
 */
import { evaluateGate } from './lib/baseline.mjs';
import { loadTemplates } from './lib/template-gate.mjs';
import { componentSources } from './lib/component-sources.mjs';
import { gate as dialogs } from './check-a11y-dialogs.mjs';
import { gate as names } from './check-a11y-names.mjs';
import { gate as picker } from './check-patient-picker.mjs';
import { gate as money } from './check-money-format.mjs';
import { gate as literals } from './check-ts-literals.mjs';
import { gate as fragments } from './check-uuid-fragments.mjs';

const argv = process.argv.slice(2);
const ctx = { templates: loadTemplates(), components: componentSources() };

let failed = 0;
for (const gate of [dialogs, names, picker, money, literals, fragments]) {
  if (!evaluateGate(gate, argv, ctx)) failed++;
}
if (failed > 0) {
  console.error(`\n${failed} regression gate(s) failed — see docs/qa/regression-guards.md`);
  process.exit(1);
}

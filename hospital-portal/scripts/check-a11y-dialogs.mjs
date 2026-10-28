#!/usr/bin/env node
/**
 * Dialog-semantics gate (QA D976, D988, D1004, D968).
 *
 * Every modal the portal opens must be a dialog to assistive technology:
 * role="dialog", aria-modal="true", a name (aria-labelledby → its title) and an
 * Escape handler in its component. Round 16 found four billing modals without
 * a role, Escape dead on all of them, a check-in dialog that is not a modal and
 * an outpatient triage dialog with no semantics at all — the same class QA
 * reported on earlier rounds (D173). This scan reads every template, so the
 * next modal cannot ship without them.
 *
 * Rules and the anchor format: lib/dialog-scan.mjs. Sites that exist today are
 * pinned in scripts/a11y-dialogs-baseline.json with the defect id they belong
 * to; fix the dialog, then delete its pins (the gate reports a pin whose site
 * is gone).
 *
 * Usage:
 *   node scripts/check-a11y-dialogs.mjs
 *   node scripts/check-a11y-dialogs.mjs --report-only
 *   node scripts/check-a11y-dialogs.mjs --write-baseline   # READ THE DIFF
 */
import { resolve } from 'node:path';

import { dialogFindings } from './lib/dialog-scan.mjs';
import { dialogComponentSelectors } from './lib/component-sources.mjs';
import { scanTemplates, PORTAL_DIR } from './lib/template-gate.mjs';
import { runGate } from './lib/baseline.mjs';

const childDialogs = dialogComponentSelectors();

runGate({
  name: 'a11y dialogs',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'a11y-dialogs-baseline.json'),
  findings: scanTemplates((html, componentSource) =>
    dialogFindings(html, componentSource, childDialogs),
  ),
  comment: [
    'Dialogs that lack role/aria-modal/a name/Escape, as they stand today.',
    'Each key is "template::rule::anchor"; "count" is how many such sites the',
    'template holds and "why" the QA defect id (or the reason a person wrote).',
    'A site not listed fails the build; fix a dialog, then delete its pins.',
  ],
  hint:
    'Give the modal role="dialog" aria-modal="true" aria-labelledby="<title id>" and close it on (keydown.escape) — ' +
    'see shared/patient-picker or reception/walkin-dialog for the house shape.',
});

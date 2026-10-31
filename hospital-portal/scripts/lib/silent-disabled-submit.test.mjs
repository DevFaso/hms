// QA D1124 / D1089 / D1069 guard (rounds 19 and 20): no control is silently
// disabled.
//
// Round 19 (D1089): PR #981 promised "Saisissez un montant supérieur à zéro"
// in the patient's pay dialog, but "Payer" was bound to
// `[disabled]="payProcessing() || !payAmount || payAmount <= 0"`, so the
// handler that shows the message could never run. The round-19 guard looked
// only at amount-shaped names (and reasons on billing / pharmacy screens).
//
// Round 20 (D1124, NOT_FIXED twice): the same dead button sat on every staff
// area and the portal behind names that guard could not see — `canSubmit`,
// `quantityDispensed`, `verifyAttested`, `patientId`, an acknowledgement flag,
// a lab void / reject / collect reason, the MAR reasons.
//
// The rule now (scripts/lib/disabled-control-scan.mjs): every way a control
// is switched off (`disabled`, `[disabled]`, `[attr.disabled]`,
// `aria-disabled`, `[class.disabled]`, an `[ngClass]` naming `disabled`, a
// `disabled` class, a disabled `<fieldset>`) may read only "a request is in
// flight" (`saving()`, `busy`, `actingOnId() === row.id`) or "a pager is at its
// end", followed into the component members it calls. Anything else stays
// clickable behind `appSubmitGuard` (shared/forms/submit-guard.directive.ts),
// which refuses the click, marks the blank fields and says why next to the
// button — or carries an `aria-describedby` naming the visible reason.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import {
  countDisablings,
  disablings,
  inFlight,
  silentDisabledFindings,
  silentReason,
} from './disabled-control-scan.mjs';
import { tags } from './html-tags.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

/**
 * Pinned files: none. The round-20 branches pinned admissions, appointment
 * detail, bed board and the nursing intake while another branch owned those
 * files; the merge into qa/r16-combined fixed every one the same way
 * (`[disabled]` for the request in flight, the rest in `[appSubmitGuard]`).
 * A new pin needs a written reason, and a pin whose file no longer offends
 * fails the test, so this list can only shrink.
 */
const PINS = new Map();

function scanApp() {
  const offenders = new Map();
  let bindings = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    const tsFile = file.endsWith('.html') ? file.replace(/\.html$/, '.ts') : file;
    const componentTs = existsSync(tsFile) ? readFileSync(tsFile, 'utf8') : '';
    bindings += countDisablings(text);
    for (const f of silentDisabledFindings(text, componentTs)) {
      const expr = (f.expr ?? '').replace(/\s+/g, ' ').trim();
      const list = offenders.get(rel) ?? [];
      list.push(`${rel}:${f.line + lineOffset}: ${f.how}="${expr}" waits on ${f.word}`);
      offenders.set(rel, list);
    }
  }
  return { offenders, bindings };
}

test('QA D1124 / D1089: no control is disabled for anything but a request in flight or a pager end', () => {
  const { offenders, bindings } = scanApp();
  assert.ok(bindings > 400, `scanned ${bindings} disabling bindings — the scan went blind`);
  const unpinned = [...offenders].filter(([rel]) => !PINS.has(rel)).flatMap(([, list]) => list);
  assert.deepEqual(
    unpinned,
    [],
    'a disabled button hides why: keep [disabled] for the request in flight and move the rest to [appSubmitGuard] (shared/forms/submit-guard.directive.ts)',
  );
  const stale = [...PINS.keys()].filter((rel) => !offenders.has(rel));
  assert.deepEqual(stale, [], 'a pinned file no longer offends: remove its pin');
});

test('QA D1124 guard: the scan sees every shape the defect had', () => {
  // Round 19 (amounts) and round 20 (fields, flags, reasons, getters, computeds).
  assert.equal(silentReason('payProcessing() || !payAmount || payAmount <= 0'), 'payAmount');
  assert.equal(silentReason('saving() || !form.medicationName'), 'form.medicationName');
  assert.equal(
    silentReason('saving() || form.quantityDispensed <= 0 || !verifyAttested()'),
    'form.quantityDispensed',
  );
  assert.equal(silentReason('voiding() || !voidReason().trim()'), 'voidReason');
  assert.equal(
    silentReason(
      '!canSubmit',
      '  get canSubmit(): boolean {\n    return this.consentAcknowledged && !this.saving();\n  }\n',
    ),
    'canSubmit → consentAcknowledged',
  );
  assert.equal(
    silentReason(
      '!canVerify()',
      '  protected readonly canVerify = computed(() => this.patientScan().trim().length > 0);\n',
    ),
    'canVerify → patientScan',
  );
  // What may disable a control.
  assert.equal(silentReason('saving()'), null);
  assert.equal(silentReason('addendumSaving() || !!signingId()'), null);
  assert.equal(silentReason('actingOnId() === row.id'), null);
  assert.equal(silentReason('state.busy || logoutPanel().busy'), null);
  assert.equal(silentReason('page() === 0'), null);
  assert.equal(silentReason('currentPage() >= totalPages() - 1'), null);
  // A gerund that names data is not a request in flight.
  assert.equal(inFlight('pendingAttachments'), false);
  assert.equal(inFlight('existingHistory'), false);
  assert.equal(inFlight('uploadingPhoto'), true);
});

test('QA D1124 guard: every way of switching a control off is read', () => {
  const kinds = (html) =>
    tags(html)
      .filter((t) => t.type === 'open')
      .flatMap((t) => disablings(t).map((d) => d.how));
  assert.deepEqual(kinds('<button [disabled]="x">'), ['[disabled]']);
  assert.deepEqual(kinds('<button [attr.disabled]="x ? true : null">'), ['[attr.disabled]']);
  assert.deepEqual(kinds('<a [attr.aria-disabled]="!ok">'), ['[attr.aria-disabled]']);
  assert.deepEqual(kinds('<button aria-disabled="true">'), ['aria-disabled']);
  assert.deepEqual(kinds('<button aria-disabled="false">'), []);
  assert.deepEqual(kinds('<button disabled>'), ['disabled']);
  assert.deepEqual(kinds('<button [class.disabled]="!ok">'), ['[class.disabled]']);
  assert.deepEqual(kinds('<a class="btn disabled">'), ['class="disabled"']);
  assert.deepEqual(kinds('<button [ngClass]="{ disabled: !ok }">'), ['[ngClass]']);

  // Found on a button, a link, an input of type submit, a role="button" and a fieldset.
  const found = (html) => silentDisabledFindings(html).map((f) => f.how);
  assert.deepEqual(found('<button [disabled]="!form.patientId">x</button>'), ['[disabled]']);
  assert.deepEqual(found('<a role="button" [class.disabled]="!ready">x</a>'), ['[class.disabled]']);
  assert.deepEqual(found('<input type="submit" [attr.disabled]="!ok ? true : null">'), [
    '[attr.disabled]',
  ]);
  assert.deepEqual(found('<span role="button" aria-disabled="true">x</span>'), ['aria-disabled']);
  assert.deepEqual(found('<fieldset [disabled]="!scopeReady()"></fieldset>'), ['[disabled]']);
  assert.deepEqual(found('<button disabled>x</button>'), ['disabled']);
  // The two ways out: in flight only, or a visible reason named.
  assert.deepEqual(found('<button [disabled]="saving()" [appSubmitGuard]="!x">x</button>'), []);
  assert.deepEqual(
    found('<button disabled aria-describedby="why">x</button><p id="why">Bientôt</p>'),
    [],
  );
  assert.deepEqual(
    found('<button disabled aria-describedby="nowhere">x</button>'),
    ['disabled'],
    'an aria-describedby that names nothing on the page is not a visible reason',
  );
});

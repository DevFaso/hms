// QA D1256 class closure (round 27: D1277, D1278, D1279 — D1089 / D1124 /
// D1245 NOT_FIXED again): a form whose refusal names the missing fields ONE AT
// A TIME.
//
// Round 26 made the submit guard name every blank field at once, and fixed
// the forms QA named. Round 27 found the class on the paths the guard never
// sees: a submit handler that checks its fields in a row of
// `if (!a) { toast(A_REQUIRED); return; } if (!b) { toast(B_REQUIRED); … }`
// (the billing adjustment form: the reason, then the amount, then the line,
// one press each, no field marked), and a guard reason that answered one
// rule, then the next on the following press (empty triage: "Score ESI", then
// "at least one vital sign"). The reader learns the form one field per press.
//
// Rule `one-at-a-time`, over every template (each `.html` and each inline
// `template:`) and the component that owns it: the handler a submit calls —
// a `<form>`'s `(ngSubmit)` / `(submit)`, or a button's `(click)` — shows two
// or more different "required / missing" messages (a `'…REQUIRED…'` /
// `'…MISSING…'` key, through `instant(` or handed to the template; the per-field
// `COMMON.FIELD_REQUIRED` under a field is not one), and nothing in front of
// it names them all first: the button (or, for a form, one of its submit
// buttons) carries no `appSubmitGuard` / `[appSubmitGuard]` — the guard stops
// the click before the handler and names every blank field at once — and
// the handler does not aggregate through `missingRequiredMessage(` /
// `joinReasons(` (shared/required-fields.ts).
//
// Rule `reason-one-at-a-time`: a member bound to `[appSubmitGuard]` that
// returns two or more different translated reasons one after the other, or
// returns `true` (the blank fields) before a reason. The guard says what it
// is given; the second rule is said only on the next press. Several rules
// are said at once through `joinReasons(` (shared/required-fields.ts), and
// the guard names the blank required fields together with a reason.
//
// A handler whose two messages are not two blank fields (a missing hospital
// context and a blank field; a witness rule) is listed in ALLOWED with why.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';
import { closeIndexOf } from './dialog-scan.mjs';
import { memberBody } from './disabled-control-scan.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

// A message key, shown through `instant('…')` or handed to the template as the
// key itself (`this.verifyError = 'ONBOARDING.ERR_PHONE_REQUIRED'`).
const REFUSAL = /'([A-Z0-9_]+(?:\.[A-Z0-9_]+)*\.[A-Z0-9_]*(?:REQUIRED|MISSING)[A-Z0-9_]*)'/g;
/** Under each blank field: never the reason for the whole form. */
const PER_FIELD = new Set(['COMMON.FIELD_REQUIRED']);
/** A handler that names every blank field in one message. */
const AGGREGATES = /\bmissingRequiredMessage\(|\bjoinReasons\(/;

/**
 * Handlers whose different messages are not blank fields of one press, each
 * with why. Key: `<template path>::<handler>`.
 */
export const ALLOWED = new Map([
  [
    'login/login.html::submit',
    'CREDENTIALS_REQUIRED names both fields at once; TOKEN_MISSING is a server answer, not a field',
  ],
  [
    'pharmacy/pharmacy-checkout.html::submit',
    'CHECKOUT_USER_OR_HOSPITAL_MISSING is a lost session context, not a field; the reference is the only field',
  ],
  [
    'checkout/checkout-dialog/checkout-dialog.component.html::checkoutBlockReason',
    'SUBMIT_GUARD.ENCOUNTER is the visit the dialog was opened for (a context, never blank in use); the summary is the one rule',
  ],
  [
    'discharge/discharge.html::requestBlockReason',
    'the stay states (not admitted, still loading) are read FOR the chosen patient: they cannot be said before a patient is chosen',
  ],
  [
    'maternity/maternity.html::historyBlockReason',
    'the existing-history states are read FOR the chosen patient: they cannot be said before a patient is chosen',
  ],
  [
    'nurse-station/nursing-intake-form/nursing-intake-form.component.html::submitBlockReason',
    'INTAKE.NO_ENCOUNTER is the encounter the dialog was opened for (a context); the assessment is the one rule',
  ],
  [
    'pharmacy/stock-routing.html::routeBlockReason',
    'the prescription list is loaded FOR the chosen hospital scope: it cannot be asked for before a hospital is chosen',
  ],
]);

/** The method a binding calls (`save($event)` → `save`). */
function handlerName(binding) {
  return /^\s*(?:this\.)?([A-Za-z_$][\w$]*)\s*\(/.exec(binding ?? '')?.[1] ?? null;
}

const isGuarded = (t) => t.attrs.has('[appSubmitGuard]') || t.attrs.has('appSubmitGuard');

/** The different whole-form "required / missing" messages a method body shows. */
export function refusalKeys(body) {
  return [...new Set([...body.matchAll(REFUSAL)].map((m) => m[1]))].filter(
    (k) => !PER_FIELD.has(k),
  );
}

/**
 * The unguarded submit handlers of a template that refuse field by field,
 * one message per press: `{ line, handler, keys }`.
 */
export function oneAtATimeFindings(html, componentTs = '') {
  const tokens = tags(html);
  const out = [];
  const seen = new Set();
  const report = (line, handler) => {
    if (!handler || seen.has(handler)) return;
    const body = memberBody(componentTs, handler);
    if (AGGREGATES.test(body)) return;
    const keys = refusalKeys(body);
    if (keys.length < 2) return;
    seen.add(handler);
    out.push({ line, handler, keys });
  };
  tokens.forEach((t, i) => {
    if (t.type !== 'open') return;
    if (t.name === 'form') {
      const inner = tokens.slice(i + 1, closeIndexOf(tokens, i)).filter((x) => x.type === 'open');
      const submits = inner.filter(
        (x) =>
          x.name === 'button' &&
          (x.attrs.get('type') ?? 'submit').replace(/["']/g, '') === 'submit',
      );
      if (submits.some(isGuarded)) return;
      report(t.line, handlerName(t.attrs.get('(ngSubmit)') ?? t.attrs.get('(submit)')));
      return;
    }
    if (t.name === 'button' && !isGuarded(t)) report(t.line, handlerName(t.attrs.get('(click)')));
  });
  return out;
}

/**
 * Why a guard reason method answers one rule per press, or null: two or more
 * different translated reasons returned one after the other (only the first
 * is ever said), or `return true` ("the blank fields", which the guard names)
 * BEFORE a returned reason — the reason then waits for the fields. Returning
 * the reason first and `true` last is the fixed shape: the guard names the
 * blank required fields together with the reason.
 */
export function reasonProblem(body) {
  const reasons = [...body.matchAll(/return\s+(?:this\.)?translate\.instant\(\s*'([A-Z0-9_.]+)'/g)];
  const keys = [...new Set(reasons.map((m) => m[1]))];
  if (keys.length >= 2) return keys;
  const early = /\breturn\s+true\s*;/.exec(body);
  if (early && reasons.some((m) => m.index > early.index)) return [...keys, 'true (first)'];
  return null;
}

/** Guard reason methods that answer one rule per press: `{ line, member, keys }`. */
export function reasonOneAtATimeFindings(html, componentTs = '') {
  const out = [];
  const seen = new Set();
  for (const t of tags(html)) {
    if (t.type !== 'open') continue;
    const member = handlerName(t.attrs.get('[appSubmitGuard]'));
    if (!member || seen.has(member)) continue;
    seen.add(member);
    const keys = reasonProblem(memberBody(componentTs, member));
    if (keys) out.push({ line: t.line, member, keys });
  }
  return out;
}

function scan() {
  const found = [];
  let handlers = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    const tsFile = file.endsWith('.html') ? file.replace(/\.html$/, '.ts') : file;
    const componentTs = existsSync(tsFile) ? readFileSync(tsFile, 'utf8') : '';
    handlers += tags(text).filter((t) => t.type === 'open' && t.attrs.has('(click)')).length;
    for (const f of oneAtATimeFindings(text, componentTs)) {
      found.push({
        key: `${rel}::${f.handler}`,
        at: `${rel}:${f.line + (lineOffset ?? 0)}`,
        what: f.keys.join(', '),
      });
    }
    for (const f of reasonOneAtATimeFindings(text, componentTs)) {
      found.push({
        key: `${rel}::${f.member}`,
        at: `${rel}:${f.line + (lineOffset ?? 0)}`,
        what: `returns ${f.keys.join(', ')}`,
      });
    }
  }
  return { found, handlers };
}

test('QA D1256 class: no form names its missing fields one at a time', () => {
  const { found, handlers } = scan();
  assert.ok(handlers > 500, `every submit must be read, saw ${handlers} click handlers`);
  const offenders = found.filter((f) => !ALLOWED.has(f.key)).map((f) => `${f.at} ${f.what}`);
  assert.deepEqual(
    offenders,
    [],
    'name every blank field at once: appSubmitGuard on the button, or missingRequiredMessage() / joinReasons() (shared/required-fields)',
  );
  // An entry that no longer excuses anything goes: the list only shrinks.
  const stale = [...ALLOWED.keys()].filter((k) => !found.some((f) => f.key === k));
  assert.deepEqual(stale, [], 'remove the ALLOWED entries that excuse nothing');
});

test('QA D1256 guard: the scan sees the round-27 shapes and accepts the fixed ones', () => {
  // D1278: the adjustment form — reason, then line, then amount, one press each.
  const html = '<div><button type="button" (click)="submitAdjustment()">Appliquer</button></div>';
  const ts = [
    '  submitAdjustment(): void {',
    "    if (!reason) { this.toast.error(this.translate.instant('BILLING.ADJUSTMENT_REASON_REQUIRED')); return; }",
    "    if (!line) { this.toast.error(this.translate.instant('BILLING.ADJUSTMENT_LINE_REQUIRED')); return; }",
    '  }',
  ].join('\n');
  assert.equal(oneAtATimeFindings(html, ts).length, 1);
  // Guarded: the guard names every blank field before the handler runs.
  const guarded = html.replace('type="button"', 'type="button" appSubmitGuard submitGuardRequired');
  assert.deepEqual(oneAtATimeFindings(guarded, ts), []);
  // Aggregated in the handler.
  const aggregated = ts.replace(
    '  submitAdjustment(): void {',
    '  submitAdjustment(): void {\n    const missing = missingRequiredMessage(t, f);',
  );
  assert.deepEqual(oneAtATimeFindings(html, aggregated), []);
  // The per-field message is not a second reason.
  const perField = ts.replace('BILLING.ADJUSTMENT_LINE_REQUIRED', 'COMMON.FIELD_REQUIRED');
  assert.deepEqual(oneAtATimeFindings(html, perField), []);
  // A form whose submit button is guarded is not an instance; an unguarded one is.
  const form = '<form (ngSubmit)="submitAdjustment()"><button type="submit">OK</button></form>';
  assert.equal(oneAtATimeFindings(form, ts).length, 1);
  assert.deepEqual(
    oneAtATimeFindings(
      form.replace('type="submit"', 'type="submit" appSubmitGuard submitGuardRequired'),
      ts,
    ),
    [],
  );

  // D1277: the triage guard reason — ESI first, the vital sign on the next press.
  const triage = '<button [appSubmitGuard]="submitBlockReason()">Soumettre</button>';
  const reasonTs = [
    '  submitBlockReason(): string | boolean {',
    '    if (!this.encounter?.id || !this.esiChosen()) return true;',
    "    if (!this.hasVitalSign()) return this.translate.instant('TRIAGE.VITAL_REQUIRED') as string;",
    '    return false;',
    '  }',
  ].join('\n');
  assert.equal(reasonOneAtATimeFindings(triage, reasonTs).length, 1);
  // Two reasons in a row: only the first is ever said.
  const twoReasons = reasonTs.replace(
    'return true;',
    "return this.translate.instant('SUBMIT_GUARD.QUANTITY');",
  );
  assert.equal(reasonOneAtATimeFindings(triage, twoReasons).length, 1);
  // The fixed shape: the rule first, `true` last; the guard names the blank fields with it.
  const oneReason = [
    '  submitBlockReason(): string | boolean {',
    "    if (!this.hasVitalSign()) return this.translate.instant('TRIAGE.VITAL_REQUIRED') as string;",
    '    return !this.encounter?.id || !this.esiChosen();',
    '  }',
  ].join('\n');
  assert.deepEqual(reasonOneAtATimeFindings(triage, oneReason), []);
});

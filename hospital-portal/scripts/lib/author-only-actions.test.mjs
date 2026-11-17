// QA D1081 guard (the D995 class): an author-only action offered to everyone.
//
// D995 (round 16) made the server's refusal readable (a toast, not the 403
// page). Round 18 found the same lists still OFFERING "Annuler" on an imaging
// order and a transfusion request to a doctor who did not write it: he
// confirmed, then the server refused him. The action is the author's, so the
// row must decide whether to offer it.
//
// This reads every template in the app. A button whose click ends a record —
// cancels, withdraws or voids it (`confirmCancel(…)`, `cancelRequest(…)`,
// `openCancelPrompt()`, `openAction('cancel')`, `openVoid(…)` …) — must sit
// inside an `@if` that calls a predicate of its component whose body asks the
// shared rule, `AuthorshipService.isAuthor` (src/app/shared/authorship.service.ts).
// A site whose decision is NOT the author's (a supervisor's void, a team-owned
// record, a server-supplied flag, a modal's own Cancel) is listed below with
// its reason; a listed site that no longer exists fails too, so the list never
// outlives what it excused.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { componentSourceFor } from './template-gate.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const appDir = join(here, '..', '..', 'src', 'app');

/** A click that ends a record. */
const ENDING_CLICK =
  /\(click\)="\s*((?:confirmCancel|cancelRequest|cancelOrder|cancelReferral|openCancel\w*|openVoid\w*|openWithdraw\w*|withdraw\w*|void\w*)\s*\([^"]*\)|open\w*Action\(\s*'(?:cancel|void|withdraw)'\s*\))/g;

/** Sites whose decision is not the author's, and why (file :: handler). */
const NOT_AUTHOR_OWNED = {
  'lab/lab.html :: openVoid(order)':
    'supervisor path: entered-in-error is a lab manager’s or director’s decision (ClinicalDocumentAuthorGuardTest TEAM_OWNED LabOrderServiceImpl.voidLabOrder)',
  'referrals/referrals.html :: confirmCancel(r)':
    'already per row on the referring provider (canCancel compares referringProviderId with the session staff id, QA D1026)',
  'referrals/referrals.html :: confirmCancel(ref)':
    'already per row on the referring provider (canCancel compares referringProviderId with the session staff id, QA D1026)',
  'appointments/appointment-detail.html :: openCancelConfirm':
    'an appointment is not a clinical record: the desk or the patient cancels it (appointment policy, not authorship)',
  'patient-portal/my-appointments/my-appointments.component.html :: openCancel':
    'the patient cancels her own appointment (the server scopes it to her)',
  'pharmacy/dispensing.html :: openCancelReady(rx)':
    'a ready-for-pickup fill is the pharmacy’s to release, not a clinician’s order (pharmacist workflow)',
};

/** The `@if` conditions that enclose `index` in a prettier-formatted template. */
export function enclosingConditions(text, index) {
  const lines = text.slice(0, index).split('\n');
  const stack = [];
  let pending = null;
  for (const raw of lines) {
    const line = raw.trim();
    if (pending !== null) {
      pending += ' ' + line;
      if (/\)\s*\{$/.test(line)) {
        stack.push(pending.replace(/\)\s*\{$/, ''));
        pending = null;
      }
      continue;
    }
    // `} @else if (…) {` / `} @else {` close one block and open the next.
    const closeOpen = /^\}\s*@else(?:\s+if\s*\((.*)\))?\s*\{$/.exec(line);
    if (closeOpen) {
      stack.pop();
      stack.push(closeOpen[1] ?? '');
      continue;
    }
    const ifOpen = /^@if\s*\((.*)$/.exec(line);
    if (ifOpen) {
      if (/\)\s*\{$/.test(line)) stack.push(ifOpen[1].replace(/\)\s*\{$/, ''));
      else pending = ifOpen[1];
      continue;
    }
    if (/^@(for|switch|case|default|empty|defer|placeholder|loading|error)\b.*\{$/.test(line)) {
      stack.push('');
      continue;
    }
    if (/^\}$/.test(line)) stack.pop();
  }
  return stack.filter(Boolean);
}

/** The body of a method `name(` declared in the component source, or ''. */
function methodBody(source, name) {
  // Declared at class-member indentation (two spaces), never a call site.
  const decl = new RegExp(`\\n {2}(?:readonly\\s+)?${name}\\s*(?:\\(|=\\s*computed\\()`);
  const m = decl.exec(source);
  if (!m) return '';
  // The member ends at the first line back at class-member indentation: a
  // method's closing `}`, a multi-line `computed(…)`'s `);`, or the next member.
  const rest = source.slice(m.index + 1);
  const next = /\n {2}\S/.exec(rest);
  return next ? rest.slice(0, next.index + 4) : rest;
}

/** True when the condition calls a predicate that asks the shared author rule. */
export function asksAuthorRule(conditions, source, depth = 0) {
  for (const cond of conditions) {
    for (const [, name] of cond.matchAll(/\b(\w+)\s*\(/g)) {
      const body = methodBody(source, name);
      if (/\bauthorship\.isAuthor\(/.test(body)) return true;
      if (depth < 1 && body && asksAuthorRule([body.slice(body.indexOf('{'))], source, depth + 1)) {
        return true;
      }
    }
  }
  return false;
}

export function endingActions(text) {
  return [...text.matchAll(ENDING_CLICK)].map((m) => ({
    index: m.index,
    handler: m[1].replace(/\s+/g, ' '),
    line: text.slice(0, m.index).split('\n').length,
  }));
}

test('QA D1081: an action that ends a clinical record is offered per row on the author rule', () => {
  const problems = [];
  const seen = new Set();
  for (const { file, text } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    const source = componentSourceFor(file, text);
    for (const action of endingActions(text)) {
      const handlerName = action.handler.split('(')[0];
      const key = `${rel} :: ${action.handler}`;
      const keyByName = `${rel} :: ${handlerName}`;
      const listed = NOT_AUTHOR_OWNED[key] ? key : NOT_AUTHOR_OWNED[keyByName] ? keyByName : null;
      if (listed) {
        seen.add(listed);
        continue;
      }
      // A modal's own Cancel/close button is not an action on a record.
      if (/^(cancelAction|cancelDelete|cancelEdit|cancelForm|cancelHoldRefuse)\b/.test(handlerName))
        continue;
      if (!asksAuthorRule(enclosingConditions(text, action.index), source)) {
        problems.push(
          `${rel}:${action.line}: "${action.handler}" is offered without the author rule ` +
            '(gate it in an @if on a predicate that calls this.authorship.isAuthor(row), ' +
            'or list it with the reason the decision is not the author’s)',
        );
      }
    }
  }
  for (const key of Object.keys(NOT_AUTHOR_OWNED)) {
    if (!seen.has(key)) problems.push(`stale entry: ${key} no longer exists — delete it`);
  }
  assert.deepEqual(
    problems,
    [],
    problems.join('\n') +
      '\n\nFixing a QA defect? Read docs/qa/regression-guards.md ("How a fix PR uses these guards").',
  );
});

/**
 * QA D1098 (round 19): a cancel button offered on an order that already has
 * its result. The imaging list hid it for COMPLETED/FINAL/CANCELLED but not
 * RESULTS_AVAILABLE; every order list kept its own list of closed statuses.
 * A cancel of an ORDER must also sit behind the shared status rule,
 * `isOrderCancellable` (src/app/shared/order-lifecycle.ts). The records that
 * are not orders are listed with their reason.
 */
const NOT_AN_ORDER = {
  'appointments/appointment-detail.html :: openCancelConfirm':
    'an appointment, not an order (appointment policy)',
  'patient-portal/my-appointments/my-appointments.component.html :: openCancel':
    'the patient’s own appointment, not an order',
  'pharmacy/dispensing.html :: openCancelReady(rx)':
    'a ready-for-pickup fill released by the pharmacy, not the order itself',
  'lab/lab.html :: openVoid(order)':
    'the void as entered in error IS the correction path of a resulted lab order (isVoidable decides)',
  'discharge/discharge.html :: openCancel(a)':
    'a pending discharge approval request, not an order (canCancelApproval admits only PENDING)',
};

/** True when the condition (or a predicate it calls, one level down) asks `isOrderCancellable`. */
export function asksCancellableRule(conditions, source, depth = 0) {
  for (const cond of conditions) {
    if (/\bisOrderCancellable\(/.test(cond)) return true;
    for (const [, name] of cond.matchAll(/\b(\w+)\s*\(/g)) {
      const body = methodBody(source, name);
      if (/\bisOrderCancellable\(/.test(body)) return true;
      if (
        depth < 1 &&
        body &&
        asksCancellableRule([body.slice(body.indexOf('{'))], source, depth + 1)
      ) {
        return true;
      }
    }
  }
  return false;
}

test('QA D1098: a cancel of an order is offered only while the order has no result', () => {
  const problems = [];
  const seen = new Set();
  for (const { file, text } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    const source = componentSourceFor(file, text);
    for (const action of endingActions(text)) {
      const handlerName = action.handler.split('(')[0];
      if (/^(cancelAction|cancelDelete|cancelEdit|cancelForm|cancelHoldRefuse)\b/.test(handlerName))
        continue;
      const key = `${rel} :: ${action.handler}`;
      const keyByName = `${rel} :: ${handlerName}`;
      const listed = NOT_AN_ORDER[key] ? key : NOT_AN_ORDER[keyByName] ? keyByName : null;
      if (listed) {
        seen.add(listed);
        continue;
      }
      if (!asksCancellableRule(enclosingConditions(text, action.index), source)) {
        problems.push(
          `${rel}:${action.line}: "${action.handler}" is offered without the shared status rule ` +
            '(gate it on a predicate that calls isOrderCancellable(row.status) from shared/order-lifecycle, ' +
            'or list it with the reason the record is not an order)',
        );
      }
    }
  }
  for (const key of Object.keys(NOT_AN_ORDER)) {
    if (!seen.has(key)) problems.push(`stale entry: ${key} no longer exists — delete it`);
  }
  assert.deepEqual(
    problems,
    [],
    problems.join('\n') +
      '\n\nFixing a QA defect? Read docs/qa/regression-guards.md ("How a fix PR uses these guards").',
  );
});

test('QA D1098 guard: the scan sees the resulted-order shape', () => {
  const before = `@if (!['COMPLETED', 'FINAL', 'CANCELLED'].includes(o.status)) {\n  @if (canCancelThisOrder(o)) {\n    <button (click)="confirmCancel(o)">x</button>\n  }\n}`;
  const src = `\n  canCancelThisOrder(o: X): boolean {\n    return this.authorship.isAuthor(o);\n  }`;
  const [a] = endingActions(before);
  assert.equal(asksCancellableRule(enclosingConditions(before, a.index), src), false);
  const fixed = `\n  canCancelThisOrder(o: X): boolean {\n    return isOrderCancellable(o.status) && this.authorship.isAuthor(o);\n  }`;
  assert.equal(asksCancellableRule(enclosingConditions(before, a.index), fixed), true);
});

/**
 * QA D1108 (round 19): "Parti sans être vu" offered on fewer visits than the
 * server accepts. The tracker showed it only in the arrival and triage
 * columns; the close-unseen endpoint accepts a visit until a clinician sees it
 * (EncounterClosureRules.NEVER_SEEN). Every LWBS button must sit behind the
 * one rule, `isNeverSeenVisit` (src/app/core/encounter-start.ts), directly or
 * through a predicate that calls it.
 */
const LWBS_CLICK = /\(click\)="\s*(openCloseUnseen\w*\s*\([^"]*\))/g;

function asksRule(conditions, source, rule, depth = 0) {
  for (const cond of conditions) {
    if (rule.test(cond)) return true;
    for (const [, name] of cond.matchAll(/\b(\w+)\s*\(/g)) {
      const body = methodBody(source, name);
      if (rule.test(body)) return true;
      if (depth < 1 && body && asksRule([body.slice(body.indexOf('{'))], source, rule, depth + 1)) {
        return true;
      }
    }
  }
  return false;
}

test('QA D1108: every LWBS button is offered on the one never-seen rule', () => {
  const problems = [];
  let sites = 0;
  for (const { file, text } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    const source = componentSourceFor(file, text);
    for (const m of text.matchAll(LWBS_CLICK)) {
      sites++;
      const conditions = enclosingConditions(text, m.index);
      if (!asksRule(conditions, source, /\bisNeverSeenVisit\b/)) {
        const line = text.slice(0, m.index).split('\n').length;
        problems.push(
          `${rel}:${line}: "${m[1]}" is offered without isNeverSeenVisit(status) from core/encounter-start ` +
            '(the server accepts LWBS until the patient is seen, QA D1108)',
        );
      }
    }
  }
  assert.ok(sites >= 3, `LWBS buttons found: ${sites} (tracker, nurse station, visit detail)`);
  assert.deepEqual(problems, [], problems.join('\n'));
});

test('QA D1108 guard: the scan sees the before-triage-only shape', () => {
  const before = `@if (col.key === 'triage' || col.key === 'arrived') {\n  <button (click)="openCloseUnseen(item)">x</button>\n}`;
  const [m] = [...before.matchAll(LWBS_CLICK)];
  assert.equal(asksRule(enclosingConditions(before, m.index), '', /\bisNeverSeenVisit\b/), false);
  const after = `@if (canCloseUnseen && isNeverSeenVisit(item.currentStatus)) {\n  <button (click)="openCloseUnseen(item)">x</button>\n}`;
  const [n] = [...after.matchAll(LWBS_CLICK)];
  assert.equal(asksRule(enclosingConditions(after, n.index), '', /\bisNeverSeenVisit\b/), true);
});

test('QA D1081 guard: the scan sees the shapes the defect had', () => {
  const ungated = `@if (canCancelOrder()) {\n  <button (click)="confirmCancel(o)">x</button>\n}`;
  const source = `\n  readonly canCancelOrder = computed(() => true);\n  }`;
  const [a] = endingActions(ungated);
  assert.equal(a.handler, 'confirmCancel(o)');
  assert.deepEqual(enclosingConditions(ungated, a.index), ['canCancelOrder()']);
  assert.equal(asksAuthorRule(enclosingConditions(ungated, a.index), source), false);

  const gated = `@for (o of rows; track o.id) {\n  @if (\n    canCancelThisOrder(o)\n  ) {\n    <button (click)="openAction('cancel')">x</button>\n  }\n}`;
  const src2 = `\n  canCancelThisOrder(o: X): boolean {\n    return this.authorship.isAuthor(o);\n  }`;
  const [b] = endingActions(gated);
  assert.equal(b.handler, "openAction('cancel')");
  assert.equal(asksAuthorRule(enclosingConditions(gated, b.index), src2), true);

  // A computed gate does not borrow the body of the method declared after it.
  const src3 = `\n  readonly canCancelOrder = computed(() =>\n    this.roles.has('X'),\n  );\n  canCancelThisOrder(o: X): boolean {\n    return this.authorship.isAuthor(o);\n  }`;
  assert.equal(asksAuthorRule(['canCancelOrder()'], src3), false);
  assert.equal(asksAuthorRule(['canCancelThisOrder(o)'], src3), true);

  // A closed block no longer encloses what follows it.
  const closed = `@if (canCancelThisOrder(o)) {\n  <span></span>\n}\n<button (click)="cancelRequest(r)">x</button>`;
  const [c] = endingActions(closed);
  assert.deepEqual(enclosingConditions(closed, c.index), []);
});

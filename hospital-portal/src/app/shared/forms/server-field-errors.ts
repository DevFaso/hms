import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';

import { FieldRefusal, refuseFields } from './invalid-fields';

/**
 * QA D1341 / D1320 / D1321 (the D1245 / D1258 / D1301 class, NOT_FIXED in
 * rounds 27–29): the server named the refused field under `fieldErrors`
 * (`reference`, `referenceNumber`, `transactionReference`, `copayReference`,
 * `amount` …) and the screen showed only a toast. Every form had to wire its
 * own `fieldErrors` → element map, and the forms nobody wired kept the field
 * unmarked: billing, pharmacy checkout, the patient's own payment, the
 * check-in copay and the desk pay-collect all failed the same way.
 *
 * One choke point for every form of the portal: {@link serverFieldErrorsInterceptor}
 * (installed in app.config.ts) remembers, when a write is sent, the form,
 * dialog or `[data-submit-scope]` (else the page section: a fieldset, card,
 * panel or section) the reader acted in; when the
 * server refuses that write (400 / 409 / 422) with `fieldErrors`, each named
 * field found in that place is marked through {@link refuseFields}
 * (`aria-invalid`, the server's reason right under it, focus on the first).
 * The page's own toast and handler still run first; a field the handler
 * marked itself in the same refusal is left as it marked it.
 *
 * A field is found in its place by, in order:
 * 1. `data-server-field="reference referenceNumber"`: the server keys the
 *    control answers to (space-separated) — for a control whose id does not
 *    say it (`#payRef`, `#ci-amount`);
 * 2. a control whose id, `name` or `formcontrolname` is the key (case,
 *    dashes and underscores ignored: `#payment-reference` ⇄ `paymentReference`);
 * 3. the ONE control whose id ends with the key (`#payment-reference` ⇄
 *    `reference`); two candidates mark neither.
 * A nested key (`items[0].quantity`, `lines.unitPrice`) is looked up whole,
 * then by its last segment.
 *
 * The mark leaves when the reader edits that field, or when the same place
 * sends its next write. A component that knows a better map calls
 * {@link markServerFieldErrors} with it.
 *
 * `src/app/testing/form-behaviour.spec.ts` drives every form of the
 * front-office screens through this path; `scripts/lib/server-field-marks.test.mjs`
 * keeps the interceptor installed and holds the screens' known server keys
 * to a control.
 */

/** Where a submit's fields are looked up — the same places as the submit guard's `root()`. */
export const SUBMIT_SCOPE_SELECTOR =
  'form, [role="dialog"], [role="alertdialog"], dialog, .modal, [data-submit-scope]';

/**
 * A page section that holds an inline form without being one (a card with
 * fields and a `(click)` button): where the fields are looked up when no
 * form, dialog or scope encloses the control the reader acted on.
 */
export const SECTION_SCOPE_SELECTOR = 'fieldset, .form-card, .card, .panel, section';

/** The place a control belongs to: its form, dialog or scope, else its page section. */
export function scopeOf(el: Element | null | undefined): HTMLElement | null {
  return (
    el?.closest<HTMLElement>(SUBMIT_SCOPE_SELECTOR) ??
    el?.closest<HTMLElement>(SECTION_SCOPE_SELECTOR) ??
    null
  );
}

/** Statuses whose `fieldErrors` name the reader's own input. */
const FIELD_REFUSAL_STATUSES = new Set([400, 409, 422]);

/** The controls a server key can name. */
const CONTROL_SELECTOR = [
  'input:not([type="hidden"])',
  'select',
  'textarea',
  '[role="radiogroup"]',
  '[role="group"][id]',
  '[role="listbox"][id]',
  '[data-server-field]',
].join(', ');

/** Marks this module set (so a field the page marked itself is left alone). */
const SERVER_MARK = 'data-server-invalid';

/**
 * Each non-blank `fieldErrors` entry of a refusal, key → the server's
 * (localised) reason, in the order the server sent them. Empty for anything
 * that is not a 4xx with `fieldErrors`.
 */
export function serverFieldErrors(err: unknown): Map<string, string> {
  const out = new Map<string, string>();
  const status = (err as { status?: unknown } | null)?.status;
  if (typeof status !== 'number' || status < 400 || status >= 500) return out;
  const body = (err as { error?: unknown }).error as { fieldErrors?: unknown } | null | undefined;
  const raw = body?.fieldErrors;
  if (!raw || typeof raw !== 'object') return out;
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof value === 'string' && value.trim() !== '') out.set(key, value.trim());
  }
  return out;
}

/** Lower-case letters and digits only: `payment-reference`, `paymentReference` → `paymentreference`. */
export function normalizeFieldKey(key: string): string {
  return key.toLowerCase().replace(/[^a-z0-9]/g, '');
}

/** `items[0].quantity` → [`items0quantity`, `quantity`]; `amount` → [`amount`]. */
function keyForms(key: string): string[] {
  const whole = normalizeFieldKey(key);
  const last = normalizeFieldKey(
    key
      .replace(/\[\d+\]/g, '')
      .split('.')
      .pop() ?? '',
  );
  return last !== '' && last !== whole ? [whole, last] : [whole];
}

/** A control the reader can see and use. */
function usable(el: HTMLElement): boolean {
  if ((el as HTMLInputElement).disabled) return false;
  return el.closest('[hidden]') === null;
}

/** The names a control answers to (id, name, formcontrolname), normalised. */
function namesOf(el: HTMLElement): string[] {
  return [el.id, el.getAttribute('name') ?? '', el.getAttribute('formcontrolname') ?? '']
    .map(normalizeFieldKey)
    .filter((n) => n !== '');
}

/**
 * The control of `scope` that server key `key` names, or `null` (see the
 * module comment for the order). Exported for the behavioural spec.
 */
export function fieldControlFor(scope: ParentNode, key: string): HTMLElement | null {
  const controls = Array.from(scope.querySelectorAll<HTMLElement>(CONTROL_SELECTOR)).filter(usable);
  for (const form of keyForms(key)) {
    const declared = controls.find((el) =>
      (el.getAttribute('data-server-field') ?? '')
        .split(/\s+/)
        .some((token) => token !== '' && normalizeFieldKey(token) === form),
    );
    if (declared) return declared;
  }
  for (const form of keyForms(key)) {
    const named = controls.find((el) => namesOf(el).includes(form));
    if (named) return named;
  }
  for (const form of keyForms(key)) {
    if (form.length < 3) continue;
    const ending = controls.filter((el) => normalizeFieldKey(el.id).endsWith(form));
    if (ending.length === 1) return ending[0];
  }
  return null;
}

/** Gives a control an id so {@link refuseFields} can mark and describe it. */
let nextId = 0;
function idOf(el: HTMLElement): string {
  if (!el.id) el.id = `server-field-${++nextId}`;
  return el.id;
}

/**
 * Marks every field of `scope` the refusal `err` names, each with the
 * server's reason, and focuses the first. `idByField` maps a server key to
 * an element id when the page knows it better than the lookup. Returns the
 * ids marked (empty when the refusal names no field found here).
 */
export function markServerFieldErrors(
  scope: ParentNode | null | undefined,
  err: unknown,
  idByField: Readonly<Record<string, string>> = {},
): string[] {
  if (!scope) return [];
  const refusals: FieldRefusal[] = [];
  const seen = new Set<string>();
  for (const [key, reason] of serverFieldErrors(err)) {
    const mapped = idByField[key]
      ? scope.querySelector<HTMLElement>(`#${CSS.escape(idByField[key])}`)
      : null;
    const el = mapped ?? fieldControlFor(scope, key);
    if (!el) continue;
    // A field the page's own handler marked in this refusal keeps its words.
    if (el.getAttribute('aria-invalid') === 'true' && !el.hasAttribute(SERVER_MARK)) continue;
    const id = idOf(el);
    if (seen.has(id)) continue;
    seen.add(id);
    el.setAttribute(SERVER_MARK, 'true');
    clearOnEdit(scope, el);
    refusals.push({ id, message: reason });
  }
  if (refusals.length === 0) return [];
  const ordered = refusals.sort((a, b) => documentOrder(scope, a.id, b.id));
  refuseFields(
    scope,
    ordered,
    ordered.map((r) => r.id),
  );
  return ordered.map((r) => r.id);
}

/** Removes the marks this module set in `scope` (its next write, or a reset). */
export function clearServerFieldMarks(scope: ParentNode | null | undefined): void {
  if (!scope) return;
  const ids = Array.from(scope.querySelectorAll<HTMLElement>(`[${SERVER_MARK}]`)).map((el) => {
    el.removeAttribute(SERVER_MARK);
    return el.id;
  });
  if (ids.length > 0) refuseFields(scope, [], ids);
}

function clearOnEdit(scope: ParentNode, el: HTMLElement): void {
  const clear = () => {
    el.removeEventListener('input', clear);
    el.removeEventListener('change', clear);
    if (!el.hasAttribute(SERVER_MARK)) return;
    el.removeAttribute(SERVER_MARK);
    refuseFields(scope, [], [el.id]);
  };
  el.addEventListener('input', clear);
  el.addEventListener('change', clear);
}

function documentOrder(scope: ParentNode, a: string, b: string): number {
  const ea = scope.querySelector(`#${CSS.escape(a)}`);
  const eb = scope.querySelector(`#${CSS.escape(b)}`);
  if (!ea || !eb || ea === eb) return 0;
  return ea.compareDocumentPosition(eb) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1;
}

/** The place the reader acted in: the last control they clicked or pressed a key on. */
const lastAction = new WeakMap<Document, { target: Element; at: number }>();
const tracked = new WeakSet<Document>();

/** How long after a click or key press a write still belongs to that place. */
const ACTION_WINDOW_MS = 10_000;

function track(doc: Document): void {
  if (tracked.has(doc)) return;
  tracked.add(doc);
  const remember = (event: Event) => {
    if (event.target instanceof Element) {
      lastAction.set(doc, { target: event.target, at: Date.now() });
    }
  };
  doc.addEventListener('click', remember, true);
  doc.addEventListener('submit', remember, true);
  doc.addEventListener('keydown', remember, true);
}

/** The form, dialog or scope the reader is acting in now, or `null`. */
export function actionScope(doc: Document): HTMLElement | null {
  const action = lastAction.get(doc);
  const recent = action && Date.now() - action.at <= ACTION_WINDOW_MS ? action.target : null;
  const from = recent?.isConnected ? recent : doc.activeElement;
  return scopeOf(from);
}

const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/**
 * Marks the fields a refused write's `fieldErrors` name in the place the
 * write was sent from (module comment). Reads nothing and changes nothing
 * in the request or the error: the page's handler gets the same error.
 */
export const serverFieldErrorsInterceptor: HttpInterceptorFn = (req, next) => {
  if (!WRITE_METHODS.has(req.method)) return next(req);
  const doc = inject(DOCUMENT);
  track(doc);
  const scope = actionScope(doc);
  clearServerFieldMarks(scope);
  return next(req).pipe(
    catchError((err: unknown) => {
      if (
        scope &&
        err instanceof HttpErrorResponse &&
        FIELD_REFUSAL_STATUSES.has(err.status) &&
        serverFieldErrors(err).size > 0
      ) {
        // After the page's own error handler (it runs as this error is
        // rethrown) and the re-render it causes.
        setTimeout(() => {
          if (scope.isConnected) markServerFieldErrors(scope, err);
        }, 0);
      }
      return throwError(() => err);
    }),
  );
};

/** Starts remembering where the reader acts (app start; the interceptor also does on first use). */
export function installServerFieldMarks(doc: Document): void {
  track(doc);
}

import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture } from '@angular/core/testing';

/**
 * Round 29 (D1256 / D1272 / D1245 class, NOT_FIXED in rounds 26 to 29): the
 * BEHAVIOURAL half of the required-field guards. The static scans read the
 * templates for shapes (a star, a `required`, a guard on the button); QA kept
 * finding forms that had the shapes and still refused by a toast alone, named
 * a filled field, or left a field the server named unmarked. These helpers
 * drive the real component the way the tester does:
 *
 * - {@link expectEmptySubmitRefusal}: press the submit with the form as it
 *   opens. Every blank required control of the form (and every id the caller
 *   says the refusal is about) carries `aria-invalid="true"` and a visible
 *   message it names in `aria-describedby`; every blank required control's
 *   label shows the star; a FILLED control is never marked; nothing is sent.
 * - {@link expectFieldsMarked}: after a server 400 whose `fieldErrors` name
 *   fields ({@link fieldErrorsResponse}), each of them is marked with the
 *   server's own reason.
 *
 * `scripts/lib/form-refusal-behaviour.test.mjs` enumerates every guarded
 * submit of the clinical screens and fails when one has no spec calling
 * these (or no pin with its reason), so a new form is covered automatically.
 */

const SCOPE = 'form, [role="dialog"], [role="alertdialog"], dialog, .modal, [data-submit-scope]';
const REQUIRED =
  'input[required], select[required], textarea[required], ' +
  'input[aria-required="true"], select[aria-required="true"], textarea[aria-required="true"]';

export interface EmptySubmitRefusal {
  /** Selector of the submit control, looked up in the fixture. */
  readonly button: string;
  /** Ids the refusal must mark besides every blank required control (a "one of" rule). */
  readonly alsoMarked?: readonly string[];
  /** Ids holding a value the refusal must NOT mark (filled before the press). */
  readonly notMarked?: readonly string[];
  /** The call that would send the form: never made by a refused press. */
  readonly sent?: jasmine.Spy;
}

/** A control the browser would show, with nothing chosen or typed. */
function isBlank(el: Element): boolean {
  if (el.getAttribute('data-submit-blank') === 'true') return true;
  if (el instanceof HTMLInputElement) {
    if (el.type === 'checkbox' || el.type === 'radio') return !el.checked;
    return el.value.trim() === '';
  }
  if (el instanceof HTMLSelectElement) {
    const value = el.selectedIndex < 0 ? '' : el.value.trim();
    return value === '' || /^\d+: (?:null|undefined)$/.test(value);
  }
  if (el instanceof HTMLTextAreaElement) return el.value.trim() === '';
  return false;
}

function isRendered(el: HTMLElement): boolean {
  return (
    !(el as HTMLInputElement).disabled &&
    el.getAttribute('type') !== 'hidden' &&
    el.closest('[hidden]') === null
  );
}

/** The text of the label a control is shown under. */
function labelText(root: ParentNode, el: HTMLElement): string {
  const label =
    (el.id ? root.querySelector<HTMLElement>(`label[for="${CSS.escape(el.id)}"]`) : null) ??
    el.closest('label');
  return (label?.textContent ?? el.getAttribute('aria-label') ?? '').trim();
}

/** The message a marked control names in `aria-describedby`, or ''. */
function describedText(el: HTMLElement): string {
  const ids = (el.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean);
  return ids
    .map((id) => el.ownerDocument.getElementById(id)?.textContent ?? '')
    .join(' ')
    .trim();
}

/** Expects control `el` marked: `aria-invalid="true"` and a non-empty message it describes itself by. */
function expectMarked(el: HTMLElement, what: string, reason?: string): void {
  expect(el.getAttribute('aria-invalid'))
    .withContext(`${what} #${el.id} is marked aria-invalid`)
    .toBe('true');
  const said = describedText(el);
  expect(said).withContext(`${what} #${el.id} names its reason under it`).not.toBe('');
  if (reason !== undefined) {
    expect(said).withContext(`${what} #${el.id} shows the server's reason`).toContain(reason);
  }
}

/**
 * Presses `button` and expects the refusal to mark every blank required
 * control of its form (starred), every `alsoMarked` id, and nothing filled.
 * The fixture must be attached to the document. Returns the marked ids.
 */
export function expectEmptySubmitRefusal(
  fixture: ComponentFixture<unknown>,
  refusal: EmptySubmitRefusal,
): string[] {
  const host = fixture.nativeElement as HTMLElement;
  const button = host.querySelector<HTMLElement>(refusal.button);
  expect(button).withContext(`submit ${refusal.button} is rendered`).not.toBeNull();
  if (!button) return [];
  const scope = button.closest<HTMLElement>(SCOPE) ?? host;
  const required = Array.from(scope.querySelectorAll<HTMLElement>(REQUIRED)).filter(isRendered);
  const blankRequired = required.filter(isBlank);
  for (const el of blankRequired) {
    expect(labelText(scope, el))
      .withContext(`required control #${el.id} shows the required star`)
      .toContain('*');
  }

  button.click();
  fixture.detectChanges();

  const expected = [
    ...blankRequired,
    ...(refusal.alsoMarked ?? []).map((id) => scope.querySelector<HTMLElement>(`#${id}`)),
  ];
  expect(expected.length)
    .withContext(`the refusal of ${refusal.button} marks at least one field (never a toast alone)`)
    .toBeGreaterThan(0);
  const marked: string[] = [];
  for (const el of expected) {
    expect(el).withContext('a field the refusal is about is rendered').not.toBeNull();
    if (!el) continue;
    expectMarked(el, 'refused field');
    marked.push(el.id);
  }
  for (const id of refusal.notMarked ?? []) {
    const el = scope.querySelector<HTMLElement>(`#${id}`);
    expect(el?.getAttribute('aria-invalid') ?? null)
      .withContext(`filled field #${id} is not marked`)
      .not.toBe('true');
  }
  if (refusal.sent) {
    expect(refusal.sent).withContext('a refused form is not sent').not.toHaveBeenCalled();
  }
  return marked;
}

/** A server validation refusal naming `fieldErrors`, as the global handler sends it. */
export function fieldErrorsResponse(
  fieldErrors: Readonly<Record<string, string>>,
  status = 400,
): HttpErrorResponse {
  return new HttpErrorResponse({
    status,
    error: {
      error: 'Validation Failed',
      message: Object.values(fieldErrors).join(' '),
      fieldErrors,
    },
  });
}

/** Expects each control id marked with the server's reason given beside it. */
export function expectFieldsMarked(
  fixture: ComponentFixture<unknown>,
  reasonById: Readonly<Record<string, string>>,
): void {
  const host = fixture.nativeElement as HTMLElement;
  for (const [id, reason] of Object.entries(reasonById)) {
    const el = host.querySelector<HTMLElement>(`#${CSS.escape(id)}`);
    expect(el).withContext(`#${id} is rendered`).not.toBeNull();
    if (el) expectMarked(el, 'field the server named', reason);
  }
}

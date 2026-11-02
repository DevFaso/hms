import {
  DestroyRef,
  Directive,
  ElementRef,
  booleanAttribute,
  effect,
  inject,
  input,
} from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

import { invalidMessageId, refuseFields } from './invalid-fields';

/** Under each blank field the guard marks. */
const FIELD_KEY = 'COMMON.FIELD_REQUIRED';
/** Next to the button when the blank fields have labels: "Required fields missing: …". */
const MISSING_FIELDS_KEY = 'COMMON.MISSING_REQUIRED_FIELDS';
/** Next to the button when no blank field can be named. */
const BLOCKED_KEY = 'COMMON.SUBMIT_BLOCKED_REQUIRED';

/**
 * QA D1124 / D1089 (NOT_FIXED in rounds 18–20): a submit button is never
 * silently disabled. A disabled button swallows the click, so the reader never
 * learns which field is missing; round 20 still found ~20 of them
 * (prescription "Créer", "Dispenser", the lab void reason, the MAR reasons,
 * check-in, new invoice …).
 *
 * The button keeps `[disabled]` only for a request in flight. Whatever else
 * made it unusable moves to this directive:
 *
 * ```html
 * <button type="submit" [disabled]="saving()"
 *         [appSubmitGuard]="!form.patientId || !form.dueDate">Créer</button>
 * ```
 *
 * While the guard is truthy a click (or the implicit submission Enter makes,
 * which is a click on the default button) is stopped before any `(click)` or
 * `(ngSubmit)` handler runs; the blank required fields are marked through
 * {@link refuseFields} (`aria-invalid`, a message under each, focus on the
 * first); and the reason is shown right under the button in a `role="alert"`
 * that the button's `aria-describedby` names.
 *
 * The guard value is the reason: `true` (or any truthy non-string) means
 * "required fields are missing", worded from the fields' own labels; a string
 * is shown as is (already translated) for a reason that is not a field — an
 * attestation, a witness, a hospital scope.
 *
 * The fields are `submitGuardFields` (ids, looked up in the enclosing form or
 * dialog); without them only the reason is shown.
 *
 * QA D1193 / D1089 (round 22): a form whose fields carry `required` but whose
 * submit button checked nothing posted empty (the lab instrument and
 * inventory "Créer": the server answered "ne doit pas être vide", or a generic
 * toast). With `submitGuardRequired` the guard reads the form itself at click
 * time: every rendered, enabled `[required]` control that is blank is marked
 * and named ("Champs obligatoires manquants : Nom, N° de série"), and nothing
 * is sent:
 *
 * ```html
 * <button type="submit" [disabled]="saving()" appSubmitGuard submitGuardRequired>Créer</button>
 * ```
 *
 * QA D1245 (D1089 / D1124 NOT_FIXED in round 25): the prescription dialog's
 * guard listed only the drug, its patient input had a star but no `required`,
 * and `patientId: ''` went out. So:
 * - a field listed in `submitGuardFields` that declares itself required
 *   (`required` or `aria-required="true"`) and is blank refuses the click
 *   whatever the expression says;
 * - a typeahead whose text is only a query (nothing picked) carries
 *   `data-submit-blank="true"` and counts as blank;
 * - a section that is neither a form nor a dialog carries `data-submit-scope`.
 *
 * QA D1256 / D1257 (D1089 / D1124 / D1245 NOT_FIXED in round 26): every
 * refusal reads every field the guard is about — the listed ones and, with
 * `submitGuardRequired`, every rendered required control — and names each
 * blank one, whatever made the expression truthy. Round 25 read the required
 * controls only when the expression let the click through, so an empty
 * prescription named only "Nom du médicament" and the lab order's blank
 * patient (tested by the expression, never listed) fell to the generic
 * sentence. A string reason is still said as given; the blank fields are
 * marked under it, each with its own message.
 *
 * `scripts/lib/required-form-submit.test.mjs` fails the build for a required
 * input (a `required` control, or one whose label shows `*`) that no submit of
 * its form, dialog or scope checks (lib/required-field-scan.mjs).
 * `scripts/lib/silent-disabled-submit.test.mjs` fails the build on a button
 * disabled for anything but a request in flight or a pager's end.
 */
@Directive({
  selector: 'button[appSubmitGuard], input[appSubmitGuard]',
  standalone: true,
  host: {
    // The state a test (or a style) can read without clicking; the button
    // itself stays enabled and focusable.
    '[attr.data-submit-blocked]': 'appSubmitGuard() ? "true" : null',
  },
})
export class SubmitGuardDirective {
  /** Falsy: the form may be sent. `true`: required fields are missing. A string: that reason. */
  readonly appSubmitGuard = input<unknown>(null);
  /** The ids of the fields the guard is about, in reading order. */
  readonly submitGuardFields = input<readonly string[] | null>(null);
  /** QA D1193: refuse while any `[required]` control of the form is blank (read at click time). */
  readonly submitGuardRequired = input(false, { transform: booleanAttribute });

  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly translate = inject(TranslateService);
  private static nextId = 0;
  private readonly fallbackId = `submit-guard-${++SubmitGuardDirective.nextId}`;
  private marked: string[] = [];

  constructor() {
    const button = this.host.nativeElement as HTMLElement;
    // Capture on the target runs before every bubbling listener on it,
    // Angular's `(click)` and the form's `(ngSubmit)` included.
    const onClick = (event: Event) => this.onClick(event);
    button.addEventListener('click', onClick, true);
    inject(DestroyRef).onDestroy(() => {
      button.removeEventListener('click', onClick, true);
      this.clear();
    });
    effect(() => {
      if (!this.appSubmitGuard()) this.clear();
    });
  }

  private onClick(event: Event): void {
    const root = this.root();
    // QA D1256 / D1257 (D1089 / D1124 / D1245 NOT_FIXED in round 26): every
    // field the guard is about — the listed ones and, with
    // `submitGuardRequired`, every rendered required control of the form — is
    // read on EVERY refusal. Round 25 read the required controls only when the
    // expression let the click through, so an empty form named only what the
    // expression tested ("Nom du médicament"), and a blank the expression
    // tested but did not list (the lab order's patient) fell to the generic
    // sentence.
    const all = this.guardedFieldIds(root);
    const blank = all.filter((id) => isBlank(root.querySelector(`#${CSS.escape(id)}`)));
    let reason = this.appSubmitGuard();
    if (!reason && blank.some((id) => this.requiredById(root, id))) {
      // QA D1245 / D1193: a listed field that declares itself required, or any
      // required control under `submitGuardRequired`, is refused while blank,
      // whatever the expression says.
      reason = true;
    }
    if (!reason) {
      // Every required field is filled now: an earlier refusal goes away.
      if (this.marked.length > 0) this.clear();
      return;
    }
    event.preventDefault();
    event.stopImmediatePropagation();
    const stale = this.marked.filter((id) => !all.includes(id));
    if (stale.length > 0) refuseFields(root, [], stale, '');
    this.marked = all;
    if (blank.length > 0) {
      refuseFields(root, blank, all, this.translate.instant(FIELD_KEY));
    }
    this.showMessage(this.messageFor(reason, root, blank));
  }

  /**
   * The listed fields, then (with `submitGuardRequired`) every rendered
   * required control of the form, in reading order, each once.
   */
  private guardedFieldIds(root: HTMLElement): string[] {
    const listed = this.fieldIds();
    if (!this.submitGuardRequired()) return listed;
    const ids = [...new Set([...listed, ...this.requiredFieldIds()])];
    const at = (id: string) => root.querySelector(`#${CSS.escape(id)}`);
    return ids.sort((a, b) => documentOrder(at(a), at(b)));
  }

  /** Whether field `id` is a rendered control that declares itself required. */
  private requiredById(root: HTMLElement, id: string): boolean {
    const el = root.querySelector<HTMLElement>(`#${CSS.escape(id)}`);
    return el !== null && isRequiredControl(el);
  }

  private messageFor(reason: unknown, root: ParentNode, blank: readonly string[]): string {
    // A reason that is not a field (a witness, an attestation) is said as
    // given; the blank fields are still marked, each with its own message.
    if (typeof reason === 'string') return reason;
    const labels = blank.map((id) => labelOf(root, id)).filter((l) => l !== '');
    if (labels.length > 0) {
      return this.translate.instant(MISSING_FIELDS_KEY, { fields: labels.join(', ') });
    }
    return this.translate.instant(BLOCKED_KEY);
  }

  /**
   * The form or dialog the button sends: where its fields are looked up. A
   * section that is neither (an inline form on a page) carries
   * `data-submit-scope` (QA D1245).
   */
  private root(): HTMLElement {
    const button = this.host.nativeElement as HTMLElement;
    return (
      button.closest<HTMLElement>(
        'form, [role="dialog"], [role="alertdialog"], dialog, .modal, [data-submit-scope]',
      ) ??
      button.parentElement?.parentElement ??
      button.ownerDocument.body
    );
  }

  /**
   * Only the fields the template names: a guard on a secondary action inside
   * a larger form (the OTP check in the registration form) must not mark the
   * form's other blank fields as missing.
   */
  private fieldIds(): string[] {
    return [...(this.submitGuardFields() ?? [])];
  }

  /**
   * The ids of the rendered, enabled required controls of the form (`required`,
   * or `aria-required="true"`: QA D1245), in reading order; a control without
   * an id is given one, so it can be marked and named.
   */
  private requiredFieldIds(): string[] {
    const controls = this.root().querySelectorAll<HTMLElement>(REQUIRED_CONTROLS);
    return Array.from(controls)
      .filter(isRequiredControl)
      .map((el) => {
        if (!el.id) el.id = `submit-guard-field-${++SubmitGuardDirective.nextId}`;
        return el.id;
      });
  }

  private messageId(): string {
    const button = this.host.nativeElement as HTMLElement;
    return invalidMessageId(button.id || this.fallbackId);
  }

  private showMessage(text: string): void {
    const button = this.host.nativeElement as HTMLElement;
    const id = this.messageId();
    let msg = this.messageElement(id);
    if (!msg) {
      msg = button.ownerDocument.createElement('p');
      msg.id = id;
      msg.className = 'invalid-field-msg submit-guard-msg';
      msg.setAttribute('role', 'alert');
      msg.setAttribute('data-testid', 'submit-guard-reason');
      button.insertAdjacentElement('afterend', msg);
    }
    msg.textContent = text;
    const tokens = (button.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean);
    if (!tokens.includes(id)) tokens.push(id);
    button.setAttribute('aria-describedby', tokens.join(' '));
  }

  /** The reason shown after the button (looked up beside it, so a detached view works too). */
  private messageElement(id: string): HTMLElement | null {
    const button = this.host.nativeElement as HTMLElement;
    return (
      button.parentElement?.querySelector<HTMLElement>(`#${CSS.escape(id)}`) ??
      button.ownerDocument.getElementById(id)
    );
  }

  private clear(): void {
    const button = this.host.nativeElement as HTMLElement;
    const id = this.messageId();
    this.messageElement(id)?.remove();
    const rest = (button.getAttribute('aria-describedby') ?? '')
      .split(/\s+/)
      .filter((t) => t !== '' && t !== id);
    if (rest.length > 0) button.setAttribute('aria-describedby', rest.join(' '));
    else button.removeAttribute('aria-describedby');
    if (this.marked.length > 0) {
      refuseFields(this.root(), [], this.marked, '');
      this.marked = [];
    }
  }
}

const REQUIRED_CONTROLS = [
  'input[required]',
  'select[required]',
  'textarea[required]',
  'input[aria-required="true"]',
  'select[aria-required="true"]',
  'textarea[aria-required="true"]',
].join(', ');

/** Reading order of two fields (a missing one goes last). */
function documentOrder(a: Element | null, b: Element | null): number {
  if (a === b) return 0;
  if (a === null) return 1;
  if (b === null) return -1;
  return a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1;
}

/** A rendered, enabled control that declares itself required. */
function isRequiredControl(el: HTMLElement): boolean {
  return (
    el.matches(REQUIRED_CONTROLS) &&
    !(el as HTMLInputElement).disabled &&
    el.getAttribute('type') !== 'hidden' &&
    el.closest('[hidden]') === null
  );
}

/**
 * Whether a control holds no answer: an empty value, an unticked box, nothing
 * chosen. QA D1245: a typeahead whose text is only the query (a patient
 * search with nothing picked) says so with `data-submit-blank="true"`; the
 * text it holds is not an answer.
 */
function isBlank(el: Element | null): boolean {
  if (!el) return false;
  const declared = el.getAttribute('data-submit-blank');
  if (declared === 'true') return true;
  if (declared === 'false') return false;
  if (el instanceof HTMLInputElement) {
    if (el.type === 'checkbox' || el.type === 'radio') return !el.checked;
    return el.value.trim() === '';
  }
  if (el instanceof HTMLSelectElement) {
    // `[ngValue]="null"` options carry Angular's `0: null` value.
    const value = el.selectedIndex < 0 ? '' : el.value.trim();
    return value === '' || /^\d+: (?:null|undefined)$/.test(value);
  }
  if (el instanceof HTMLTextAreaElement) return el.value.trim() === '';
  return false;
}

/** The visible label of field `id`, without its required-field star or the control's own text. */
function labelOf(root: ParentNode, id: string): string {
  const el = root.querySelector<HTMLElement>(`#${CSS.escape(id)}`);
  const label =
    root.querySelector<HTMLElement>(`label[for="${CSS.escape(id)}"]`) ?? el?.closest('label');
  let text = el?.getAttribute('aria-label') ?? '';
  if (label) {
    // A label that wraps its control would otherwise read the options of a
    // select, or the message refuseFields put after the field.
    const copy = label.cloneNode(true) as HTMLElement;
    copy.querySelectorAll('input, select, textarea, .invalid-field-msg').forEach((n) => n.remove());
    text = copy.textContent ?? text;
  }
  return text.replace(/\*/g, '').replace(/\s+/g, ' ').trim();
}

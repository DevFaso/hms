import { WritableSignal } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

/** One required field of a form: the label key it is shown under, and whether it holds a value. */
export interface RequiredField {
  labelKey: string;
  value: unknown;
}

/** True when a form value counts as given: not null, not undefined, not a blank string. */
export function hasValue(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === 'string') return value.trim() !== '';
  return true;
}

/**
 * QA D890: the treatment-plan, consultation and referral forms posted an empty
 * form and answered with a generic or technical toast ("targetSpecialty").
 * Before posting, a form checks its required fields here. Null when every
 * field is filled; otherwise the translated "Required fields missing: …" text
 * naming each missing field by its label, in form order.
 */
export function missingRequiredMessage(
  translate: TranslateService,
  fields: readonly RequiredField[],
): string | null {
  const missing = fields
    .filter((f) => !hasValue(f.value))
    .map((f) => translate.instant(f.labelKey));
  if (missing.length === 0) return null;
  return translate.instant('COMMON.MISSING_REQUIRED_FIELDS', { fields: missing.join(', ') });
}

/**
 * QA D1940 (round 54): a banner set on submit from {@link missingRequiredMessage}
 * ("Champs obligatoires manquants : Service, Date, Heure") kept naming fields
 * after they were filled, until the next submit. A form whose banner stays on
 * screen (a signal, not a toast) refuses through this instead: {@link check}
 * sets the banner as before, and while it still shows that sentence every
 * edit inside `root` recomputes it, so a filled field leaves the sentence and
 * the banner goes when nothing is missing. Another message the form put in
 * the banner since (a server error) is left alone.
 */
export class LiveRequiredMessage {
  private shown: string | null = null;
  private watched: HTMLElement | null = null;
  private readonly refresh = (): void => {
    if (this.shown === null || this.banner() !== this.shown) {
      this.shown = null;
      return;
    }
    this.shown = missingRequiredMessage(this.translate, this.fields());
    this.banner.set(this.shown);
  };

  constructor(
    private readonly translate: TranslateService,
    /** The form's required fields with their CURRENT values, read at each call. */
    private readonly fields: () => readonly RequiredField[],
    /** The banner the message is shown in. */
    private readonly banner: WritableSignal<string | null>,
  ) {}

  /**
   * On submit: the "missing fields" message, also put in the banner, or null
   * when every field is filled (the banner is then left as it is).
   */
  check(root: HTMLElement | null | undefined): string | null {
    const message = missingRequiredMessage(this.translate, this.fields());
    this.shown = message;
    if (message === null) return null;
    this.banner.set(message);
    if (root && this.watched !== root) {
      this.stop();
      this.watched = root;
      root.addEventListener('input', this.refresh);
      root.addEventListener('change', this.refresh);
    }
    return message;
  }

  /** Stops following the form's edits (the form is closed or destroyed). */
  stop(): void {
    this.watched?.removeEventListener('input', this.refresh);
    this.watched?.removeEventListener('change', this.refresh);
    this.watched = null;
  }
}

/**
 * QA D1256 class (round 27): every reason a submit guard has, said at once —
 * a guard reason method that returned the first rule only made the reader
 * press once per rule (empty triage: "Score ESI", then "a vital sign").
 * Null when there is none.
 */
export function joinReasons(
  reasons: readonly (string | null | false | undefined)[],
): string | null {
  const said = reasons
    .filter((r): r is string => typeof r === 'string' && r.trim() !== '')
    .map((r) => r.trim());
  if (said.length <= 1) return said[0] ?? null;
  // Each reason reads as its own sentence, whether its text ends with a stop or not.
  return said.map((r) => (/[.!?…]$/u.test(r) ? r : `${r}.`)).join(' ');
}

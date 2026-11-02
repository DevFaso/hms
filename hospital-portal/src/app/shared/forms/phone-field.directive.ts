import { Directive, ElementRef, OnDestroy, forwardRef, inject } from '@angular/core';
import { AbstractControl, NG_VALIDATORS, ValidationErrors, Validator } from '@angular/forms';
import { TranslateService } from '@ngx-translate/core';

import { isFacilityPhone } from '../phone-number';
import { focusFirstInvalid, invalidMessageId } from './invalid-fields';

let nextId = 0;

/**
 * QA D1211 (round 23, portal half): the server now refuses a non-number in
 * every phone field of every write request (`validation.phone.invalid`, 27
 * fields). The portal checked only the account's main phone, so a desk typing
 * "appeler le voisin" in a secondary or contact phone learned it from a banner
 * after the save, not on the field.
 *
 * ```html
 * <input id="ecPhone" type="tel" appPhoneField [(ngModel)]="form.emergencyContactPhone" />
 * ```
 *
 * The one client check of every phone input: the server's own rule
 * ({@link isFacilityPhone}), as an Angular validator (`phone` error on the
 * control) and, once the field is left, a message right under it
 * (`.invalid-field-msg`, the id {@link invalidMessageId} gives, `aria-invalid`,
 * `aria-describedby`), cleared as soon as the value is a number. Blank is never
 * refused here. `scripts/lib/phone-field.test.mjs` fails the build when a phone
 * input does not carry it.
 */
@Directive({
  selector: 'input[appPhoneField]',
  standalone: true,
  providers: [
    { provide: NG_VALIDATORS, useExisting: forwardRef(() => PhoneFieldDirective), multi: true },
  ],
  host: {
    // QA D1289: how a submit finds every phone field of its form (refuseInvalidPhones).
    'data-phone-field': '',
    '(blur)': 'onBlur()',
    '(input)': 'onInput()',
  },
})
export class PhoneFieldDirective implements Validator, OnDestroy {
  private readonly host = inject<ElementRef<HTMLInputElement>>(ElementRef);
  private readonly translate = inject(TranslateService);
  /** The field was left once: from then on the message follows the value. */
  private touched = false;
  /** This directive set `aria-invalid` (a server refusal's mark is not cleared here). */
  private marked = false;

  validate(control: AbstractControl): ValidationErrors | null {
    const value: unknown = control.value;
    return typeof value !== 'string' || isFacilityPhone(value) ? null : { phone: true };
  }

  protected onBlur(): void {
    this.touched = true;
    this.render();
  }

  protected onInput(): void {
    if (this.touched) this.render();
  }

  ngOnDestroy(): void {
    this.message()?.remove();
  }

  private fieldId(): string {
    const input = this.host.nativeElement;
    if (!input.id) input.id = `phone-field-${++nextId}`;
    return input.id;
  }

  private message(): HTMLElement | null {
    const input = this.host.nativeElement;
    const id = invalidMessageId(this.fieldId());
    const next = input.nextElementSibling;
    return next instanceof HTMLElement && next.id === id ? next : null;
  }

  private render(): void {
    const input = this.host.nativeElement;
    const msgId = invalidMessageId(this.fieldId());
    const described = (input.getAttribute('aria-describedby') ?? '')
      .split(/\s+/)
      .filter((t) => t !== '' && t !== msgId);
    let msg = this.message();
    if (isFacilityPhone(input.value)) {
      msg?.remove();
      if (this.marked) input.removeAttribute('aria-invalid');
      this.marked = false;
    } else {
      if (!msg) {
        msg = document.createElement('small');
        msg.id = msgId;
        msg.className = 'invalid-field-msg';
        msg.setAttribute('role', 'alert');
        input.insertAdjacentElement('afterend', msg);
      }
      msg.textContent = this.translate.instant('USERS.PHONE_INVALID');
      input.setAttribute('aria-invalid', 'true');
      this.marked = true;
      described.push(msgId);
    }
    if (described.length > 0) input.setAttribute('aria-describedby', described.join(' '));
    else input.removeAttribute('aria-describedby');
  }
}

/** The phone fields of `root` (each carries {@link PhoneFieldDirective}) holding a value the server refuses. */
export function invalidPhoneInputs(root: ParentNode | null | undefined): HTMLInputElement[] {
  if (!root) return [];
  return Array.from(root.querySelectorAll<HTMLInputElement>('input[data-phone-field]')).filter(
    (el) => !el.disabled && el.closest('[hidden]') === null && !isFacilityPhone(el.value),
  );
}

/**
 * QA D1289 (round 27): a submit never sends a phone number the server refuses.
 * Registration posted "70a2b" and learned it from the server's 400: the field's
 * own message appears only once the field is left, and the submit did not
 * look. Every phone field of `root` holding a refused number shows its message
 * now (the directive's own, as when it is left) and the first one takes the
 * focus. Returns whether any was refused. The submit guard calls it for every
 * guarded form; a form with its own submit handler calls it there.
 */
export function refuseInvalidPhones(
  root: ParentNode | null | undefined,
  bad: readonly HTMLInputElement[] = invalidPhoneInputs(root),
): boolean {
  if (bad.length === 0) return false;
  // The directive renders its message on blur, the way a reader leaving the field sees it.
  for (const el of bad) el.dispatchEvent(new Event('blur'));
  focusFirstInvalid(
    root,
    bad.map((el) => el.id),
  );
  return true;
}

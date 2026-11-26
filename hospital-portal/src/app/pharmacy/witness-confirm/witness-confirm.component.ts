import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  PharmacyService,
  StockWitnessCandidate,
  WitnessConfirmation,
} from '../../services/pharmacy.service';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { serverDateOrInstant } from '../../shared/date-utils';

/**
 * QA D2679 (r65 → r77): the witness of a controlled-drug movement was a name picked in a list, and never did
 * anything. Under every witness picker of the pharmacy, the witness now confirms THEMSELVES: their own password,
 * or the code of their authenticator when they have one, typed on the screen of the colleague recording the
 * movement. The server keeps the confirmation a few minutes and refuses a controlled movement that names a
 * witness who has not confirmed, so this component is where the second person acts, never the only gate.
 *
 * <p>`witness-confirm.test.mjs` keeps it under every witness picker.
 */
@Component({
  selector: 'app-witness-confirm',
  standalone: true,
  imports: [TranslateModule, DatePipe, ServerTimePipe, SubmitGuardDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './witness-confirm.component.html',
  styles: `
    :host {
      display: block;
    }
    .witness-confirm-row {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
    }
    .witness-confirm-row input {
      flex: 1 1 12rem;
      min-width: 0;
    }
    .witness-confirmed {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      margin: 0.25rem 0 0;
      color: var(--success-text, #166534);
      font-weight: 600;
    }
  `,
})
export class WitnessConfirmComponent {
  /** The colleague picked as the witness, or nothing yet. */
  readonly witnessId = input<string | null | undefined>(null);
  readonly candidates = input<readonly StockWitnessCandidate[]>([]);
  /** Makes the field's id unique on a page that holds several witness pickers. */
  readonly fieldId = input.required<string>();
  /** Told when the witness has confirmed (the form may then record the movement). */
  readonly confirmed = output<WitnessConfirmation>();

  private readonly svc = inject(PharmacyService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** What the witness is typing; a signal, so clearing it clears the field at once. */
  protected readonly secret = signal('');
  protected readonly confirming = signal(false);
  private readonly lastConfirmation = signal<WitnessConfirmation | null>(null);

  protected readonly candidate = computed(
    () => this.candidates().find((c) => c.userId === this.witnessId()) ?? null,
  );
  protected readonly usesCode = computed(() => this.candidate()?.confirmationMethod === 'TOTP');
  protected readonly unavailable = computed(
    () => this.candidate()?.confirmationMethod === 'UNAVAILABLE',
  );
  /** The confirmation that stands for the witness now picked; another witness confirms afresh. */
  protected readonly standing = computed(() => {
    const confirmation = this.lastConfirmation();
    if (!confirmation || confirmation.witnessedBy !== this.witnessId()) return null;
    return serverDateOrInstant(confirmation.expiresAt).getTime() > Date.now() ? confirmation : null;
  });

  /** The witness has confirmed and the confirmation is still good. */
  isConfirmed(): boolean {
    return this.standing() !== null;
  }

  confirm(): void {
    const witnessId = this.witnessId();
    if (!witnessId || this.confirming()) return;
    const ids = [this.fieldId()];
    // A blank field is refused by the button's submit guard, which names it; nothing is sent.
    if (!this.secret().trim()) return;
    refuseFields(this.host.nativeElement, [], ids, '');
    this.confirming.set(true);
    this.svc.confirmWitness(witnessId, this.secret()).subscribe({
      next: (res) => {
        this.confirming.set(false);
        this.secret.set('');
        const confirmation = res?.data ?? null;
        this.lastConfirmation.set(confirmation);
        if (confirmation) this.confirmed.emit(confirmation);
      },
      error: (err: unknown) => {
        this.confirming.set(false);
        // A refused secret is never kept on screen; the reason is said under the field.
        this.secret.set('');
        this.showServerRefusal(err);
      },
    });
  }

  /**
   * The server's own reason, under the field the witness is looking at: what it names for the secret or for
   * the choice of witness, else its message, else a translated fallback. Never a raw body.
   */
  private showServerRefusal(err: unknown): void {
    const fieldId = this.fieldId();
    const named = refusedFieldReasons(err, { secret: fieldId, witnessedBy: fieldId });
    const reason =
      named[0]?.[1] ??
      refusalReason(err) ??
      this.translate.instant('PHARMACY.WITNESS_CONFIRM_FAILED');
    refuseFields(this.host.nativeElement, [fieldId], [fieldId], reason);
  }
}

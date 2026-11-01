import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';

import { ModalDialogDirective } from '../a11y/modal-dialog.directive';
import { SubmitGuardDirective } from '../forms/submit-guard.directive';

/** Ids are unique per dialog instance so two dialogs never share a field id. */
let nextId = 0;

/**
 * QA D1157 (round 21): an action that ends a record (close a waitlist entry,
 * cancel a recall) acted on one click: no confirmation, no reason, and focus
 * dropped to the page body. This is the confirmation that asks why.
 *
 * ```html
 * @if (closing(); as entry) {
 *   <app-reason-dialog titleKey="RECEPTION.CLOSE_ENTRY_TITLE" [busy]="busy()"
 *     (confirmed)="closeEntry(entry, $event)" (cancelled)="closing.set(null)" />
 * }
 * ```
 *
 * A modal (shared appModalDialog: focus in, Escape, focus back to the button
 * that opened it); the reason is required, named where it is asked for, and
 * handed over trimmed.
 */
@Component({
  selector: 'app-reason-dialog',
  standalone: true,
  imports: [FormsModule, TranslateModule, ModalDialogDirective, SubmitGuardDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="modal-backdrop" tabindex="-1" (click)="cancel()">
      <div
        class="modal"
        appModalDialog
        [attr.aria-labelledby]="titleId"
        (escaped)="cancel()"
        (click)="$event.stopPropagation()"
        data-testid="reason-dialog"
      >
        <h2 [id]="titleId">{{ titleKey() | translate }}</h2>
        @if (messageKey()) {
          <p>{{ messageKey()! | translate }}</p>
        }
        <div class="field">
          <label [for]="fieldId">{{ labelKey() | translate }} *</label>
          <textarea
            [id]="fieldId"
            rows="3"
            maxlength="500"
            required
            aria-required="true"
            data-testid="reason-dialog-reason"
            [(ngModel)]="reason"
          ></textarea>
        </div>
        <div class="modal-actions">
          <button type="button" class="btn-secondary" (click)="cancel()">
            {{ 'COMMON.CANCEL' | translate }}
          </button>
          <button
            type="button"
            class="btn-danger"
            data-testid="reason-dialog-confirm"
            [disabled]="busy()"
            [appSubmitGuard]="!reason.trim()"
            [submitGuardFields]="[fieldId]"
            (click)="confirm()"
          >
            {{ confirmKey() | translate }}
          </button>
        </div>
      </div>
    </div>
  `,
})
export class ReasonDialogComponent {
  readonly titleKey = input.required<string>();
  readonly messageKey = input<string | null>(null);
  readonly labelKey = input('REASON_DIALOG.REASON');
  readonly confirmKey = input('COMMON.CONFIRM');
  /** A request is in flight: the confirm button waits. */
  readonly busy = input(false);

  /** The trimmed reason the user gave. */
  readonly confirmed = output<string>();
  readonly cancelled = output<void>();

  private readonly uid = ++nextId;
  protected readonly titleId = `reason-dialog-title-${this.uid}`;
  protected readonly fieldId = `reason-dialog-reason-${this.uid}`;
  protected reason = '';

  protected cancel(): void {
    if (this.busy()) return;
    this.cancelled.emit();
  }

  protected confirm(): void {
    const reason = this.reason.trim();
    if (reason) this.confirmed.emit(reason);
  }
}

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
 * that opened it; Escape over a typed reason asks before discarding it); the
 * reason is required, named where it is asked for, and handed over trimmed.
 * Content placed between the tags (the record being ended, a warning) is shown
 * above the reason.
 *
 * QA D1219 (round 23): it carries its own styling (shared/styles/shared-dialog.scss:
 * padding, the danger button) — a screen that does not style `.modal` showed
 * it flush on the box's edge with a grey confirm button.
 *
 * QA D1201 (round 22): it is also the only way the portal asks for a reason —
 * the browser's own `window.prompt` (a blank answer did nothing, silently) is
 * refused by `scripts/lib/no-native-prompt.test.mjs`.
 */
@Component({
  selector: 'app-reason-dialog',
  standalone: true,
  imports: [FormsModule, TranslateModule, ModalDialogDirective, SubmitGuardDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="modal-backdrop" tabindex="-1" (click)="cancel()" (keydown.escape)="cancel()">
      <div
        class="modal reason-dialog"
        appModalDialog
        tabindex="-1"
        [attr.aria-labelledby]="titleId"
        (escaped)="cancel()"
        (click)="$event.stopPropagation()"
        (keydown.escape)="$event.stopPropagation()"
        data-testid="reason-dialog"
      >
        <h2 [id]="titleId">{{ titleKey() | translate }}</h2>
        <div class="dialog-body">
          @if (messageKey()) {
            <p>{{ messageKey()! | translate }}</p>
          }
          <!-- What the action is about (the record's name, a warning), from the caller. -->
          <ng-content />
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
        </div>
        <div class="modal-actions">
          <button
            type="button"
            class="btn-secondary"
            data-testid="reason-dialog-cancel"
            (click)="cancel()"
          >
            {{ dismissKey() | translate }}
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
  styleUrl: '../styles/shared-dialog.scss',
})
export class ReasonDialogComponent {
  readonly titleKey = input.required<string>();
  readonly messageKey = input<string | null>(null);
  readonly labelKey = input('REASON_DIALOG.REASON');
  readonly confirmKey = input('COMMON.CONFIRM');
  /**
   * QA D1219 (round 23): the button that closes without acting. A confirm
   * label that reads like "Cancel" ("Annuler le transfert") is paired with
   * "Retour" (COMMON.BACK): two buttons never carry the same label
   * (scripts/lib/dialog-button-labels.test.mjs).
   */
  readonly dismissKey = input('COMMON.CANCEL');
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

import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { ModalDialogDirective } from '../a11y/modal-dialog.directive';

/** Ids are unique per dialog instance so two dialogs never share a title id. */
let nextId = 0;

/**
 * QA D1197 (round 22): "Révoquer" on a family member's access acted on one
 * click — no confirmation — and dropped the keyboard to the page body. This is
 * the confirmation for an action that ends something and needs no reason (the
 * reason-asking one is `<app-reason-dialog>`):
 *
 * ```html
 * @if (revoking(); as p) {
 *   <app-confirm-dialog titleKey="PORTAL.FAMILY.REVOKE_TITLE" [busy]="busy()"
 *     (confirmed)="revoke(p)" (cancelled)="revoking.set(null)">
 *     <p>{{ p.name }}</p>
 *   </app-confirm-dialog>
 * }
 * ```
 *
 * A modal (shared appModalDialog: focus in on Cancel — the safe choice —
 * Escape, focus back to the button that opened it). Content between the tags
 * says what is about to end.
 */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [TranslateModule, ModalDialogDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="modal-backdrop" tabindex="-1" (click)="cancel()" (keydown.escape)="cancel()">
      <div
        class="modal shared-dialog"
        appModalDialog
        tabindex="-1"
        [attr.aria-labelledby]="titleId"
        (escaped)="cancel()"
        (click)="$event.stopPropagation()"
        (keydown.escape)="$event.stopPropagation()"
        data-testid="confirm-dialog"
      >
        <h2 [id]="titleId">{{ titleKey() | translate }}</h2>
        <div class="dialog-body">
          @if (messageKey()) {
            <p>{{ messageKey()! | translate }}</p>
          }
          <ng-content />
        </div>
        <div class="modal-actions">
          <button
            type="button"
            class="btn-secondary"
            data-testid="confirm-dialog-cancel"
            (click)="cancel()"
          >
            {{ dismissKey() | translate }}
          </button>
          <button
            type="button"
            class="btn-danger"
            data-testid="confirm-dialog-confirm"
            [disabled]="busy()"
            (click)="confirmed.emit()"
          >
            {{ confirmKey() | translate }}
          </button>
        </div>
      </div>
    </div>
  `,
  styleUrl: '../styles/shared-dialog.scss',
})
export class ConfirmDialogComponent {
  readonly titleKey = input.required<string>();
  readonly messageKey = input<string | null>(null);
  readonly confirmKey = input('COMMON.CONFIRM');
  /** QA D1219 (round 23): never the same label as the confirm button. */
  readonly dismissKey = input('COMMON.CANCEL');
  /** A request is in flight: the confirm button waits. */
  readonly busy = input(false);

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  protected readonly titleId = `confirm-dialog-title-${++nextId}`;

  protected cancel(): void {
    if (this.busy()) return;
    this.cancelled.emit();
  }
}

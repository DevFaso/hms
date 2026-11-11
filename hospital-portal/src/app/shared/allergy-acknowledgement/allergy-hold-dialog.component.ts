import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { ModalDialogDirective } from '../a11y/modal-dialog.directive';
import { CdsCardListComponent } from '../cds-card/cds-card.component';
import { AllergyHold } from './allergy-acknowledgement';

let nextId = 0;

/**
 * QA D1563 / D1555, Policy 4 (user recommendation 2026-11-10, pending clinical approval): the allergy
 * conflict the server held a dose, a verification, a fill or a routing for. It shows the card and who
 * was notified (the server's sentence), and only closes: the nurse's or the pharmacist's own reason no
 * longer releases it, an authorized prescriber reviews it on the order. Holding or refusing a dose
 * stays possible from the screen behind.
 */
@Component({
  selector: 'app-allergy-hold-dialog',
  standalone: true,
  imports: [TranslateModule, ModalDialogDirective, CdsCardListComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="modal-backdrop"
      tabindex="-1"
      (click)="closed.emit()"
      (keydown.escape)="closed.emit()"
    >
      <div
        class="modal shared-dialog"
        appModalDialog
        tabindex="-1"
        [attr.aria-labelledby]="titleId"
        [attr.aria-describedby]="whyId"
        (escaped)="closed.emit()"
        (click)="$event.stopPropagation()"
        (keydown.escape)="$event.stopPropagation()"
        data-testid="allergy-hold-dialog"
      >
        <h2 [id]="titleId" data-testid="allergy-hold-title">
          {{ (stopped() ? 'ALLERGY_HOLD.TITLE_STOP' : 'ALLERGY_HOLD.TITLE') | translate }}
        </h2>
        <div class="dialog-body">
          <p class="form-error" role="alert" [id]="whyId" data-testid="allergy-hold-why">
            {{ refusal().why }}
          </p>
          <app-cds-card-list [cards]="refusal().cards" />
          <p>
            {{ (stopped() ? 'ALLERGY_HOLD.WHAT_NOW_STOP' : 'ALLERGY_HOLD.WHAT_NOW') | translate }}
          </p>
        </div>
        <div class="modal-actions">
          <button
            type="button"
            class="btn-secondary"
            data-testid="allergy-hold-close"
            (click)="closed.emit()"
          >
            {{ 'COMMON.CLOSE' | translate }}
          </button>
        </div>
      </div>
    </div>
  `,
  styleUrl: '../styles/shared-dialog.scss',
})
export class AllergyHoldDialogComponent {
  readonly refusal = input.required<AllergyHold>();
  readonly closed = output<void>();

  /** QA D1585: the prescriber stopped the order; the hold no longer awaits a review. */
  protected readonly stopped = computed(() => this.refusal().state === 'STOP');

  private readonly uid = ++nextId;
  protected readonly titleId = `allergy-hold-title-${this.uid}`;
  protected readonly whyId = `allergy-hold-why-${this.uid}`;
}

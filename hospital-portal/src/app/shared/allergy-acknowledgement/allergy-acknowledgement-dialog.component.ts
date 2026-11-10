import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { CdsCardListComponent } from '../cds-card/cds-card.component';
import { ReasonDialogComponent } from '../reason-dialog/reason-dialog.component';
import { AllergyAcknowledgementRefusal } from './allergy-acknowledgement';

/**
 * QA D1563 / D1555: the allergy the server refused a dose, a verification, a fill or a routing over,
 * with its card, and the reason the clinician gives to go ahead. Going back is always possible (a dose
 * can be held instead, a fill not made). Provisional rule, not a clinical sign-off: acknowledge with a
 * reason.
 */
@Component({
  selector: 'app-allergy-acknowledgement-dialog',
  standalone: true,
  imports: [ReasonDialogComponent, CdsCardListComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-reason-dialog
      titleKey="ALLERGY_ACK.TITLE"
      labelKey="ALLERGY_ACK.REASON_LABEL"
      confirmKey="ALLERGY_ACK.CONFIRM"
      dismissKey="COMMON.BACK"
      [busy]="busy()"
      (confirmed)="confirmed.emit($event)"
      (cancelled)="cancelled.emit()"
    >
      <p class="form-error" role="alert" data-testid="allergy-ack-why">{{ refusal().why }}</p>
      <app-cds-card-list [cards]="refusal().cards" />
    </app-reason-dialog>
  `,
})
export class AllergyAcknowledgementDialogComponent {
  readonly refusal = input.required<AllergyAcknowledgementRefusal>();
  readonly busy = input(false);
  readonly confirmed = output<string>();
  readonly cancelled = output<void>();
}

import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { CashSessionComponent } from './cash-session.component';

/**
 * Billing-money plan PR 10 (QA r65 D2672): the pharmacist's till. Dispense payments and counter sales land in the
 * PHARMACY session; the pharmacist counts and closes it here like any other till (the billing close was 403 for them).
 */
@Component({
  selector: 'app-pharmacy-cash-page',
  standalone: true,
  imports: [TranslateModule, CashSessionComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-container" data-testid="pharmacy-cash-page">
      <app-cash-session till="PHARMACY" />
    </div>
  `,
})
export class PharmacyCashPageComponent {}

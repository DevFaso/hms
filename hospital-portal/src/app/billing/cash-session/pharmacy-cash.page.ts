import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { CashSessionComponent } from './cash-session.component';

/**
 * Billing-money plan PR 10 (QA r65 D2672): the pharmacist's till. Dispense payments and counter sales land in the
 * PHARMACY session; the pharmacist counts and closes it here like any other till (the billing close was 403 for them).
 */
@Component({
  selector: 'app-pharmacy-cash-page',
  standalone: true,
  imports: [TranslateModule, PageHeaderComponent, CashSessionComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-container" data-testid="pharmacy-cash-page">
      <!-- QA D3506: the page title in the page-title style every other page uses (the card keeps its own heading). -->
      <app-page-header [title]="'BILLING.CASH_SESSION.TITLE' | translate" icon="point_of_sale" />
      <app-cash-session till="PHARMACY" />
    </div>
  `,
})
export class PharmacyCashPageComponent {}

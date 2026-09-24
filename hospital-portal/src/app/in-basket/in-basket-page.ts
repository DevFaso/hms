import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { InBasketPanelComponent } from '../dashboard/in-basket-panel/in-basket-panel';
import { LabResultsInboxComponent } from './lab-results-inbox/lab-results-inbox';
import { LAB_RESULT_REVIEW_ROLES } from './lab-result-review-access';
import { RoleContextService } from '../core/role-context.service';

@Component({
  selector: 'app-in-basket-page',
  standalone: true,
  imports: [TranslateModule, InBasketPanelComponent, LabResultsInboxComponent],
  template: `
    <div class="in-basket-page">
      <header class="page-header">
        <h1>{{ 'inBasket.pageTitle' | translate }}</h1>
        <p class="page-subtitle">{{ 'inBasket.pageSubtitle' | translate }}</p>
      </header>
      <!--
        B7 — the lab-results category. Gated here rather than on the route:
        /in-basket also admits nurses, midwives and two lab roles, and
        /me/results/review-queue admits only the three physician authorities,
        so a role that is on the page but not on the endpoint would otherwise
        get a 403 it never asked for.
      -->
      @if (canReviewLabResults()) {
        <app-lab-results-inbox />
      }
      <app-in-basket-panel />
    </div>
  `,
  styles: `
    .in-basket-page {
      max-width: 960px;
      margin: 0 auto;
      padding: 24px 16px;
    }
    .page-header {
      margin-bottom: 20px;
    }
    .page-header h1 {
      font-size: 1.5rem;
      font-weight: 600;
      color: #1e293b;
      margin: 0 0 4px;
    }
    .page-subtitle {
      font-size: 0.875rem;
      color: #64748b;
      margin: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InBasketPageComponent {
  private readonly roleContext = inject(RoleContextService);

  /**
   * `computed`, not a field read once in the constructor: `hasAnyActiveRole`
   * reads the service's role signals, and a role or scope change after the
   * page was built must move the gate with it.
   */
  readonly canReviewLabResults = computed(() =>
    this.roleContext.hasAnyActiveRole(LAB_RESULT_REVIEW_ROLES),
  );
}

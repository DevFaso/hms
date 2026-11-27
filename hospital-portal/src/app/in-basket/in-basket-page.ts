import { ChangeDetectionStrategy, Component, computed, inject, viewChild } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { InBasketPanelComponent } from '../dashboard/in-basket-panel/in-basket-panel';
import { LabResultsInboxComponent } from './lab-results-inbox/lab-results-inbox';
import { LAB_RESULT_REVIEW_ROLES } from './lab-result-review-access';
import { RoleContextService } from '../core/role-context.service';
import { WardNotificationsComponent } from './ward-notifications/ward-notifications.component';
import { UrgentAlertsBannerComponent } from '../shared/urgent-alerts/urgent-alerts-banner.component';

@Component({
  selector: 'app-in-basket-page',
  standalone: true,
  imports: [
    TranslateModule,
    InBasketPanelComponent,
    LabResultsInboxComponent,
    WardNotificationsComponent,
    UrgentAlertsBannerComponent,
  ],
  template: `
    <div class="in-basket-page">
      <header class="page-header">
        <h1>{{ 'inBasket.pageTitle' | translate }}</h1>
        <p class="page-subtitle">{{ 'inBasket.pageSubtitle' | translate }}</p>
      </header>
      <!--
        QA D4100 (r81): the urgent alerts the escalation ladder is waiting on come first, each with its own
        « Acquitter », above the laboratory results. They were under the whole results table.
      -->
      <app-urgent-alerts-banner mode="list" (acknowledged)="onUrgentAlertAcknowledged()" />
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
      <app-in-basket-panel [showTitle]="false" />
      <!-- QA D2582 (r65): a nurse's or midwife's ward alerts and reminders, as on the nurse station. -->
      @if (readsWardNotifications()) {
        <app-ward-notifications />
      }
    </div>
  `,
  styles: `
    .in-basket-page {
      max-width: 960px;
      margin: 0 auto;
      padding: 24px 16px;
    }
    .page-header {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 4px;
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
      color: var(--text-secondary);
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

  private readonly panel = viewChild(InBasketPanelComponent);

  /** An alert was acknowledged from the top block: the list below shows it acknowledged too. */
  onUrgentAlertAcknowledged(): void {
    this.panel()?.refresh();
  }

  /** QA D2582: the ward notifications, for the roles whose alerts arrive there (`GET /nurse/inbox`). */
  readonly readsWardNotifications = computed(() =>
    this.roleContext.hasAnyActiveRoleExactly(['ROLE_NURSE', 'ROLE_MIDWIFE']),
  );
}

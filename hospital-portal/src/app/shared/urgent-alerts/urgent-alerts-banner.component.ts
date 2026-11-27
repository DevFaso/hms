import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { InBasketItem, InBasketService } from '../../services/in-basket.service';
import { UrgentAlertsService } from '../../services/urgent-alerts.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { refusalReason } from '../refusal-reason';
import { currentLocale } from '../i18n/app-locale';
import { ServerTimePipe } from '../pipes/server-time.pipe';
import { URGENT_ALERT_READER_ROLES, urgentAlertAge } from './urgent-alert-age';

const SERVER_TIME = new ServerTimePipe();
/** The alerts are read again this often while a screen stays open. */
const REFRESH_MS = 60_000;

/**
 * QA D4100, D4101 (r81): the urgent alerts waiting on the reader, where she looks first.
 *
 * - `summary` (the dashboards): how many wait, since when the oldest waits, and a link to the in-basket. A nurse's
 *   dashboard said « 0 alertes » beside twenty-one unread urgent items.
 * - `list` (the top of the in-basket): the same, and each alert with its own « Acquitter » button, above the
 *   laboratory results. The alerts were three screens down, under the results table.
 *
 * Nothing is shown to a role no urgent alert is filed to, or when nothing waits.
 */
@Component({
  selector: 'app-urgent-alerts-banner',
  standalone: true,
  imports: [TranslateModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (reads() && waiting() > 0) {
      <section
        class="urgent-alerts"
        role="region"
        [attr.aria-label]="'URGENT_ALERTS.REGION' | translate"
        data-testid="urgent-alerts-banner"
      >
        <header class="ua-head">
          <span class="material-symbols-outlined ua-icon" aria-hidden="true">emergency</span>
          <div class="ua-text">
            @if (mine().toAcknowledge > 0) {
              <strong class="ua-count" data-testid="urgent-alerts-count">{{
                (mine().toAcknowledge > 1
                  ? 'URGENT_ALERTS.TO_ACK_MANY'
                  : 'URGENT_ALERTS.TO_ACK_ONE'
                ) | translate: { count: mine().toAcknowledge }
              }}</strong>
            }
            @if (mine().documentedWaiting > 0) {
              <span class="ua-documented" data-testid="urgent-alerts-documented">{{
                (mine().documentedWaiting > 1
                  ? 'URGENT_ALERTS.DOCUMENTED_MANY'
                  : 'URGENT_ALERTS.DOCUMENTED_ONE'
                ) | translate: { count: mine().documentedWaiting }
              }}</span>
            }
            @if (age(); as oldest) {
              <span class="ua-age" data-testid="urgent-alerts-age">{{
                'URGENT_ALERTS.OLDEST' | translate: { age: oldest.key | translate: oldest.params }
              }}</span>
            }
          </div>
          @if (mode() === 'summary') {
            <a class="ua-open" routerLink="/in-basket" data-testid="urgent-alerts-open">{{
              'URGENT_ALERTS.OPEN_IN_BASKET' | translate
            }}</a>
          }
        </header>
        @if (mode() === 'list' && mine().items.length > 0) {
          <ul class="ua-list">
            @for (item of mine().items; track item.id) {
              <li class="ua-item">
                <div class="ua-item-text">
                  <span class="ua-item-title">{{ item.title }}</span>
                  <span class="ua-item-meta">
                    @if (item.patientName) {
                      <span>{{ item.patientName }}</span>
                    }
                    <span>{{ formatDate(item.createdAt) }}</span>
                  </span>
                </div>
                <button
                  type="button"
                  class="ua-ack"
                  [disabled]="acknowledging() === item.id"
                  (click)="acknowledge(item)"
                  data-testid="urgent-alert-acknowledge"
                >
                  {{ 'URGENT_ALERTS.ACKNOWLEDGE' | translate }}
                </button>
              </li>
            }
          </ul>
        }
      </section>
    }
  `,
  styles: `
    .urgent-alerts {
      border: 1px solid #fca5a5;
      border-left: 4px solid #dc2626;
      background: #fef2f2;
      border-radius: 8px;
      padding: 12px 16px;
      margin-bottom: 16px;
    }
    .ua-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 12px;
    }
    .ua-icon {
      color: #b91c1c;
    }
    .ua-text {
      display: flex;
      flex-direction: column;
      gap: 2px;
      flex: 1 1 200px;
      min-width: 0;
    }
    .ua-count {
      color: #7f1d1d;
      font-size: 1rem;
    }
    .ua-documented,
    .ua-age {
      color: #7f1d1d;
      font-size: 0.875rem;
    }
    .ua-open,
    .ua-ack {
      min-height: 44px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 16px;
      border-radius: 6px;
      border: 1px solid #b91c1c;
      background: #b91c1c;
      color: #fff;
      font-weight: 600;
      font-size: 0.875rem;
      text-decoration: none;
      cursor: pointer;
    }
    .ua-ack[disabled] {
      opacity: 0.6;
      cursor: default;
    }
    .ua-list {
      list-style: none;
      margin: 12px 0 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .ua-item {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 12px;
      background: #fff;
      border: 1px solid #fecaca;
      border-radius: 6px;
      padding: 10px 12px;
    }
    .ua-item-text {
      display: flex;
      flex-direction: column;
      gap: 4px;
      flex: 1 1 220px;
      min-width: 0;
    }
    .ua-item-title {
      color: #1e293b;
      font-size: 0.9rem;
      overflow-wrap: anywhere;
    }
    .ua-item-meta {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 12px;
      color: var(--text-secondary, #475569);
      font-size: 0.8rem;
    }
    @media (max-width: 480px) {
      .ua-open,
      .ua-ack {
        flex: 1 1 100%;
      }
    }
  `,
})
export class UrgentAlertsBannerComponent {
  private readonly alerts = inject(UrgentAlertsService);
  private readonly inBasket = inject(InBasketService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly mode = input<'summary' | 'list'>('summary');
  /** An alert was acknowledged here: the page reads its own lists again. */
  readonly acknowledged = output<void>();

  readonly mine = this.alerts.mine;
  readonly waiting = this.alerts.waiting;
  readonly acknowledging = signal<string | null>(null);
  /** Moves with the refresh, so the age shown never stays at the value of the first render. */
  private readonly now = signal(new Date());

  /** The roles an urgent alert is filed to; nobody else is asked, so nobody else gets a 403. */
  readonly reads = computed(() => this.roleContext.hasAnyActiveRole(URGENT_ALERT_READER_ROLES));

  readonly age = computed(() => urgentAlertAge(this.mine().oldestRaisedAt, this.now()));

  constructor() {
    // The read follows the hospital scope: what was read under the previous hospital is dropped first.
    effect(() => {
      this.roleContext.effectiveHospitalIdForRequest();
      const reads = this.reads();
      untracked(() => {
        this.alerts.resetMine();
        if (reads) this.refresh();
      });
    });
    const timer = setInterval(() => {
      if (this.reads()) this.refresh();
    }, REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  refresh(): void {
    this.now.set(new Date());
    this.alerts.refreshMine();
  }

  acknowledge(item: InBasketItem): void {
    if (this.acknowledging()) return;
    this.acknowledging.set(item.id);
    this.inBasket.acknowledge(item.id).subscribe({
      next: () => {
        this.acknowledging.set(null);
        this.toast.success(this.translate.instant('inBasket.acknowledged'));
        this.refresh();
        this.alerts.refreshMarks();
        this.acknowledged.emit();
      },
      // The server says why an acknowledgement is refused (an urgent clinical item needs a clinician).
      error: (err: unknown) => {
        this.acknowledging.set(null);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('inBasket.error.acknowledge'),
        );
        this.refresh();
      },
    });
  }

  formatDate(iso: string | null): string {
    if (!iso) return '';
    return new Date(SERVER_TIME.transform(iso) as string).toLocaleString(currentLocale(), {
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
    });
  }
}

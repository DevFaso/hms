import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';

import { ChargebackRow, ChargebackService } from '../../services/chargeback.service';

interface WindowState {
  from: string;
  to: string;
}

/**
 * Super-admin Control Tower per-tenant cost panel (roadmap row 44
 * follow-on). Renders the chargeback rollup with stable {@code hospitalId}
 * keying + the computed monetary amount in the configured currency.
 *
 * <p>Flag-off contract: the backing endpoint returns 404 when
 * {@code app.observability.tenant-cost.enabled=false}; the component
 * surfaces that as the "feature disabled" empty-state so the operator
 * isn't left looking at a generic toast.
 */
@Component({
  selector: 'app-cost-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="cost-panel" data-testid="cost-panel">
      <header class="cost-panel__header">
        <h1>{{ 'COST_PANEL.TITLE' | translate }}</h1>
        <p class="cost-panel__subtitle">{{ 'COST_PANEL.SUBTITLE' | translate }}</p>
      </header>

      <form class="cost-panel__filters" (submit)="reload($event)" data-testid="cost-panel-filters">
        <div class="form-row">
          <label for="cost-from">{{ 'COST_PANEL.FROM' | translate }}</label>
          <input
            id="cost-from"
            name="from"
            type="date"
            [(ngModel)]="window.from"
            data-testid="cost-from"
          />
        </div>
        <div class="form-row">
          <label for="cost-to">{{ 'COST_PANEL.TO' | translate }}</label>
          <input id="cost-to" name="to" type="date" [(ngModel)]="window.to" data-testid="cost-to" />
        </div>
        <button type="submit" class="btn-primary" [disabled]="loading()" data-testid="cost-reload">
          {{ loading() ? ('COST_PANEL.LOADING' | translate) : ('COST_PANEL.REFRESH' | translate) }}
        </button>
      </form>

      <p *ngIf="featureDisabled()" class="cost-panel__empty" data-testid="cost-disabled">
        {{ 'COST_PANEL.FEATURE_DISABLED' | translate }}
      </p>

      <p *ngIf="error()" class="cost-panel__error" data-testid="cost-error">
        {{ 'COST_PANEL.ERROR' | translate }}
      </p>

      <table
        *ngIf="!featureDisabled() && !error() && rows().length > 0"
        class="data-table"
        data-testid="cost-rows"
      >
        <thead>
          <tr>
            <th scope="col">{{ 'COST_PANEL.COL_HOSPITAL' | translate }}</th>
            <th scope="col" class="num">{{ 'COST_PANEL.COL_AUDIT_EVENTS' | translate }}</th>
            <th scope="col" class="num">{{ 'COST_PANEL.COL_SPLUNK' | translate }}</th>
            <th scope="col" class="num">{{ 'COST_PANEL.COL_GRAFANA' | translate }}</th>
            <th scope="col" class="num">{{ 'COST_PANEL.COL_STORAGE_GIB' | translate }}</th>
            <th scope="col" class="num">{{ 'COST_PANEL.COL_AMOUNT' | translate }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            *ngFor="let row of rows(); trackBy: trackByHospitalId"
            [attr.data-hospital-id]="row.hospitalId"
          >
            <td>{{ row.hospitalName }}</td>
            <td class="num">{{ row.auditEventCount | number: '1.0-0' }}</td>
            <td class="num">{{ row.splunkEventCount | number: '1.0-0' }}</td>
            <td class="num">{{ row.grafanaSeriesCardinality | number: '1.0-0' }}</td>
            <td class="num">
              {{ row.postgresStorageBytes / 1073741824 | number: '1.2-2' }}
            </td>
            <td class="num">{{ row.chargebackAmount | number: '1.2-2' }} {{ row.currency }}</td>
          </tr>
        </tbody>
      </table>

      <p
        *ngIf="!featureDisabled() && !error() && !loading() && rows().length === 0"
        class="cost-panel__empty"
        data-testid="cost-empty"
      >
        {{ 'COST_PANEL.NO_DATA' | translate }}
      </p>
    </section>
  `,
  styles: [
    `
      .cost-panel {
        padding: 1.5rem;
      }
      .cost-panel__header h1 {
        margin-bottom: 0.25rem;
      }
      .cost-panel__subtitle {
        margin-top: 0;
        color: var(--muted, #64748b);
      }
      .cost-panel__filters {
        display: flex;
        gap: 1rem;
        align-items: end;
        margin: 1rem 0 1.5rem;
      }
      .form-row {
        display: flex;
        flex-direction: column;
      }
      .form-row label {
        font-weight: 500;
        margin-bottom: 0.25rem;
      }
      .cost-panel__error {
        color: var(--danger, #b00020);
      }
      .cost-panel__empty {
        color: var(--muted, #64748b);
        font-style: italic;
      }
      .num {
        text-align: right;
        font-variant-numeric: tabular-nums;
      }
    `,
  ],
})
export class CostPanelComponent implements OnInit {
  protected readonly rows = signal<ChargebackRow[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal(false);
  protected readonly featureDisabled = signal(false);

  protected window: WindowState = { from: '', to: '' };

  private readonly service = inject(ChargebackService);

  ngOnInit(): void {
    this.fetch();
  }

  protected reload(event: Event): void {
    event.preventDefault();
    this.fetch();
  }

  protected trackByHospitalId(_index: number, row: ChargebackRow): string {
    return row.hospitalId;
  }

  private fetch(): void {
    this.loading.set(true);
    this.error.set(false);
    this.featureDisabled.set(false);
    this.service.perTenant(this.window.from || undefined, this.window.to || undefined).subscribe({
      next: (rows) => {
        this.rows.set(rows ?? []);
        this.loading.set(false);
      },
      error: (err: { status?: number }) => {
        this.rows.set([]);
        this.loading.set(false);
        // 404 from the backing endpoint means the flag is off — the
        // operator-actionable signal is "set APP_OBSERVABILITY_TENANT_COST_ENABLED=true",
        // not a generic error. Other status codes surface as the
        // generic error state.
        if (err?.status === 404) {
          this.featureDisabled.set(true);
        } else {
          this.error.set(true);
        }
      },
    });
  }
}

import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

import { Dhis2Service } from '../../../services/integrations/dhis2.service';
import {
  DHIS2_PERIOD_REGEX,
  DHIS2_UID_REGEX,
  Dhis2ExportRun,
  Dhis2PeriodType,
  Dhis2RunStatus,
  Dhis2TriggerRequest,
} from '../../../services/integrations/dhis2.model';
import { RoleContextService } from '../../../core/role-context.service';
import { ToastService } from '../../../core/toast.service';

/**
 * Manual-trigger panel + last-N runs table.
 *
 * <p>Cancels in-flight load on hospital switch (clinical-safety pattern
 * from Storyboard / Chart Review / eMAR). The trigger button is
 * disabled while a request is pending so a double-click cannot fire
 * two runs.
 */
@Component({
  selector: 'app-dhis2-export-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="dhis2-export" data-testid="dhis2-export-panel">
      <header class="dhis2-export__header">
        <h2>{{ 'DHIS2.PANEL.HEADING' | translate }}</h2>
      </header>

      <form class="dhis2-export__form" (ngSubmit)="onTrigger()" data-testid="dhis2-export-form">
        <label>
          {{ 'DHIS2.PANEL.DATASET_UID' | translate }}
          <input
            type="text"
            name="datasetUid"
            data-testid="dhis2-export-dataset"
            [(ngModel)]="datasetUid"
            required
            maxlength="11"
            [pattern]="uidPattern"
          />
        </label>

        <label>
          {{ 'DHIS2.PANEL.PERIOD_TYPE' | translate }}
          <select name="periodType" data-testid="dhis2-export-period-type" [(ngModel)]="periodType">
            <option value="MONTHLY">{{ 'DHIS2.PERIOD.MONTHLY' | translate }}</option>
            <option value="WEEKLY">{{ 'DHIS2.PERIOD.WEEKLY' | translate }}</option>
            <option value="YEARLY">{{ 'DHIS2.PERIOD.YEARLY' | translate }}</option>
          </select>
        </label>

        <label>
          {{ 'DHIS2.PANEL.PERIOD_ISO' | translate }}
          <input
            type="text"
            name="periodIso"
            data-testid="dhis2-export-period-iso"
            [(ngModel)]="periodIso"
            required
            [pattern]="periodPattern"
            placeholder="202604"
          />
        </label>

        <button
          type="submit"
          class="btn-primary"
          data-testid="dhis2-export-trigger"
          [disabled]="triggering() || !canTrigger()"
        >
          {{ 'DHIS2.PANEL.TRIGGER' | translate }}
        </button>
      </form>

      <p *ngIf="loading()" data-testid="dhis2-export-loading">
        {{ 'DHIS2.PANEL.LOADING' | translate }}
      </p>

      <p *ngIf="error()" class="dhis2-export__error" data-testid="dhis2-export-error">
        {{ 'DHIS2.PANEL.ERROR' | translate }}
      </p>

      <table
        class="data-table"
        *ngIf="!loading() && !error() && runs().length > 0"
        data-testid="dhis2-export-runs"
      >
        <thead>
          <tr>
            <th>{{ 'DHIS2.PANEL.COL_STARTED' | translate }}</th>
            <th>{{ 'DHIS2.PANEL.COL_DATASET' | translate }}</th>
            <th>{{ 'DHIS2.PANEL.COL_PERIOD' | translate }}</th>
            <th>{{ 'DHIS2.PANEL.COL_STATUS' | translate }}</th>
            <th>{{ 'DHIS2.PANEL.COL_VALUES' | translate }}</th>
            <th>{{ 'DHIS2.PANEL.COL_SKIPPED' | translate }}</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let r of runs(); trackBy: trackById" [attr.data-run-id]="r.id">
            <td>{{ r.startedAt | date: 'short' }}</td>
            <td>{{ r.datasetUid }}</td>
            <td>{{ r.periodIso }}</td>
            <td>
              <span class="status-pill" [ngClass]="statusClass(r.status)">{{ r.status }}</span>
            </td>
            <td>{{ r.valueCount }}</td>
            <td>{{ r.skippedCount }}</td>
          </tr>
        </tbody>
      </table>

      <p
        *ngIf="!loading() && !error() && runs().length === 0"
        class="dhis2-export__empty"
        data-testid="dhis2-export-empty"
      >
        {{ 'DHIS2.PANEL.EMPTY' | translate }}
      </p>
    </section>
  `,
  styles: [
    `
      .dhis2-export {
        padding: 1.5rem;
      }
      .dhis2-export__form {
        display: flex;
        flex-wrap: wrap;
        gap: 1rem;
        align-items: flex-end;
        margin-bottom: 1rem;
      }
      .dhis2-export__form label {
        display: flex;
        flex-direction: column;
        font-size: 0.875rem;
      }
      .dhis2-export__error {
        color: var(--danger, #b00020);
      }
      .status-pill {
        padding: 0.125rem 0.5rem;
        border-radius: 999px;
        font-size: 0.75rem;
      }
      .status-pill--success {
        background: var(--success-bg, #e6f4ea);
        color: var(--success, #1e8e3e);
      }
      .status-pill--partial {
        background: var(--warning-bg, #fef7e0);
        color: var(--warning, #b06000);
      }
      .status-pill--failed {
        background: var(--danger-bg, #fce8e6);
        color: var(--danger, #b00020);
      }
      .status-pill--pending {
        background: var(--neutral-bg, #f1f3f4);
        color: var(--neutral, #5f6368);
      }
    `,
  ],
})
export class Dhis2ExportPanelComponent implements OnInit, OnDestroy {
  protected readonly runs = signal<Dhis2ExportRun[]>([]);
  protected readonly loading = signal(false);
  protected readonly triggering = signal(false);
  protected readonly error = signal(false);

  protected datasetUid = '';
  protected periodType: Dhis2PeriodType = 'MONTHLY';
  protected periodIso = '';

  protected readonly uidPattern = DHIS2_UID_REGEX.source;
  protected readonly periodPattern = DHIS2_PERIOD_REGEX.source;

  private readonly dhis2 = inject(Dhis2Service);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private listSub?: Subscription;
  private triggerSub?: Subscription;

  ngOnInit(): void {
    this.refreshRuns();
  }

  ngOnDestroy(): void {
    this.listSub?.unsubscribe();
    this.triggerSub?.unsubscribe();
  }

  protected canTrigger(): boolean {
    return (
      DHIS2_UID_REGEX.test(this.datasetUid) &&
      DHIS2_PERIOD_REGEX.test(this.periodIso) &&
      this.roleContext.activeHospitalId !== null
    );
  }

  protected onTrigger(): void {
    if (!this.canTrigger() || this.triggering()) return;
    const hospitalId = this.roleContext.activeHospitalId;
    if (!hospitalId) return;

    const body: Dhis2TriggerRequest = {
      hospitalId,
      datasetUid: this.datasetUid,
      periodType: this.periodType,
      periodIso: this.periodIso,
    };
    this.triggering.set(true);
    this.triggerSub?.unsubscribe();
    this.triggerSub = this.dhis2.triggerExport(body).subscribe({
      next: () => {
        this.triggering.set(false);
        this.toast.success('DHIS2 export triggered');
        this.refreshRuns();
      },
      error: () => {
        this.triggering.set(false);
        this.toast.error('Could not trigger DHIS2 export');
      },
    });
  }

  protected refreshRuns(): void {
    const hospitalId = this.roleContext.activeHospitalId;
    if (!hospitalId) {
      this.runs.set([]);
      return;
    }
    this.loading.set(true);
    this.error.set(false);
    this.listSub?.unsubscribe();
    this.listSub = this.dhis2.listRuns(hospitalId).subscribe({
      next: (page) => {
        this.runs.set(page.content);
        this.loading.set(false);
      },
      error: () => {
        this.runs.set([]);
        this.loading.set(false);
        this.error.set(true);
      },
    });
  }

  protected statusClass(status: Dhis2RunStatus): string {
    switch (status) {
      case 'SUCCESS':
        return 'status-pill--success';
      case 'PARTIAL':
        return 'status-pill--partial';
      case 'FAILED':
        return 'status-pill--failed';
      default:
        return 'status-pill--pending';
    }
  }

  protected trackById(_i: number, r: Dhis2ExportRun): string {
    return r.id;
  }
}

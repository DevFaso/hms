import { ChangeDetectionStrategy, Component, OnDestroy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

import { Dhis2Service } from '../../../services/integrations/dhis2.service';
import {
  DHIS2_UID_REGEX,
  Dhis2DataElementMapping,
  Dhis2DataElementMappingRequest,
  Dhis2PeriodType,
} from '../../../services/integrations/dhis2.model';
import { RoleContextService } from '../../../core/role-context.service';
import { ToastService } from '../../../core/toast.service';

/**
 * Per-hospital editor for HMS-concept-code → DHIS2-dataElement-UID
 * mappings. Filters by datasetUid because DHIS2 dataElements live
 * inside datasets and a code can map differently per dataset.
 */
@Component({
  selector: 'app-dhis2-mapping-editor',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="dhis2-mappings" data-testid="dhis2-mapping-editor">
      <header class="dhis2-mappings__header">
        <h2>{{ 'DHIS2.MAPPINGS.HEADING' | translate }}</h2>
      </header>

      <div class="dhis2-mappings__filter">
        <label>
          {{ 'DHIS2.MAPPINGS.FILTER_DATASET' | translate }}
          <input
            type="text"
            data-testid="dhis2-mapping-filter-dataset"
            [(ngModel)]="filterDatasetUid"
            (ngModelChange)="onFilterChange()"
            maxlength="11"
            [pattern]="uidPattern"
          />
        </label>
      </div>

      <p *ngIf="loading()" data-testid="dhis2-mapping-loading">
        {{ 'DHIS2.MAPPINGS.LOADING' | translate }}
      </p>

      <p *ngIf="error()" class="dhis2-mappings__error" data-testid="dhis2-mapping-error">
        {{ 'DHIS2.MAPPINGS.ERROR' | translate }}
      </p>

      <table
        class="data-table"
        *ngIf="!loading() && !error() && rows().length > 0"
        data-testid="dhis2-mapping-table"
      >
        <thead>
          <tr>
            <th>{{ 'DHIS2.MAPPINGS.COL_SYSTEM' | translate }}</th>
            <th>{{ 'DHIS2.MAPPINGS.COL_CODE' | translate }}</th>
            <th>{{ 'DHIS2.MAPPINGS.COL_DATAELEMENT' | translate }}</th>
            <th>{{ 'DHIS2.MAPPINGS.COL_COC' | translate }}</th>
            <th>{{ 'DHIS2.MAPPINGS.COL_PERIOD' | translate }}</th>
            <th>{{ 'DHIS2.MAPPINGS.COL_ACTIVE' | translate }}</th>
            <th>{{ 'COMMON.ACTIONS' | translate }}</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let m of rows(); trackBy: trackById" [attr.data-mapping-id]="m.id">
            <td>{{ m.hmsConceptSystem }}</td>
            <td>{{ m.hmsConceptCode }}</td>
            <td>{{ m.dhis2DataElementUid }}</td>
            <td>{{ m.dhis2CategoryOptionComboUid || '—' }}</td>
            <td>{{ m.periodType }}</td>
            <td>{{ m.active ? '✓' : '·' }}</td>
            <td>
              <button
                type="button"
                class="action-link delete-link"
                (click)="onDelete(m.id)"
                data-testid="dhis2-mapping-delete"
              >
                {{ 'COMMON.DELETE' | translate }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <p
        *ngIf="!loading() && !error() && rows().length === 0"
        class="dhis2-mappings__empty"
        data-testid="dhis2-mapping-empty"
      >
        {{ 'DHIS2.MAPPINGS.EMPTY' | translate }}
      </p>

      <form class="dhis2-mappings__add" (ngSubmit)="onAdd()" data-testid="dhis2-mapping-add-form">
        <h3>{{ 'DHIS2.MAPPINGS.ADD' | translate }}</h3>
        <label>
          {{ 'DHIS2.MAPPINGS.COL_SYSTEM' | translate }}
          <input
            type="text"
            data-testid="dhis2-mapping-add-system"
            [(ngModel)]="newRow.hmsConceptSystem"
            name="hmsConceptSystem"
            required
          />
        </label>
        <label>
          {{ 'DHIS2.MAPPINGS.COL_CODE' | translate }}
          <input
            type="text"
            data-testid="dhis2-mapping-add-code"
            [(ngModel)]="newRow.hmsConceptCode"
            name="hmsConceptCode"
            required
          />
        </label>
        <label>
          {{ 'DHIS2.MAPPINGS.COL_DATAELEMENT' | translate }}
          <input
            type="text"
            data-testid="dhis2-mapping-add-dataelement"
            [(ngModel)]="newRow.dhis2DataElementUid"
            name="dhis2DataElementUid"
            required
            [pattern]="uidPattern"
            maxlength="11"
          />
        </label>
        <label>
          {{ 'DHIS2.MAPPINGS.COL_COC' | translate }}
          <input
            type="text"
            data-testid="dhis2-mapping-add-coc"
            [(ngModel)]="newRow.dhis2CategoryOptionComboUid"
            name="dhis2CategoryOptionComboUid"
            [pattern]="uidPattern"
            maxlength="11"
          />
        </label>
        <label>
          {{ 'DHIS2.MAPPINGS.COL_PERIOD' | translate }}
          <select
            data-testid="dhis2-mapping-add-period"
            [(ngModel)]="newRow.periodType"
            name="periodType"
          >
            <option value="MONTHLY">{{ 'DHIS2.PERIOD.MONTHLY' | translate }}</option>
            <option value="WEEKLY">{{ 'DHIS2.PERIOD.WEEKLY' | translate }}</option>
            <option value="YEARLY">{{ 'DHIS2.PERIOD.YEARLY' | translate }}</option>
          </select>
        </label>
        <button
          type="submit"
          class="btn-primary"
          data-testid="dhis2-mapping-add-submit"
          [disabled]="!canAdd() || saving()"
        >
          {{ 'COMMON.ADD' | translate }}
        </button>
      </form>
    </section>
  `,
  styles: [
    `
      .dhis2-mappings {
        padding: 1.5rem;
      }
      .dhis2-mappings__error {
        color: var(--danger, #b00020);
      }
      .dhis2-mappings__add {
        margin-top: 1.5rem;
        display: flex;
        flex-wrap: wrap;
        gap: 1rem;
        align-items: flex-end;
      }
      .dhis2-mappings__add label {
        display: flex;
        flex-direction: column;
        font-size: 0.875rem;
      }
    `,
  ],
})
export class Dhis2MappingEditorComponent implements OnDestroy {
  protected readonly rows = signal<Dhis2DataElementMapping[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal(false);
  protected readonly saving = signal(false);

  protected filterDatasetUid = '';
  protected newRow: Dhis2DataElementMappingRequest = this.emptyRow();

  protected readonly uidPattern = DHIS2_UID_REGEX.source;

  private readonly dhis2 = inject(Dhis2Service);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private listSub?: Subscription;

  ngOnDestroy(): void {
    this.listSub?.unsubscribe();
  }

  protected onFilterChange(): void {
    // Keep the create-row's datasetUid in sync with the filter, otherwise
    // newRow.datasetUid stays stuck on whatever filterDatasetUid was when
    // emptyRow() ran (typically blank), and canAdd() / createMapping()
    // would respectively block forever or send the wrong dataset.
    this.newRow.datasetUid = this.filterDatasetUid;
    if (DHIS2_UID_REGEX.test(this.filterDatasetUid)) {
      this.refresh();
    } else {
      this.rows.set([]);
    }
  }

  protected canAdd(): boolean {
    return (
      DHIS2_UID_REGEX.test(this.newRow.dhis2DataElementUid) &&
      DHIS2_UID_REGEX.test(this.newRow.datasetUid) &&
      !!this.newRow.hmsConceptSystem.trim() &&
      !!this.newRow.hmsConceptCode.trim() &&
      this.roleContext.activeHospitalId !== null
    );
  }

  protected onAdd(): void {
    const hospitalId = this.roleContext.activeHospitalId;
    if (!hospitalId || !this.canAdd() || this.saving()) return;
    this.saving.set(true);
    this.dhis2.createMapping(hospitalId, this.newRow).subscribe({
      next: () => {
        this.saving.set(false);
        this.toast.success('Mapping added');
        this.newRow = this.emptyRow();
        this.refresh();
      },
      error: () => {
        this.saving.set(false);
        this.toast.error('Could not add mapping');
      },
    });
  }

  protected onDelete(id: string): void {
    const hospitalId = this.roleContext.activeHospitalId;
    if (!hospitalId) return;
    this.dhis2.deleteMapping(id, hospitalId).subscribe({
      next: () => {
        this.toast.success('Mapping deleted');
        this.refresh();
      },
      error: () => this.toast.error('Could not delete mapping'),
    });
  }

  protected refresh(): void {
    const hospitalId = this.roleContext.activeHospitalId;
    if (!hospitalId || !DHIS2_UID_REGEX.test(this.filterDatasetUid)) {
      this.rows.set([]);
      return;
    }
    this.loading.set(true);
    this.error.set(false);
    this.listSub?.unsubscribe();
    this.listSub = this.dhis2.listMappings(hospitalId, this.filterDatasetUid).subscribe({
      next: (page) => {
        this.rows.set(page.content);
        this.loading.set(false);
      },
      error: () => {
        this.rows.set([]);
        this.loading.set(false);
        this.error.set(true);
      },
    });
  }

  protected trackById(_i: number, m: Dhis2DataElementMapping): string {
    return m.id;
  }

  private emptyRow(): Dhis2DataElementMappingRequest {
    return {
      hmsConceptSystem: 'http://hl7.org/fhir/sid/cvx',
      hmsConceptCode: '',
      dhis2DataElementUid: '',
      dhis2CategoryOptionComboUid: null,
      periodType: 'MONTHLY' as Dhis2PeriodType,
      datasetUid: this.filterDatasetUid,
      active: true,
    };
  }
}

import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  AcuityLevel,
  AdmissionType,
  AdtIntakeConfig,
  AdtIntakeConfigRequest,
  AdtIntakeConfigService,
  EncounterType,
} from '../../services/adt-intake-config.service';
import { ToastService } from '../../core/toast.service';

const ADMISSION_TYPES: AdmissionType[] = [
  'EMERGENCY',
  'ELECTIVE',
  'URGENT',
  'NEWBORN',
  'TRANSFER',
  'OBSERVATION',
  'DAY_CASE',
  'LABOR_DELIVERY',
  'PSYCHIATRIC',
];

const ACUITY_LEVELS: AcuityLevel[] = [
  'LEVEL_1_MINIMAL',
  'LEVEL_2_MODERATE',
  'LEVEL_3_MAJOR',
  'LEVEL_4_SEVERE',
  'LEVEL_5_CRITICAL',
];

const ENCOUNTER_TYPES: EncounterType[] = [
  'CONSULTATION',
  'FOLLOW_UP',
  'EMERGENCY',
  'SURGERY',
  'LAB',
  'OUTPATIENT',
  'INPATIENT',
  'TELEHEALTH',
];

interface IntakeFormState {
  hospitalId: string;
  admittingProviderId: string;
  departmentId: string;
  defaultAssignmentId: string;
  defaultAdmissionType: AdmissionType;
  defaultAcuityLevel: AcuityLevel;
  defaultEncounterType: EncounterType;
  defaultChiefComplaint: string;
  enabled: boolean;
}

const EMPTY_FORM: IntakeFormState = {
  hospitalId: '',
  admittingProviderId: '',
  departmentId: '',
  defaultAssignmentId: '',
  defaultAdmissionType: 'EMERGENCY',
  defaultAcuityLevel: 'LEVEL_2_MODERATE',
  defaultEncounterType: 'INPATIENT',
  defaultChiefComplaint: 'Auto-created from ADT^A01',
  enabled: false,
};

/**
 * Admin surface for the per-hospital ADT auto-create defaults
 * (roadmap row 24 admin UI). Lists the existing intake configs and
 * exposes an upsert form scoped to one hospital at a time.
 */
@Component({
  selector: 'app-adt-intake-config',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="adt-intake" data-testid="adt-intake-admin">
      <header class="adt-intake__header">
        <h1>{{ 'ADT_INTAKE.TITLE' | translate }}</h1>
        <p class="adt-intake__subtitle">{{ 'ADT_INTAKE.SUBTITLE' | translate }}</p>
      </header>

      <p *ngIf="loading()" class="adt-intake__loading" data-testid="adt-intake-loading">
        {{ 'ADT_INTAKE.LOADING' | translate }}
      </p>

      <p *ngIf="error()" class="adt-intake__error" data-testid="adt-intake-error">
        {{ 'ADT_INTAKE.ERROR' | translate }}
      </p>

      <table
        *ngIf="!loading() && !error() && configs().length > 0"
        class="data-table"
        data-testid="adt-intake-table"
      >
        <thead>
          <tr>
            <th scope="col">{{ 'ADT_INTAKE.COL_HOSPITAL' | translate }}</th>
            <th scope="col">{{ 'ADT_INTAKE.COL_ADMISSION_TYPE' | translate }}</th>
            <th scope="col">{{ 'ADT_INTAKE.COL_ACUITY' | translate }}</th>
            <th scope="col">{{ 'ADT_INTAKE.COL_ENCOUNTER_TYPE' | translate }}</th>
            <th scope="col">{{ 'ADT_INTAKE.COL_ENABLED' | translate }}</th>
            <th scope="col">{{ 'COMMON.ACTIONS' | translate }}</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let cfg of configs(); trackBy: trackById" [attr.data-config-id]="cfg.id">
            <td>{{ cfg.hospitalName || cfg.hospitalId }}</td>
            <td>{{ cfg.defaultAdmissionType }}</td>
            <td>{{ cfg.defaultAcuityLevel }}</td>
            <td>{{ cfg.defaultEncounterType }}</td>
            <td>
              <span
                class="adt-intake__pill"
                [class.adt-intake__pill--on]="cfg.enabled"
                [class.adt-intake__pill--off]="!cfg.enabled"
              >
                {{
                  cfg.enabled
                    ? ('ADT_INTAKE.STATE_ENABLED' | translate)
                    : ('ADT_INTAKE.STATE_DISABLED' | translate)
                }}
              </span>
            </td>
            <td class="actions">
              <button
                type="button"
                class="action-link"
                (click)="edit(cfg)"
                [attr.data-testid]="'adt-intake-edit-' + cfg.id"
              >
                {{ 'COMMON.EDIT' | translate }}
              </button>
              <button
                type="button"
                class="action-link delete-link"
                (click)="remove(cfg)"
                [attr.data-testid]="'adt-intake-delete-' + cfg.id"
              >
                {{ 'COMMON.DELETE' | translate }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <p
        *ngIf="!loading() && !error() && configs().length === 0"
        class="adt-intake__empty"
        data-testid="adt-intake-empty"
      >
        {{ 'ADT_INTAKE.EMPTY' | translate }}
      </p>

      <form
        class="adt-intake__form"
        (submit)="save($event)"
        novalidate
        data-testid="adt-intake-form"
      >
        <h2>
          {{
            editingId()
              ? ('ADT_INTAKE.FORM_EDIT_TITLE' | translate)
              : ('ADT_INTAKE.FORM_CREATE_TITLE' | translate)
          }}
        </h2>

        <div class="form-row">
          <label for="adt-hospital">{{ 'ADT_INTAKE.FIELD.HOSPITAL_ID' | translate }}</label>
          <input
            id="adt-hospital"
            name="hospitalId"
            type="text"
            required
            [(ngModel)]="form.hospitalId"
            [disabled]="!!editingId()"
            data-testid="adt-intake-hospital"
          />
        </div>

        <div class="form-row">
          <label for="adt-provider">{{ 'ADT_INTAKE.FIELD.PROVIDER_ID' | translate }}</label>
          <input
            id="adt-provider"
            name="admittingProviderId"
            type="text"
            required
            [(ngModel)]="form.admittingProviderId"
            data-testid="adt-intake-provider"
          />
        </div>

        <div class="form-row">
          <label for="adt-department">{{ 'ADT_INTAKE.FIELD.DEPARTMENT_ID' | translate }}</label>
          <input
            id="adt-department"
            name="departmentId"
            type="text"
            [(ngModel)]="form.departmentId"
            data-testid="adt-intake-department"
          />
        </div>

        <div class="form-row">
          <label for="adt-assignment">{{ 'ADT_INTAKE.FIELD.ASSIGNMENT_ID' | translate }}</label>
          <input
            id="adt-assignment"
            name="defaultAssignmentId"
            type="text"
            [(ngModel)]="form.defaultAssignmentId"
            data-testid="adt-intake-assignment"
          />
          <small>{{ 'ADT_INTAKE.FIELD.ASSIGNMENT_HINT' | translate }}</small>
        </div>

        <div class="form-row">
          <label for="adt-admission-type">{{
            'ADT_INTAKE.FIELD.ADMISSION_TYPE' | translate
          }}</label>
          <select
            id="adt-admission-type"
            name="defaultAdmissionType"
            [(ngModel)]="form.defaultAdmissionType"
            data-testid="adt-intake-admission-type"
          >
            <option *ngFor="let opt of admissionTypes" [value]="opt">{{ opt }}</option>
          </select>
        </div>

        <div class="form-row">
          <label for="adt-acuity">{{ 'ADT_INTAKE.FIELD.ACUITY' | translate }}</label>
          <select
            id="adt-acuity"
            name="defaultAcuityLevel"
            [(ngModel)]="form.defaultAcuityLevel"
            data-testid="adt-intake-acuity"
          >
            <option *ngFor="let opt of acuityLevels" [value]="opt">{{ opt }}</option>
          </select>
        </div>

        <div class="form-row">
          <label for="adt-encounter-type">{{
            'ADT_INTAKE.FIELD.ENCOUNTER_TYPE' | translate
          }}</label>
          <select
            id="adt-encounter-type"
            name="defaultEncounterType"
            [(ngModel)]="form.defaultEncounterType"
            data-testid="adt-intake-encounter-type"
          >
            <option *ngFor="let opt of encounterTypes" [value]="opt">{{ opt }}</option>
          </select>
        </div>

        <div class="form-row">
          <label for="adt-complaint">{{ 'ADT_INTAKE.FIELD.CHIEF_COMPLAINT' | translate }}</label>
          <input
            id="adt-complaint"
            name="defaultChiefComplaint"
            type="text"
            maxlength="500"
            [(ngModel)]="form.defaultChiefComplaint"
            data-testid="adt-intake-complaint"
          />
        </div>

        <div class="form-row form-row--checkbox">
          <input
            id="adt-enabled"
            name="enabled"
            type="checkbox"
            [(ngModel)]="form.enabled"
            data-testid="adt-intake-enabled"
          />
          <label for="adt-enabled">{{ 'ADT_INTAKE.FIELD.ENABLED' | translate }}</label>
        </div>

        <div class="form-actions">
          <button
            type="submit"
            class="btn-primary"
            [disabled]="saving()"
            data-testid="adt-intake-save"
          >
            {{ saving() ? ('ADT_INTAKE.SAVING' | translate) : ('ADT_INTAKE.SAVE' | translate) }}
          </button>
          <button
            type="button"
            class="btn-secondary"
            (click)="resetForm()"
            data-testid="adt-intake-reset"
          >
            {{ 'COMMON.CANCEL' | translate }}
          </button>
        </div>
      </form>
    </section>
  `,
  styles: [
    `
      .adt-intake {
        padding: 1.5rem;
      }
      .adt-intake__header h1 {
        margin-bottom: 0.25rem;
      }
      .adt-intake__subtitle {
        margin-top: 0;
        color: var(--muted, #64748b);
      }
      .adt-intake__error {
        color: var(--danger, #b00020);
      }
      .adt-intake__pill {
        display: inline-block;
        padding: 0.15rem 0.6rem;
        border-radius: 999px;
        font-size: 0.85em;
      }
      .adt-intake__pill--on {
        background: var(--success-bg, #dcfce7);
        color: var(--success, #166534);
      }
      .adt-intake__pill--off {
        background: var(--muted-bg, #f1f5f9);
        color: var(--muted, #475569);
      }
      .adt-intake__form {
        margin-top: 2rem;
        max-width: 640px;
      }
      .form-row {
        display: flex;
        flex-direction: column;
        margin-bottom: 0.75rem;
      }
      .form-row label {
        font-weight: 500;
        margin-bottom: 0.25rem;
      }
      .form-row small {
        color: var(--muted, #64748b);
        margin-top: 0.25rem;
      }
      .form-row--checkbox {
        flex-direction: row;
        align-items: center;
        gap: 0.5rem;
      }
      .form-row--checkbox label {
        margin: 0;
      }
      .form-actions {
        display: flex;
        gap: 0.5rem;
        margin-top: 1rem;
      }
      .actions {
        display: flex;
        gap: 0.5rem;
      }
    `,
  ],
})
export class AdtIntakeConfigComponent implements OnInit {
  protected readonly admissionTypes = ADMISSION_TYPES;
  protected readonly acuityLevels = ACUITY_LEVELS;
  protected readonly encounterTypes = ENCOUNTER_TYPES;

  protected readonly configs = signal<AdtIntakeConfig[]>([]);
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal(false);
  protected readonly editingId = signal<string | null>(null);

  protected form: IntakeFormState = { ...EMPTY_FORM };

  private readonly service = inject(AdtIntakeConfigService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  ngOnInit(): void {
    this.reload();
  }

  protected trackById(_index: number, cfg: AdtIntakeConfig): string {
    return cfg.id;
  }

  protected edit(cfg: AdtIntakeConfig): void {
    this.editingId.set(cfg.id);
    this.form = {
      hospitalId: cfg.hospitalId,
      admittingProviderId: cfg.admittingProviderId,
      departmentId: cfg.departmentId ?? '',
      defaultAssignmentId: cfg.defaultAssignmentId ?? '',
      defaultAdmissionType: cfg.defaultAdmissionType,
      defaultAcuityLevel: cfg.defaultAcuityLevel,
      defaultEncounterType: cfg.defaultEncounterType,
      defaultChiefComplaint: cfg.defaultChiefComplaint,
      enabled: cfg.enabled,
    };
  }

  protected resetForm(): void {
    this.editingId.set(null);
    this.form = { ...EMPTY_FORM };
  }

  protected save(event: Event): void {
    event.preventDefault();
    if (!this.form.hospitalId || !this.form.admittingProviderId) {
      this.toastError('ADT_INTAKE.VALIDATION.REQUIRED');
      return;
    }
    const request: AdtIntakeConfigRequest = {
      hospitalId: this.form.hospitalId.trim(),
      admittingProviderId: this.form.admittingProviderId.trim(),
      departmentId: this.form.departmentId.trim() || null,
      defaultAssignmentId: this.form.defaultAssignmentId.trim() || null,
      defaultAdmissionType: this.form.defaultAdmissionType,
      defaultAcuityLevel: this.form.defaultAcuityLevel,
      defaultEncounterType: this.form.defaultEncounterType,
      defaultChiefComplaint: this.form.defaultChiefComplaint.trim() || 'Auto-created from ADT^A01',
      enabled: this.form.enabled,
    };

    this.saving.set(true);
    this.service.upsert(request).subscribe({
      next: () => {
        this.saving.set(false);
        this.toastSuccess('ADT_INTAKE.TOAST.SAVED');
        this.resetForm();
        this.reload();
      },
      error: () => {
        this.saving.set(false);
        this.toastError('ADT_INTAKE.TOAST.SAVE_FAILED');
      },
    });
  }

  protected remove(cfg: AdtIntakeConfig): void {
    // ToastService and globalThis.confirm both render their input
    // verbatim; resolve the i18n key via TranslateService first so the
    // admin page stays localizable. Caught on PR #360 Copilot review
    // (High + Medium).
    const message = this.translate.instant('ADT_INTAKE.CONFIRM_DELETE', {
      hospital: cfg.hospitalName || cfg.hospitalId,
    });
    const confirmed = globalThis.confirm(message);
    if (!confirmed) return;
    this.service.remove(cfg.id).subscribe({
      next: () => {
        this.toastSuccess('ADT_INTAKE.TOAST.DELETED');
        if (this.editingId() === cfg.id) this.resetForm();
        this.reload();
      },
      error: () => this.toastError('ADT_INTAKE.TOAST.DELETE_FAILED'),
    });
  }

  private toastSuccess(key: string): void {
    this.toast.success(this.translate.instant(key));
  }

  private toastError(key: string): void {
    this.toast.error(this.translate.instant(key));
  }

  private reload(): void {
    this.loading.set(true);
    this.error.set(false);
    this.service.list().subscribe({
      next: (rows) => {
        this.configs.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.configs.set([]);
        this.loading.set(false);
        this.error.set(true);
      },
    });
  }
}

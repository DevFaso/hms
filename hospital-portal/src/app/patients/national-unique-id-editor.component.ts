import {
  Component,
  EventEmitter,
  Input,
  OnChanges,
  SimpleChanges,
  OnDestroy,
  Output,
  effect,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { PatientService } from '../services/patient.service';
import { RoleContextService } from '../core/role-context.service';
import { PATIENT_IDENTITY_EDIT_ROLES } from './patient-chart/chart-access';
import {
  NationalUniqueIdChange,
  NationalUniqueIdWarning,
  normalizedNationalUniqueId,
} from './national-unique-id';

/** Full identity data lives only in this editor and is dropped on scope/patient changes. */
@Component({
  selector: 'app-national-unique-id-editor',
  standalone: true,
  imports: [FormsModule, RouterLink, TranslateModule],
  template: `
    <div class="form-group">
      <label [for]="inputId">{{ 'NATIONAL_IU.LABEL' | translate }}</label>
      @if (masked) {
        <p>{{ masked }}</p>
      }
      @if (patientId && canEditIdentity()) {
        <button type="button" (click)="reveal()" [disabled]="busy">
          {{ 'NATIONAL_IU.SHOW_FULL' | translate }}
        </button>
      }
      @if (masked && canEditIdentity()) {
        <button
          type="button"
          data-testid="clear-national-unique-id"
          (click)="change('')"
          [disabled]="busy"
        >
          {{ 'NATIONAL_IU.CLEAR' | translate }}
        </button>
      }
      <input
        [id]="inputId"
        type="text"
        inputmode="numeric"
        autocomplete="off"
        [disabled]="!canEditIdentity()"
        [ngModel]="value"
        [ngModelOptions]="{ standalone: true }"
        (ngModelChange)="change($event)"
        [attr.aria-invalid]="errorKey || serverError ? true : null"
        [attr.aria-describedby]="inputId + '-help ' + inputId + '-error'"
      />
      <small [id]="inputId + '-help'">{{ 'NATIONAL_IU.HINT' | translate }}</small>
      <p [id]="inputId + '-error'" role="alert">
        {{ errorKey ? (errorKey | translate) : serverError }}
      </p>
      @if (busy) {
        <p role="status">{{ 'NATIONAL_IU.CHECKING' | translate }}</p>
      }
      @if (warning) {
        <div role="status">
          @for (id of warning.accessiblePatientIds; track id) {
            <a [routerLink]="['/patients', id]">{{ 'NATIONAL_IU.EXISTS_HERE' | translate }}</a>
          }
          @if (warning.registeredElsewhere) {
            <p>{{ 'NATIONAL_IU.EXISTS_ELSEWHERE' | translate }}</p>
          }
          <label>
            <input
              type="checkbox"
              [(ngModel)]="override"
              [ngModelOptions]="{ standalone: true }"
              (ngModelChange)="emit()"
            />{{ 'NATIONAL_IU.SAVE_ANYWAY' | translate }}
          </label>
          <label [for]="inputId + '-reason'">{{ 'NATIONAL_IU.REASON' | translate }}</label>
          <textarea
            [id]="inputId + '-reason'"
            [(ngModel)]="reason"
            [ngModelOptions]="{ standalone: true }"
            maxlength="1000"
            (ngModelChange)="emit()"
          ></textarea>
        </div>
      }
    </div>
  `,
})
export class NationalUniqueIdEditorComponent implements OnChanges, OnDestroy {
  private readonly patients = inject(PatientService);
  private readonly roles = inject(RoleContextService);
  constructor() {
    effect(() => {
      this.roles.effectiveHospitalIdForRequest();
      this.roles.hasAnyActiveRole(PATIENT_IDENTITY_EDIT_ROLES);
      this.reset();
    });
  }
  @Input() patientId?: string;
  @Input() masked?: string | null;
  @Input() scope: string | null = null;
  @Input() inputId = 'nationalUniqueId';
  @Input() serverError?: string | null;
  @Output() updated = new EventEmitter<NationalUniqueIdChange>();
  @Output() ready = new EventEmitter<boolean>();
  value = '';
  reason = '';
  override = false;
  busy = false;
  errorKey = '';
  warning: NationalUniqueIdWarning | null = null;
  private epoch = 0;
  private timer?: ReturnType<typeof setTimeout>;
  private touched = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['patientId'] || changes['masked'] || changes['scope']) this.reset();
    // A concurrent registration can create a duplicate after the preflight check.
    // Recheck that refusal so the warning and explicit override remain available.
    if (changes['serverError']?.currentValue && this.touched && this.value) this.change(this.value);
  }

  canEditIdentity(): boolean {
    return this.roles.hasAnyActiveRole(PATIENT_IDENTITY_EDIT_ROLES);
  }
  ngOnDestroy(): void {
    this.epoch++;
    clearTimeout(this.timer);
    this.value = '';
    this.reason = '';
  }

  private reset(): void {
    this.epoch++;
    clearTimeout(this.timer);
    this.value = '';
    this.reason = '';
    this.override = false;
    this.warning = null;
    this.busy = false;
    this.errorKey = '';
    this.touched = false;
    this.updated.emit({});
    this.ready.emit(true);
  }

  change(value: string): void {
    this.value = value;
    this.touched = true;
    this.reason = '';
    this.override = false;
    this.warning = null;
    this.errorKey = '';
    const epoch = ++this.epoch;
    clearTimeout(this.timer);
    const canonical = normalizedNationalUniqueId(value);
    if (canonical === null) {
      this.busy = false;
      this.errorKey = 'NATIONAL_IU.INVALID';
      this.ready.emit(false);
      return;
    }
    if (!canonical) {
      this.busy = false;
      this.emit();
      return;
    }
    this.busy = true;
    this.ready.emit(false);
    this.timer = setTimeout(
      () =>
        this.patients.checkNationalUniqueId(canonical, this.patientId).subscribe({
          next: (warning) => {
            if (epoch !== this.epoch) return;
            this.busy = false;
            this.value = `${canonical.slice(0, 4)} ${canonical.slice(4, 8)} ${canonical.slice(8)}`;
            this.warning =
              warning.accessiblePatientIds.length || warning.registeredElsewhere ? warning : null;
            this.emit();
          },
          error: () => {
            if (epoch !== this.epoch) return;
            this.busy = false;
            this.errorKey = 'NATIONAL_IU.UNAVAILABLE';
            this.ready.emit(false);
          },
        }),
      250,
    );
  }

  emit(): void {
    const canonical = normalizedNationalUniqueId(this.value);
    const ready =
      !this.busy &&
      !this.errorKey &&
      canonical !== null &&
      (!this.warning || (this.override && this.reason.trim().length > 0));
    this.ready.emit(ready);
    this.updated.emit(
      this.touched && ready
        ? {
            nationalUniqueId: canonical ?? '',
            allowNationalUniqueIdDuplicate: this.override,
            nationalUniqueIdDuplicateReason: this.reason.trim() || undefined,
          }
        : {},
    );
  }

  reveal(): void {
    if (!this.patientId || this.busy || !this.roles.hasAnyActiveRole(PATIENT_IDENTITY_EDIT_ROLES))
      return;
    const epoch = ++this.epoch;
    clearTimeout(this.timer);
    this.busy = true;
    this.patients.revealNationalUniqueId(this.patientId).subscribe({
      next: (response) => {
        if (epoch !== this.epoch) return;
        this.reset();
        this.value = response.nationalUniqueId ?? '';
      },
      error: () => {
        if (epoch !== this.epoch) return;
        this.busy = false;
        this.errorKey = 'NATIONAL_IU.UNAVAILABLE';
      },
    });
  }
}

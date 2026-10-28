import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  OnInit,
  Output,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  PatientDemographicsPatch,
  PatientResponse,
  PatientService,
} from '../../services/patient.service';
import { ToastService } from '../../core/toast.service';
import { DialogFocusDirective } from '../../shared/a11y/dialog-focus.directive';
import { localDateString } from '../../shared/date-utils';

/**
 * QA D926: the front desk corrects a registered patient's identity, contact
 * details and guardian (emergency contact). Only the fields that changed are
 * sent (PATCH /patients/{id}); the server refuses a blank name, sex or phone,
 * and a number or e-mail another record holds.
 */
@Component({
  selector: 'app-patient-demographics-edit',
  standalone: true,
  imports: [FormsModule, TranslateModule, DialogFocusDirective],
  templateUrl: './patient-demographics-edit.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: [
    '../../reception/walkin-dialog/walkin-dialog.component.scss',
    './patient-demographics-edit.component.scss',
  ],
})
export class PatientDemographicsEditComponent implements OnInit {
  @Input({ required: true }) patient!: PatientResponse;
  @Output() dismissed = new EventEmitter<void>();
  @Output() saved = new EventEmitter<PatientResponse>();

  private readonly patientService = inject(PatientService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly saving = signal(false);
  readonly error = signal<string | null>(null);

  /** The editable copy; compared with {@link patient} on save. */
  form: Required<PatientDemographicsPatch> = {
    firstName: '',
    middleName: '',
    lastName: '',
    dateOfBirth: '',
    gender: '',
    phoneNumberPrimary: '',
    phoneNumberSecondary: '',
    email: '',
    addressLine1: '',
    addressLine2: '',
    city: '',
    state: '',
    country: '',
    emergencyContactName: '',
    emergencyContactPhone: '',
    emergencyContactRelationship: '',
  };

  readonly genders = ['MALE', 'FEMALE', 'OTHER'];
  /** The local calendar day (a birth date cannot be later). */
  readonly today = localDateString(new Date());

  ngOnInit(): void {
    for (const key of Object.keys(this.form) as (keyof PatientDemographicsPatch)[]) {
      this.form[key] = this.original(key);
    }
  }

  private original(key: keyof PatientDemographicsPatch): string {
    const p = this.patient as unknown as Record<string, unknown>;
    const raw = p[key];
    // Older records keep the street in `address` only.
    if (key === 'addressLine1' && (raw === undefined || raw === null || raw === '')) {
      return typeof p['address'] === 'string' ? (p['address'] as string) : '';
    }
    return typeof raw === 'string' ? raw : '';
  }

  /** Only what changed travels; an untouched field is left as it is. */
  changes(): PatientDemographicsPatch {
    const out: PatientDemographicsPatch = {};
    for (const key of Object.keys(this.form) as (keyof PatientDemographicsPatch)[]) {
      const now = (this.form[key] ?? '').trim();
      if (now !== this.original(key).trim()) {
        out[key] = now;
      }
    }
    return out;
  }

  submit(): void {
    const required: (keyof PatientDemographicsPatch)[] = [
      'firstName',
      'lastName',
      'gender',
      'phoneNumberPrimary',
    ];
    if (required.some((k) => !(this.form[k] ?? '').trim())) {
      this.error.set(this.translate.instant('PATIENTS.EDIT_REQUIRED_MISSING'));
      return;
    }
    const body = this.changes();
    if (Object.keys(body).length === 0) {
      this.dismissed.emit();
      return;
    }
    this.error.set(null);
    this.saving.set(true);
    this.patientService.patchDemographics(this.patient.id, body).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.toast.success(this.translate.instant('PATIENTS.EDIT_SAVED'));
        this.saved.emit(updated);
      },
      error: (err) => {
        this.saving.set(false);
        this.error.set(err?.error?.message ?? this.translate.instant('PATIENTS.EDIT_FAILED'));
      },
    });
  }
}

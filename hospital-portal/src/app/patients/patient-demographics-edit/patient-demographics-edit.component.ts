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
      return typeof p['address'] === 'string' ? this.streetOf(p['address'] as string) : '';
    }
    return typeof raw === 'string' ? raw : '';
  }

  /**
   * The street of a legacy composed address ("Secteur 15, Ouagadougou,
   * Burkina Faso"): the trailing parts that repeat the record's own city,
   * region, postal code or country are dropped, or an address edit would send
   * them back as the street and the server would append them a second time
   * (review round 8).
   */
  private streetOf(address: string): string {
    const known = [
      this.patient.city,
      this.patient.state,
      this.patient.zipCode,
      this.patient.country,
    ]
      .map((v) => (v ?? '').trim().toLowerCase())
      .filter((v) => v !== '');
    const parts = address.split(',').map((s) => s.trim());
    while (parts.length > 1 && known.includes(parts[parts.length - 1].toLowerCase())) {
      parts.pop();
    }
    return parts.join(', ');
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
    // The server rebuilds the mailing address from its components. A legacy
    // record kept the street in `address` only, so any address change also
    // sends the street shown in the form, or the rebuild would drop it.
    const addressKeys: (keyof PatientDemographicsPatch)[] = [
      'addressLine1',
      'addressLine2',
      'city',
      'state',
      'country',
    ];
    if (!this.patient.addressLine1 && addressKeys.some((k) => k in out)) {
      out.addressLine1 = (this.form.addressLine1 ?? '').trim();
    }
    return out;
  }

  submit(): void {
    // The date of birth is required too (NOT NULL on the record): a cleared
    // one would be sent as "" and kept by the server, while the dialog said
    // "saved" (review round 10).
    const required: (keyof PatientDemographicsPatch)[] = [
      'firstName',
      'lastName',
      'dateOfBirth',
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

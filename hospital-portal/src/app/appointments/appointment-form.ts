import { PersonNamePipe } from '../shared/pipes/person-name.pipe';
import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { of, Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, map, switchMap } from 'rxjs/operators';
import { AppointmentService, AppointmentUpsertRequest } from '../services/appointment.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { StaffService, isClinicalProvider, StaffResponse } from '../services/staff.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { localDateString } from '../shared/date-utils';
import { PatientSublinePipe } from '../shared/patient-picker/patient-subline';
import { comboboxKey } from '../shared/a11y/combobox-keys';
import { labelledRefusalReason } from '../shared/refusal-reason';

/** QA D984: the label each refused field is reported under. */
const APPOINTMENT_FIELD_LABELS: Readonly<Record<string, string>> = {
  appointmentDate: 'APPOINTMENTS.DATE',
  startTime: 'APPOINTMENTS.START_TIME',
  endTime: 'APPOINTMENTS.END_TIME',
  reason: 'APPOINTMENTS.REASON',
  notes: 'APPOINTMENTS.NOTES',
};

interface DeptOption {
  id: string;
  name: string;
}

@Component({
  selector: 'app-appointment-form',
  standalone: true,
  imports: [
    PersonNamePipe,
    PatientSublinePipe,
    FormsModule,
    RouterLink,
    TranslateModule,
    EnumLabelPipe,
  ],
  templateUrl: './appointment-form.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './appointment-form.scss',
})
export class AppointmentFormComponent implements OnInit {
  private readonly appointmentService = inject(AppointmentService);
  private readonly patientService = inject(PatientService);
  private readonly staffService = inject(StaffService);
  private readonly hospitalService = inject(HospitalService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);

  saving = signal(false);

  // ── Patient picker ──────────────────────────────────────
  patientQuery = signal('');
  patientSuggestions = signal<PatientResponse[]>([]);
  patientDropdownOpen = signal(false);
  /** QA D947: the suggestion the arrow keys are on (-1: none). */
  patientActive = signal(-1);
  patientSearchLoading = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  private readonly patientSearch$ = new Subject<string>();

  // ── Staff picker ────────────────────────────────────────
  staffQuery = signal('');
  staffSuggestions = signal<StaffResponse[]>([]);
  staffDropdownOpen = signal(false);
  /** QA D947: the suggestion the arrow keys are on (-1: none). */
  staffActive = signal(-1);
  staffSearchLoading = signal(false);
  selectedStaff = signal<StaffResponse | null>(null);
  private readonly staffSearch$ = new Subject<string>();
  private allStaff: StaffResponse[] = [];
  private staffLoaded = false;

  // ── Hospital + Department dropdowns ─────────────────────
  hospitals = signal<HospitalResponse[]>([]);
  get isReceptionist(): boolean {
    return this.roleContext.isReceptionist();
  }
  get lockedHospital(): HospitalResponse | null {
    const id = this.lockedHospitalId;
    if (!id) return null;
    return this.hospitals().find((h) => h.id === id) ?? null;
  }
  get hospitalOptions(): HospitalResponse[] {
    if (this.isReceptionist) {
      return this.lockedHospital ? [this.lockedHospital] : [];
    }
    return this.hospitals();
  }
  departments = signal<DeptOption[]>([]);
  selectedHospitalId = signal('');

  /** Non-null when the logged-in user is scoped to a specific hospital */
  get lockedHospitalId(): string | null {
    const permitted = this.roleContext.permittedHospitalIds;
    // Only lock when the user belongs to exactly one hospital.
    // Multi-hospital users select their hospital in the dropdown.
    if (permitted.length === 1) return permitted[0];
    return null;
  }

  get lockedHospitalName(): string {
    const id = this.lockedHospitalId;
    if (!id) return '';
    return this.hospitals().find((h) => h.id === id)?.name ?? id;
  }

  form: AppointmentUpsertRequest = {
    appointmentDate: '',
    startTime: '',
    endTime: '',
    reason: '',
    notes: '',
  };

  ngOnInit(): void {
    const lockedId = this.lockedHospitalId;

    if (lockedId) {
      // Scoped user (all non-admin staff, single-hospital admins):
      // locked to one hospital — never show a dropdown.
      this.hospitalService.getMyHospitalAsResponse().subscribe((h) => {
        this.hospitals.set([h]);
        this.selectedHospitalId.set(lockedId);
        this.form.hospitalId = lockedId;
        // Ensure the interceptor sends the correct X-Hospital-Id for this user.
        this.roleContext.activeHospitalId = lockedId;
        this.loadDepartmentsFor(lockedId);
      });

      // Load staff for the locked hospital once.
      // Bookable providers only (QA D56/D122): never an admin, a receptionist
      // or an account whose only role here is ROLE_PATIENT.
      this.staffService.list(lockedId).subscribe((list) => {
        this.allStaff = list.filter(isClinicalProvider);
        this.staffLoaded = true;
        this.setDeptsFromStaff(lockedId);
        this.applyPendingStaff();
      });
    } else {
      // Multi-hospital admin: load all hospitals for the dropdown.
      this.hospitalService.list().subscribe((h) => {
        this.hospitals.set(h);
      });
    }

    this.initPatientSearch();
    this.initStaffSearch();
    this.applyPrefill();
  }

  /**
   * QA D810: the prenatal calendar's "Book" action opens this form with the
   * patient, the recommended day, a suggested time and an antenatal reason
   * filled in. Each value is only a suggestion the user can change; a
   * malformed one is ignored, and a day in the past is not offered.
   */
  private applyPrefill(): void {
    const params = this.route.snapshot.queryParamMap;
    const date = params.get('date');
    if (date && /^\d{4}-\d{2}-\d{2}$/.test(date) && date >= localDateString()) {
      this.form.appointmentDate = date;
    }
    const time = params.get('time');
    if (time && /^\d{2}:\d{2}$/.test(time)) {
      this.form.startTime = time;
      this.onStartTimeChange();
      // QA D1055: the recommended visit length (a 45-minute first antenatal
      // visit), not the 30-minute default.
      const duration = Number(params.get('duration'));
      if (Number.isInteger(duration) && duration >= 5 && duration <= 240) {
        const [h, m] = time.split(':').map(Number);
        const end = h * 60 + m + duration;
        if (end < 24 * 60) {
          this.form.endTime = `${String(Math.floor(end / 60)).padStart(2, '0')}:${String(end % 60).padStart(2, '0')}`;
        }
      }
    }
    // QA D1055: the provider (and so the department) the calendar was planned with.
    const staffId = params.get('staffId');
    if (staffId && /^[0-9a-f-]{36}$/i.test(staffId)) {
      this.pendingStaffId = staffId;
      this.applyPendingStaff();
    }
    const reason = params.get('reason');
    if (reason) {
      this.form.reason = reason.trim().slice(0, 255);
    }
    // QA D985: booked from the recall list, the booking schedules that recall.
    const recallId = params.get('recallId');
    if (recallId && /^[0-9a-f-]{36}$/i.test(recallId)) {
      this.form.recallId = recallId;
    }
    const patientId = params.get('patientId');
    if (patientId && /^[0-9a-f-]{36}$/i.test(patientId)) {
      this.patientService.getById(patientId).subscribe({
        next: (patient) => this.selectPatient(patient),
        error: () => undefined,
      });
    }
  }

  // ── Patient search ──────────────────────────────────────
  private initPatientSearch(): void {
    this.patientSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((q) => {
          this.patientSearchLoading.set(true);
          return this.patientService.list(this.patientSearchHospitalId(), q || undefined);
        }),
      )
      .subscribe({
        next: (list) => {
          this.patientSuggestions.set(list.slice(0, 8));
          // QA D947: a new list has no active option; Tab must not pick whoever
          // now sits where the old highlight was (review round 11).
          this.patientActive.set(-1);
          this.patientDropdownOpen.set(list.length > 0);
          this.patientSearchLoading.set(false);
        },
        error: () => this.patientSearchLoading.set(false),
      });
  }

  onPatientQueryChange(q: string): void {
    this.patientQuery.set(q);
    if (q.length >= 1) this.patientSearch$.next(q);
    else {
      this.patientSuggestions.set([]);
      // QA D947: a new list has no active option; Tab must not pick whoever
      // now sits where the old highlight was (review round 11).
      this.patientActive.set(-1);
      this.patientDropdownOpen.set(false);
    }
  }

  /** QA D947: ArrowDown/ArrowUp/Enter/Tab/Escape on the patient picker. */
  onPatientKeydown(event: KeyboardEvent): void {
    const options = this.patientDropdownOpen() ? this.patientSuggestions() : [];
    const step = comboboxKey(event, options.length, this.patientActive());
    if (step.pick) {
      this.selectPatient(options[step.active]);
    } else if (step.close) {
      this.patientDropdownOpen.set(false);
    }
    this.patientActive.set(step.close ? -1 : step.active);
  }

  /** QA D947: the same keyboard on the staff picker. */
  onStaffKeydown(event: KeyboardEvent): void {
    const options = this.staffDropdownOpen() ? this.staffSuggestions() : [];
    const step = comboboxKey(event, options.length, this.staffActive());
    if (step.pick) {
      this.selectStaff(options[step.active]);
    } else if (step.close) {
      this.staffDropdownOpen.set(false);
    }
    this.staffActive.set(step.close ? -1 : step.active);
  }

  selectPatient(p: PatientResponse): void {
    // QA D985 (review round 14): picking another patient drops the recall the
    // form was opened for, as clearing the patient does; it was theirs.
    if (this.form.patientId && this.form.patientId !== p.id) {
      delete this.form.recallId;
    }
    this.selectedPatient.set(p);
    this.form.patientId = p.id;
    delete this.form.patientEmail;
    this.patientDropdownOpen.set(false);
    this.patientQuery.set('');
  }

  clearPatient(): void {
    this.selectedPatient.set(null);
    delete this.form.patientId;
    // The recall belonged to the patient just cleared (QA D985).
    delete this.form.recallId;
    this.patientQuery.set('');
  }

  /**
   * QA D912 (#967 review): the hospital whose patients, and whose MRN, the
   * typeahead offers: the form's hospital, else the request scope, which for
   * a super-admin is the hospital the scope chip picked, not their home one.
   */
  private patientSearchHospitalId(): string | undefined {
    return this.form.hospitalId || this.roleContext.effectiveHospitalIdForRequest() || undefined;
  }

  patientInitials(p: PatientResponse): string {
    return ((p.firstName?.[0] ?? '') + (p.lastName?.[0] ?? '')).toUpperCase() || '?';
  }

  // ── Staff search ────────────────────────────────────────
  private initStaffSearch(): void {
    this.staffSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((_q) => {
          this.staffSearchLoading.set(true);
          if (!this.staffLoaded) {
            // Single-hospital users: ngOnInit exclusively owns the staff API call.
            // Never fire a second concurrent request from the search path.
            if (this.lockedHospitalId) {
              return of(this.allStaff);
            }
            // Unrestricted / multi-hospital user only — safe to fetch staff here.
            return this.staffService.list().pipe(map((list) => list.filter(isClinicalProvider)));
          }
          return of(this.allStaff);
        }),
      )
      .subscribe({
        next: (list) => {
          if (!this.staffLoaded) {
            this.allStaff = list;
            this.staffLoaded = true;
          }
          const q = this.staffQuery().toLowerCase();
          const filtered = q
            ? this.allStaff.filter(
                (s) =>
                  s.name?.toLowerCase().includes(q) ||
                  s.jobTitle?.toLowerCase().includes(q) ||
                  s.email?.toLowerCase().includes(q) ||
                  s.hospitalName?.toLowerCase().includes(q),
              )
            : this.allStaff;
          this.staffSuggestions.set(filtered.slice(0, 8));
          // QA D947: a new list has no active option; Tab must not pick whoever
          // now sits where the old highlight was (review round 11).
          this.staffActive.set(-1);
          this.staffDropdownOpen.set(filtered.length > 0);
          this.staffSearchLoading.set(false);
        },
        error: () => this.staffSearchLoading.set(false),
      });
  }

  onStaffQueryChange(q: string): void {
    this.staffQuery.set(q);
    if (q.length >= 1) this.staffSearch$.next(q);
    else {
      this.staffSuggestions.set([]);
      // QA D947: a new list has no active option; Tab must not pick whoever
      // now sits where the old highlight was (review round 11).
      this.staffActive.set(-1);
      this.staffDropdownOpen.set(false);
    }
  }

  /** QA D1055: a provider asked for by the link, applied once the staff list is in. */
  private pendingStaffId: string | null = null;

  private applyPendingStaff(): void {
    if (!this.pendingStaffId || !this.staffLoaded) return;
    const staff = this.allStaff.find((s) => s.id === this.pendingStaffId);
    this.pendingStaffId = null;
    if (!staff) return;
    this.selectStaff(staff);
    if (staff.departmentId && !this.form.departmentId) {
      this.form.departmentId = staff.departmentId;
    }
  }

  selectStaff(s: StaffResponse): void {
    // Boundary guard: reject staff not belonging to the currently selected hospital.
    // For single-hospital users, lockedHospitalId is non-null.
    // For multi-hospital users, check against the form's selected hospital.
    const activeHospital = this.form.hospitalId ?? this.lockedHospitalId;
    if (activeHospital && s.hospitalId && s.hospitalId !== activeHospital) {
      this.toast.error(this.translate.instant('APPOINTMENTS.TOAST.STAFF_OTHER_HOSPITAL'));
      return;
    }

    this.selectedStaff.set(s);
    this.form.staffId = s.id;
    delete this.form.staffEmail;
    this.staffDropdownOpen.set(false);
    this.staffQuery.set('');
    // Auto-fill hospital when staff is selected (unrestricted users only —
    // locked users already have selectedHospitalId set from ngOnInit)
    if (s.hospitalId && !this.selectedHospitalId()) {
      this.selectedHospitalId.set(s.hospitalId);
      this.form.hospitalId = s.hospitalId;
      this.loadDepartmentsFor(s.hospitalId);
    }
  }

  clearStaff(): void {
    this.selectedStaff.set(null);
    delete this.form.staffId;
    this.staffQuery.set('');
  }

  staffInitials(s: StaffResponse): string {
    const parts = (s.name || '').trim().split(' ');
    return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase() || '?';
  }

  // ── Hospital + Department ───────────────────────────────
  onHospitalChange(hospitalId: string): void {
    this.selectedHospitalId.set(hospitalId);
    this.form.hospitalId = hospitalId || undefined;
    this.form.departmentId = undefined;
    this.departments.set([]);
    // Update active hospital so the auth interceptor sends the correct
    // X-Hospital-Id header on subsequent API calls (POST /api/appointments, etc.)
    this.roleContext.activeHospitalId = hospitalId || null;
    if (hospitalId) this.loadDepartmentsFor(hospitalId);
  }

  private loadDepartmentsFor(hospitalId: string): void {
    // FIX 3 — boundary guard: a locked user can never load departments for a
    // foreign hospital regardless of which code path calls this method.
    const locked = this.lockedHospitalId;
    if (locked && hospitalId !== locked) {
      return;
    }

    // Departments come from staff already loaded — derive unique dept list
    if (this.staffLoaded) {
      this.setDeptsFromStaff(hospitalId);
    } else if (this.lockedHospitalId) {
      // Staff for the single locked hospital is being loaded in ngOnInit — wait for it
      // (setDeptsFromStaff will be called once staffLoaded is set)
    } else {
      // Unrestricted / multi-hospital user selected a hospital: fetch staff for it
      this.staffService.list(hospitalId).subscribe((list) => {
        if (!this.staffLoaded) {
          this.allStaff = [...this.allStaff, ...list.filter(isClinicalProvider)];
        }
        this.setDeptsFromStaff(hospitalId);
      });
    }
  }

  private setDeptsFromStaff(hospitalId: string): void {
    const seen = new Map<string, string>();
    this.allStaff
      .filter((s) => s.hospitalId === hospitalId && s.departmentId)
      .forEach((s) => {
        if (s.departmentId && !seen.has(s.departmentId)) {
          seen.set(s.departmentId, s.departmentName || s.departmentId);
        }
      });
    this.departments.set(Array.from(seen.entries()).map(([id, name]) => ({ id, name })));
  }

  hospitalNameById(id: string): string {
    return this.hospitals().find((h) => h.id === id)?.name ?? id;
  }

  // ── Time validation ────────────────────────────────────
  timeError = signal<string | null>(null);

  onStartTimeChange(): void {
    const start = this.form.startTime;
    if (!start) return;
    this.timeError.set(null);
    const [h, m] = start.split(':').map(Number);
    const totalMin = h * 60 + m + 30;
    const endH = Math.floor(totalMin / 60) % 24;
    const endM = totalMin % 60;
    const autoEnd = `${String(endH).padStart(2, '0')}:${String(endM).padStart(2, '0')}`;
    if (!this.form.endTime || this.form.endTime <= start) {
      this.form.endTime = autoEnd;
    }
  }

  onEndTimeChange(): void {
    if (this.form.startTime && this.form.endTime) {
      if (this.form.endTime <= this.form.startTime) {
        this.timeError.set(this.translate.instant('APPOINTMENTS.END_TIME_AFTER_START'));
      } else {
        this.timeError.set(null);
      }
    }
  }

  private validateTimes(): boolean {
    if (!this.form.startTime || !this.form.endTime) return true;
    if (this.form.endTime <= this.form.startTime) {
      const msg = this.translate.instant('APPOINTMENTS.END_TIME_AFTER_START');
      this.timeError.set(msg);
      this.toast.error(msg);
      return false;
    }
    this.timeError.set(null);
    return true;
  }

  // ── Submit ───────────────────────────────────────────────
  submit(): void {
    if (!this.form.appointmentDate || !this.form.startTime) {
      this.toast.error(this.translate.instant('APPOINTMENTS.TOAST.DATE_TIME_REQUIRED'));
      return;
    }
    if (this.form.endTime && !this.validateTimes()) return;
    if (!this.form.patientId) {
      this.toast.error(this.translate.instant('APPOINTMENTS.TOAST.PATIENT_REQUIRED'));
      return;
    }
    if (!this.form.staffId) {
      this.toast.error(this.translate.instant('APPOINTMENTS.TOAST.STAFF_REQUIRED'));
      return;
    }

    this.saving.set(true);

    const payload: AppointmentUpsertRequest = { ...this.form };
    for (const key of Object.keys(payload) as (keyof AppointmentUpsertRequest)[]) {
      if (payload[key] === '' || payload[key] === undefined) {
        delete payload[key];
      }
    }

    this.appointmentService.create(payload).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('APPOINTMENTS.TOAST.CREATED'));
        this.router.navigate(['/appointments']);
      },
      error: (err) => {
        // QA D985: a recall the booking cannot answer (closed meanwhile, another
        // patient's) rolls the booking back; drop it so the next submit books
        // the visit without it, as the refusal says.
        if ((err as { error?: { field?: unknown } } | null)?.error?.field === 'recallId') {
          delete this.form.recallId;
        }
        // QA D984: a validation refusal names the field and its reason
        // ("Date : must be today or later"), not the generic text.
        this.toast.error(
          labelledRefusalReason(err, this.translate, APPOINTMENT_FIELD_LABELS) ??
            this.translate.instant('APPOINTMENTS.TOAST.CREATE_FAILED'),
        );
        this.saving.set(false);
      },
    });
  }
}

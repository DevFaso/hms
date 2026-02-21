import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { AppointmentService, AppointmentUpsertRequest } from '../services/appointment.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { ToastService } from '../core/toast.service';

interface DeptOption {
  id: string;
  name: string;
}

@Component({
  selector: 'app-appointment-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './appointment-form.html',
  styleUrl: './appointment-form.scss',
})
export class AppointmentFormComponent implements OnInit {
  private readonly appointmentService = inject(AppointmentService);
  private readonly patientService = inject(PatientService);
  private readonly staffService = inject(StaffService);
  private readonly hospitalService = inject(HospitalService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  saving = signal(false);

  // ── Patient picker ──────────────────────────────────────
  patientQuery = signal('');
  patientSuggestions = signal<PatientResponse[]>([]);
  patientDropdownOpen = signal(false);
  patientSearchLoading = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  private readonly patientSearch$ = new Subject<string>();

  // ── Staff picker ────────────────────────────────────────
  staffQuery = signal('');
  staffSuggestions = signal<StaffResponse[]>([]);
  staffDropdownOpen = signal(false);
  staffSearchLoading = signal(false);
  selectedStaff = signal<StaffResponse | null>(null);
  private readonly staffSearch$ = new Subject<string>();
  private allStaff: StaffResponse[] = [];
  private staffLoaded = false;

  // ── Hospital + Department dropdowns ─────────────────────
  hospitals = signal<HospitalResponse[]>([]);
  departments = signal<DeptOption[]>([]);
  selectedHospitalId = signal('');

  form: AppointmentUpsertRequest = {
    appointmentDate: '',
    startTime: '',
    endTime: '',
    status: 'SCHEDULED',
    reason: '',
    notes: '',
  };

  ngOnInit(): void {
    this.hospitalService.list().subscribe((h) => this.hospitals.set(h));
    this.initPatientSearch();
    this.initStaffSearch();
  }

  // ── Patient search ──────────────────────────────────────
  private initPatientSearch(): void {
    this.patientSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((q) => {
          this.patientSearchLoading.set(true);
          return this.patientService.list(undefined, q || undefined);
        }),
      )
      .subscribe({
        next: (list) => {
          this.patientSuggestions.set(list.slice(0, 8));
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
      this.patientDropdownOpen.set(false);
    }
  }

  selectPatient(p: PatientResponse): void {
    this.selectedPatient.set(p);
    this.form.patientId = p.id;
    delete this.form.patientEmail;
    this.patientDropdownOpen.set(false);
    this.patientQuery.set('');
  }

  clearPatient(): void {
    this.selectedPatient.set(null);
    delete this.form.patientId;
    this.patientQuery.set('');
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
          if (!this.staffLoaded) return this.staffService.list();
          return [this.allStaff];
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
      this.staffDropdownOpen.set(false);
    }
  }

  selectStaff(s: StaffResponse): void {
    this.selectedStaff.set(s);
    this.form.staffId = s.id;
    delete this.form.staffEmail;
    this.staffDropdownOpen.set(false);
    this.staffQuery.set('');
    // Auto-fill hospital when staff is selected
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
    if (hospitalId) this.loadDepartmentsFor(hospitalId);
  }

  private loadDepartmentsFor(hospitalId: string): void {
    // Departments come from staff already loaded — derive unique dept list
    if (this.staffLoaded) {
      this.setDeptsFromStaff(hospitalId);
    } else {
      this.staffService.list(hospitalId).subscribe((list) => {
        if (!this.staffLoaded) {
          this.allStaff = [...this.allStaff, ...list];
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
        this.timeError.set('End time must be after start time');
      } else {
        this.timeError.set(null);
      }
    }
  }

  private validateTimes(): boolean {
    if (!this.form.startTime || !this.form.endTime) return true;
    if (this.form.endTime <= this.form.startTime) {
      this.timeError.set('End time must be after start time');
      this.toast.error('End time must be after start time');
      return false;
    }
    this.timeError.set(null);
    return true;
  }

  // ── Submit ───────────────────────────────────────────────
  submit(): void {
    if (!this.form.appointmentDate || !this.form.startTime || !this.form.endTime) {
      this.toast.error('Date, start time, and end time are required');
      return;
    }
    if (!this.validateTimes()) return;
    if (!this.form.patientId) {
      this.toast.error('Please select a patient');
      return;
    }
    if (!this.form.staffId) {
      this.toast.error('Please select a doctor / staff member');
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
        this.toast.success('Appointment created successfully');
        this.router.navigate(['/appointments']);
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to create appointment');
        this.saving.set(false);
      },
    });
  }
}

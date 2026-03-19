import { Component, Output, EventEmitter, inject, signal, OnInit, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { debounceTime, distinctUntilChanged, Subject, switchMap, catchError, of } from 'rxjs';
import { PatientService, PatientResponse } from '../services/patient.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

type EncounterType = 'CONSULTATION' | 'FOLLOW_UP' | 'EMERGENCY' | 'OUTPATIENT' | 'INPATIENT';

@Component({
  selector: 'app-walkin-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './walkin-dialog.html',
})
export class WalkInDialogComponent implements OnInit {
  @Output() dismissed = new EventEmitter<void>();
  @Output() created = new EventEmitter<void>();

  private readonly http = inject(HttpClient);
  private readonly patientService = inject(PatientService);
  private readonly staffService = inject(StaffService);
  private readonly roleCtx = inject(RoleContextService);
  private readonly toast = inject(ToastService);

  /* ── Patient search ─────────────────────── */
  patientQuery = signal('');
  patientResults = signal<PatientResponse[]>([]);
  selectedPatient = signal<PatientResponse | null>(null);
  searchingPatients = signal(false);
  private patientSearch$ = new Subject<string>();

  /* ── Staff list ─────────────────────────── */
  allStaff = signal<StaffResponse[]>([]);
  staffQuery = signal('');
  filteredStaff = computed(() => {
    const q = this.staffQuery().toLowerCase();
    return q ? this.allStaff().filter((s) => s.name.toLowerCase().includes(q)) : this.allStaff();
  });
  selectedStaff = signal<StaffResponse | null>(null);

  /* ── Form ───────────────────────────────── */
  encounterType = signal<EncounterType>('OUTPATIENT');
  notes = signal('');
  saving = signal(false);

  readonly encounterTypes: EncounterType[] = [
    'OUTPATIENT',
    'CONSULTATION',
    'FOLLOW_UP',
    'EMERGENCY',
    'INPATIENT',
  ];

  ngOnInit(): void {
    const hospitalId = this.roleCtx.activeHospitalId ?? undefined;
    this.staffService.list(hospitalId).subscribe({
      next: (list) => this.allStaff.set(list),
    });

    this.patientSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          if (!q || q.length < 2) return of([]);
          this.searchingPatients.set(true);
          return this.patientService.list(hospitalId, q).pipe(catchError(() => of([])));
        }),
      )
      .subscribe((results) => {
        this.patientResults.set(results as PatientResponse[]);
        this.searchingPatients.set(false);
      });
  }

  onPatientQueryChange(value: string): void {
    this.patientQuery.set(value);
    this.selectedPatient.set(null);
    this.patientSearch$.next(value);
  }

  selectPatient(p: PatientResponse): void {
    this.selectedPatient.set(p);
    this.patientQuery.set(`${p.firstName} ${p.lastName}`);
    this.patientResults.set([]);
  }

  selectStaff(s: StaffResponse): void {
    this.selectedStaff.set(s);
    this.staffQuery.set(s.name);
  }

  submit(): void {
    const patient = this.selectedPatient();
    const staff = this.selectedStaff();
    const hospitalId = this.roleCtx.activeHospitalId;

    if (!patient || !staff || !hospitalId) {
      this.toast.error('Please select a patient, provider, and ensure a hospital is active');
      return;
    }

    this.saving.set(true);
    const body = {
      patientId: patient.id,
      staffId: staff.id,
      hospitalId,
      encounterType: this.encounterType(),
      encounterDate: new Date().toISOString(),
      notes: this.notes() || undefined,
      status: 'ARRIVED',
    };

    this.http.post('/encounters', body).subscribe({
      next: () => {
        this.saving.set(false);
        this.created.emit();
      },
      error: (err) => {
        this.saving.set(false);
        const msg = err?.error?.message ?? 'Failed to create walk-in encounter';
        this.toast.error(msg);
      },
    });
  }
}

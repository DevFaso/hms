import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subject, catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';

import {
  CareProgram,
  EnrollRequest,
  ProgramEnrollment,
  ProgramEnrollmentStatus,
  ProgramRegistryService,
} from '../services/program-registry.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

type StatusFilter = 'ACTIVE' | ProgramEnrollmentStatus;

/**
 * Disease-programme registries (Tier 2 item 35).
 *
 * <p>One programme at a time, overdue-first — the server orders the list so
 * the top row is the patient most in need of tracing. Enrolment, visit
 * recording and status outcomes all happen here; outreach to the overdue is
 * item 36, deliberately not this screen.
 */
@Component({
  selector: 'app-registries',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './registries.html',
  styleUrl: './registries.scss',
})
export class RegistriesComponent implements OnInit {
  private readonly registryService = inject(ProgramRegistryService);
  private readonly patientService = inject(PatientService);
  private readonly roleCtx = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly programs: CareProgram[] = ['HIV', 'TB', 'MALARIA', 'HYPERTENSION', 'DIABETES', 'ANC'];
  readonly statuses: ProgramEnrollmentStatus[] = [
    'ACTIVE',
    'COMPLETED',
    'TRANSFERRED_OUT',
    'LOST_TO_FOLLOW_UP',
    'WITHDRAWN',
    'DECEASED',
  ];

  activeProgram = signal<CareProgram>('HIV');
  statusFilter = signal<StatusFilter>('ACTIVE');

  rows = signal<ProgramEnrollment[]>([]);
  counts = signal<Partial<Record<ProgramEnrollmentStatus, number>>>({});
  loading = signal(false);
  loadFailed = signal(false);
  actingOnId = signal<string | null>(null);

  overdueCount = computed(() => this.rows().filter((r) => r.overdueDays > 0).length);

  /* ── Enrol modal ── */
  showEnrollForm = signal(false);
  saving = signal(false);
  patientQuery = signal('');
  patientResults = signal<PatientResponse[]>([]);
  selectedPatient = signal<PatientResponse | null>(null);
  private readonly patientSearch$ = new Subject<string>();

  enrolledOn = signal('');
  cadenceDays = signal<number | null>(null);
  notes = signal('');

  /* ── Status modal ── */
  statusTarget = signal<ProgramEnrollment | null>(null);
  newStatus = signal<ProgramEnrollmentStatus>('COMPLETED');
  statusReason = signal('');

  ngOnInit(): void {
    const hospitalId = this.roleCtx.activeHospitalId ?? undefined;
    this.patientSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          if (!q || q.length < 2) return of([]);
          return this.patientService.list(hospitalId, q).pipe(catchError(() => of([])));
        }),
      )
      .subscribe((results) => this.patientResults.set(results as PatientResponse[]));

    this.load();
  }

  setProgram(program: CareProgram): void {
    if (this.activeProgram() === program) return;
    this.activeProgram.set(program);
    this.load();
  }

  setStatusFilter(status: StatusFilter): void {
    if (this.statusFilter() === status) return;
    this.statusFilter.set(status);
    this.load();
  }

  load(): void {
    const program = this.activeProgram();
    this.loading.set(true);
    this.loadFailed.set(false);
    this.registryService.registry(program, this.statusFilter()).subscribe({
      next: (list) => {
        this.rows.set(list);
        this.loading.set(false);
      },
      error: () => {
        // An explicit failure state, never an empty registry: "nobody is
        // enrolled" and "we could not load the registry" are opposite
        // statements to a programme coordinator.
        this.rows.set([]);
        this.loading.set(false);
        this.loadFailed.set(true);
      },
    });
    this.registryService.counts(program).subscribe({
      next: (c) => this.counts.set(c),
      error: () => this.counts.set({}),
    });
  }

  countFor(status: ProgramEnrollmentStatus): number {
    return this.counts()[status] ?? 0;
  }

  /* ── Enrol ── */

  openEnrollForm(): void {
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    this.patientResults.set([]);
    this.enrolledOn.set('');
    // No prefilled cadence: it is clinical protocol the clinician knows and
    // the server refuses to guess. An empty box asks; a silent 30 asserts.
    this.cadenceDays.set(null);
    this.notes.set('');
    this.showEnrollForm.set(true);
  }

  closeEnrollForm(): void {
    this.showEnrollForm.set(false);
  }

  onPatientQueryChange(value: string): void {
    this.patientQuery.set(value);
    this.patientSearch$.next(value);
  }

  selectPatient(p: PatientResponse): void {
    this.selectedPatient.set(p);
    this.patientQuery.set(p.firstName + ' ' + p.lastName);
    this.patientResults.set([]);
  }

  submitEnroll(): void {
    const patient = this.selectedPatient();
    const cadence = this.cadenceDays();
    if (!patient || !cadence || cadence < 1 || cadence > 365) {
      this.toast.error(this.translate.instant('REGISTRIES.ENROLL_FIELDS_REQUIRED'));
      return;
    }
    if (this.saving()) return;
    const req: EnrollRequest = {
      program: this.activeProgram(),
      visitCadenceDays: cadence,
    };
    if (this.enrolledOn()) req.enrolledOn = this.enrolledOn();
    const trimmedNotes = this.notes().trim();
    if (trimmedNotes) req.notes = trimmedNotes;

    this.saving.set(true);
    this.registryService.enroll(patient.id, req).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('REGISTRIES.ENROLLED'));
        this.saving.set(false);
        this.showEnrollForm.set(false);
        this.load();
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(
          this.extractMessage(err) ?? this.translate.instant('REGISTRIES.ENROLL_FAILED'),
        );
      },
    });
  }

  /* ── Visits ── */

  recordVisit(row: ProgramEnrollment): void {
    if (this.actingOnId()) return;
    this.actingOnId.set(row.id);
    this.registryService.recordVisit(row.patientId, row.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('REGISTRIES.VISIT_RECORDED'));
        this.actingOnId.set(null);
        this.load();
      },
      error: (err) => {
        this.actingOnId.set(null);
        this.toast.error(
          this.extractMessage(err) ?? this.translate.instant('REGISTRIES.ACTION_FAILED'),
        );
      },
    });
  }

  /* ── Status ── */

  openStatusModal(row: ProgramEnrollment): void {
    this.statusTarget.set(row);
    this.newStatus.set(row.status === 'ACTIVE' ? 'COMPLETED' : 'ACTIVE');
    this.statusReason.set('');
  }

  closeStatusModal(): void {
    this.statusTarget.set(null);
  }

  submitStatus(): void {
    const target = this.statusTarget();
    if (!target || this.saving()) return;
    const status = this.newStatus();
    const reason = this.statusReason().trim();
    if (status !== 'ACTIVE' && !reason) {
      this.toast.error(this.translate.instant('REGISTRIES.REASON_REQUIRED'));
      return;
    }
    this.saving.set(true);
    this.registryService
      .updateStatus(target.patientId, target.id, {
        status,
        ...(status !== 'ACTIVE' ? { reason } : {}),
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('REGISTRIES.STATUS_UPDATED'));
          this.saving.set(false);
          this.statusTarget.set(null);
          this.load();
        },
        error: (err) => {
          this.saving.set(false);
          this.toast.error(
            this.extractMessage(err) ?? this.translate.instant('REGISTRIES.ACTION_FAILED'),
          );
        },
      });
  }

  statusClass(status: ProgramEnrollmentStatus): string {
    return 'reg-status-' + status.toLowerCase();
  }

  private extractMessage(err: unknown): string | null {
    if (!err || typeof err !== 'object') return null;
    const body = (err as { error?: { message?: string } }).error;
    return body?.message ?? null;
  }
}

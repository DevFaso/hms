import {
  Component,
  ElementRef,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subject, Subscription, forkJoin, of, switchMap } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

import {
  CareProgram,
  EnrollRequest,
  ProgramEnrollment,
  ProgramEnrollmentStatus,
  ProgramRegistryPage,
  ProgramRegistryService,
} from '../services/program-registry.service';
import { PatientResponse } from '../services/patient.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

type StatusFilter = 'ACTIVE' | ProgramEnrollmentStatus;

interface RegistryLoad {
  rows: ProgramEnrollment[];
  totalRows: number;
  rowsFailed: boolean;
  counts: Partial<Record<ProgramEnrollmentStatus, number>>;
  countsFailed: boolean;
}

/**
 * Disease-programme registries (Tier 2 item 35).
 *
 * <p>One programme at a time, overdue-first — the server orders the page so
 * the top row is the patient most in need of tracing. Enrolment, visit
 * recording and status outcomes all happen here; outreach to the overdue is
 * item 36, deliberately not this screen.
 *
 * <p>Loads run through a single switchMap so a slow response for a
 * previously selected programme can never overwrite the current one, and a
 * failed counts request renders as "unavailable", never as zeros — an
 * outage must not read as an empty cohort.
 */
@Component({
  selector: 'app-registries',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, PatientPickerComponent],
  templateUrl: './registries.html',
  styleUrl: './registries.scss',
})
export class RegistriesComponent implements OnInit, OnDestroy {
  private readonly registryService = inject(ProgramRegistryService);
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

  /** One page is plenty for a facility cohort; the server caps at 200 anyway. */
  private static readonly PAGE_SIZE = 200;

  activeProgram = signal<CareProgram>('HIV');
  statusFilter = signal<StatusFilter>('ACTIVE');

  rows = signal<ProgramEnrollment[]>([]);
  totalRows = signal(0);
  counts = signal<Partial<Record<ProgramEnrollmentStatus, number>>>({});
  countsFailed = signal(false);
  loading = signal(false);
  loadFailed = signal(false);
  actingOnId = signal<string | null>(null);

  overdueCount = computed(() => this.rows().filter((r) => r.overdueDays > 0).length);
  truncated = computed(() => this.totalRows() > this.rows().length);

  /** Hospital scope for the picker — the super-admin-aware one, not the primary. */
  readonly pickerHospitalId = computed(() => this.roleCtx.effectiveHospitalIdForRequest());

  private readonly load$ = new Subject<{ program: CareProgram; status: StatusFilter }>();
  private loadSub?: Subscription;

  /* ── Enrol modal ── */
  showEnrollForm = signal(false);
  saving = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  enrolledOn = signal('');
  cadenceDays = signal<number | null>(null);
  notes = signal('');

  /* ── Status modal ── */
  statusTarget = signal<ProgramEnrollment | null>(null);
  newStatus = signal<ProgramEnrollmentStatus>('COMPLETED');
  statusReason = signal('');

  /* ── Dialog focus management ── */
  private readonly enrollDialog = viewChild<ElementRef<HTMLElement>>('enrollDialog');
  private readonly statusDialog = viewChild<ElementRef<HTMLElement>>('statusDialog');
  private dialogOpener: HTMLElement | null = null;

  ngOnInit(): void {
    this.loadSub = this.load$
      .pipe(
        // switchMap: only the LATEST selection may update the view. Without
        // it, a slow HIV response finishing after a quick TB one would
        // display HIV rows under the TB tab.
        switchMap(({ program, status }) => {
          this.loading.set(true);
          this.loadFailed.set(false);
          const page = this.registryService
            .registry(program, status, 0, RegistriesComponent.PAGE_SIZE)
            .pipe(
              map((p: ProgramRegistryPage) => ({
                rows: p.content,
                totalRows: p.totalElements,
                rowsFailed: false,
              })),
              catchError(() => of({ rows: [], totalRows: 0, rowsFailed: true })),
            );
          const counts = this.registryService.counts(program).pipe(
            map((c) => ({ counts: c, countsFailed: false })),
            catchError(() => of({ counts: {}, countsFailed: true })),
          );
          return forkJoin([page, counts]).pipe(map(([p, c]): RegistryLoad => ({ ...p, ...c })));
        }),
      )
      .subscribe((result) => {
        this.rows.set(result.rows);
        this.totalRows.set(result.totalRows);
        this.loadFailed.set(result.rowsFailed);
        // On failure the previous counts are kept visible but flagged —
        // stale-and-marked beats zeros pretending to be data.
        this.countsFailed.set(result.countsFailed);
        if (!result.countsFailed) {
          this.counts.set(result.counts);
        }
        this.loading.set(false);
      });

    this.load();
  }

  ngOnDestroy(): void {
    this.loadSub?.unsubscribe();
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
    this.load$.next({ program: this.activeProgram(), status: this.statusFilter() });
  }

  countFor(status: ProgramEnrollmentStatus): number {
    return this.counts()[status] ?? 0;
  }

  /* ── Enrol ── */

  openEnrollForm(event?: Event): void {
    this.dialogOpener = (event?.currentTarget as HTMLElement) ?? null;
    this.selectedPatient.set(null);
    this.enrolledOn.set('');
    // No prefilled cadence: it is clinical protocol the clinician knows and
    // the server refuses to guess. An empty box asks; a silent 30 asserts.
    this.cadenceDays.set(null);
    this.notes.set('');
    this.showEnrollForm.set(true);
    this.focusDialogSoon(() => this.enrollDialog()?.nativeElement);
  }

  closeEnrollForm(): void {
    this.showEnrollForm.set(false);
    this.restoreOpenerFocus();
  }

  onPatientSelected(patient: PatientResponse | null): void {
    this.selectedPatient.set(patient);
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
        this.closeEnrollForm();
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

  openStatusModal(row: ProgramEnrollment, event?: Event): void {
    this.dialogOpener = (event?.currentTarget as HTMLElement) ?? null;
    this.statusTarget.set(row);
    this.newStatus.set(row.status === 'ACTIVE' ? 'COMPLETED' : 'ACTIVE');
    this.statusReason.set('');
    this.focusDialogSoon(() => this.statusDialog()?.nativeElement);
  }

  closeStatusModal(): void {
    this.statusTarget.set(null);
    this.restoreOpenerFocus();
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
          this.closeStatusModal();
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

  /* ── Dialog focus: move in on open, cycle on Tab, restore on close ── */

  trapTab(event: KeyboardEvent, dialog: HTMLElement): void {
    const focusables = Array.from(
      dialog.querySelectorAll<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
      ),
    ).filter((el) => !el.hasAttribute('disabled'));
    if (focusables.length === 0) return;
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private focusDialogSoon(resolve: () => HTMLElement | undefined): void {
    // The dialog renders on the next change-detection pass.
    setTimeout(() => resolve()?.focus(), 0);
  }

  private restoreOpenerFocus(): void {
    this.dialogOpener?.focus();
    this.dialogOpener = null;
  }

  private extractMessage(err: unknown): string | null {
    if (!err || typeof err !== 'object') return null;
    const body = (err as { error?: { message?: string } }).error;
    return body?.message ?? null;
  }
}

import { allRowsOfArrays } from '../shared/all-pages';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';

import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { serverInstant } from '../shared/date-utils';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  RegistrationService,
  HospitalRegistration,
  MultiHospitalSummaryRow,
  PatientStayStatus,
  RegistrationRequest,
} from '../services/registration.service';
import { PatientResponse } from '../services/patient.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { refusalReason } from '../shared/refusal-reason';
import { refuseFields } from '../shared/forms/invalid-fields';
import { ReasonDialogComponent } from '../shared/reason-dialog/reason-dialog.component';
import { isFrontDeskOnly } from '../patients/patient-chart/chart-access';
type ActiveFilter = 'all' | 'active' | 'inactive';

/** QA D1430: the reason box of the edit form. */
const DEACTIVATION_REASON_FIELD = 'reg-deactivation-reason';

/**
 * Multi-hospital registrations admin (Phase 3 task 17). Writes are
 * RECEPTIONIST/HOSPITAL_ADMIN; the multi-hospital summary excludes
 * RECEPTIONIST (cross-tenant rows). DELETE is a hard delete — the default
 * "deactivate" path is a full-replace PUT with active:false.
 */
@Component({
  selector: 'app-registrations',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SubmitGuardDirective,
    FormsModule,
    TranslateModule,
    PatientPickerComponent,
    ModalDialogDirective,
    ReasonDialogComponent,
  ],
  templateUrl: './registrations.html',
  styleUrl: './registrations.scss',
})
export class RegistrationsComponent implements OnInit {
  private readonly registrationService = inject(RegistrationService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private hospitalId: string | null = null;
  private readonly destroyRef = inject(DestroyRef);
  private listRequest = 0;
  readonly searchTerm = signal('');
  readonly stayFilter = signal<PatientStayStatus | ''>('');
  readonly sortOrder = signal<'name' | 'newest'>('name');
  readonly pageIndex = signal(0);
  readonly pageSize = signal(10);
  readonly filtered = computed(() => {
    const term = this.searchTerm().toLowerCase().trim();
    const stamp = (r: HospitalRegistration) => {
      const value = r.registrationDate ? serverInstant(r.registrationDate).getTime() : 0;
      return Number.isFinite(value) ? value : 0;
    };
    return this.registrations()
      .filter(
        (r) =>
          (!this.stayFilter() || r.stayStatus === this.stayFilter()) &&
          (!term ||
            [
              r.patientFullName,
              r.patientUsername,
              r.mri,
              r.registrationCode,
              r.currentRoom,
              r.currentBed,
              r.attendingPhysicianName,
              r.patientEmail,
              r.patientPhone,
            ].some((v) => v?.toLowerCase().includes(term))),
      )
      .sort((a, b) =>
        this.sortOrder() === 'newest'
          ? stamp(b) - stamp(a)
          : (a.patientFullName ?? a.patientUsername ?? '').localeCompare(
              b.patientFullName ?? b.patientUsername ?? '',
            ),
      );
  });
  readonly metrics = computed(
    () =>
      [
        {
          id: '',
          label: 'REGISTRATIONS.LOADED',
          icon: 'how_to_reg',
          count: this.registrations().length,
        },
        {
          id: 'ADMITTED',
          label: 'REGISTRATIONS.STAY_ADMITTED',
          icon: 'hotel',
          count: this.registrations().filter((r) => r.stayStatus === 'ADMITTED').length,
        },
        {
          id: 'READY_FOR_DISCHARGE',
          label: 'REGISTRATIONS.STAY_READY_FOR_DISCHARGE',
          icon: 'assignment_turned_in',
          count: this.registrations().filter((r) => r.stayStatus === 'READY_FOR_DISCHARGE').length,
        },
        {
          id: 'HOLD',
          label: 'REGISTRATIONS.STAY_HOLD',
          icon: 'pause_circle',
          count: this.registrations().filter((r) => r.stayStatus === 'HOLD').length,
        },
      ] as const,
  );
  readonly totalPages = computed(() =>
    Math.max(1, Math.ceil(this.filtered().length / this.pageSize())),
  );
  readonly currentPage = computed(() => Math.min(this.pageIndex(), this.totalPages() - 1));
  readonly pagedRows = computed(() =>
    this.filtered().slice(
      this.currentPage() * this.pageSize(),
      (this.currentPage() + 1) * this.pageSize(),
    ),
  );
  readonly resultStart = computed(() =>
    this.filtered().length ? this.currentPage() * this.pageSize() + 1 : 0,
  );
  readonly resultEnd = computed(() =>
    Math.min((this.currentPage() + 1) * this.pageSize(), this.filtered().length),
  );
  setPage(page: number): void {
    this.pageIndex.set(Math.max(0, Math.min(page, this.totalPages() - 1)));
  }
  filterChanged(): void {
    this.pageIndex.set(0);
  }
  selectMetric(status: PatientStayStatus | ''): void {
    this.stayFilter.set(status);
    this.searchTerm.set('');
    this.filterChanged();
  }
  clearFilters(): void {
    this.stayFilter.set('');
    this.searchTerm.set('');
    this.sortOrder.set('name');
    this.filterChanged();
  }

  /** QA D1430: the registration the reason dialog is asking about. */
  readonly deactivating = signal<HospitalRegistration | null>(null);
  readonly deactivateBusy = signal(false);

  /** RCPT-CLIN: the desk neither reads the discharge note nor writes it; the server keeps the stored one. */
  readonly frontDeskOnly = isFrontDeskOnly((roles) => this.roleContext.hasAnyActiveRole(roles));

  readonly canManage = this.roleContext.hasAnyActiveRole([
    'ROLE_RECEPTIONIST',
    'ROLE_HOSPITAL_ADMIN',
  ]);
  /** Cross-tenant summary — backend excludes RECEPTIONIST. */
  readonly canSeeMultiHospital = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_SUPER_ADMIN',
  ]);

  activeFilter = signal<ActiveFilter>('active');
  registrations = signal<HospitalRegistration[]>([]);
  loading = signal(false);
  loadError = signal(false);

  showMultiHospital = signal(false);
  multiHospitalRows = signal<MultiHospitalSummaryRow[]>([]);
  multiHospitalLoading = signal(false);

  readonly stayStatuses: PatientStayStatus[] = [
    'REGISTERED',
    'ADMITTED',
    'READY_FOR_DISCHARGE',
    'DISCHARGED',
    'TRANSFERRED',
    'HOLD',
  ];

  /* ── Create / edit modal ── */
  showFormModal = signal(false);
  editingRegistration = signal<HospitalRegistration | null>(null);
  formSaving = signal(false);
  form: RegistrationRequest = {};
  formPatient = signal<PatientResponse | null>(null);

  /* ── Delete confirm ── */
  deleteTarget = signal<HospitalRegistration | null>(null);
  deleteBusy = signal(false);

  ngOnInit(): void {
    this.hospitalId =
      this.roleContext.effectiveHospitalIdForRequest() ??
      this.roleContext.activeHospitalId ??
      this.auth.getHospitalId();
    this.load();
  }

  setActiveFilter(filter: ActiveFilter): void {
    this.activeFilter.set(filter);
    this.pageIndex.set(0);
    this.load();
  }

  load(): void {
    const request = ++this.listRequest;
    this.loading.set(true);
    this.loadError.set(false);
    const filter = this.activeFilter();
    // QA D1347: every registration of the desk (all pages), not the first 200.
    allRowsOfArrays((page, size) =>
      this.registrationService.list({
        hospitalId: this.hospitalId ?? undefined,
        active: filter === 'all' ? undefined : filter === 'active',
        page,
        size,
      }),
    )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (registrations) => {
          if (request !== this.listRequest) return;
          this.registrations.set(registrations ?? []);
          this.loading.set(false);
        },
        error: () => {
          if (request !== this.listRequest) return;
          this.registrations.set([]);
          this.loadError.set(true);
          this.loading.set(false);
          this.toast.error(this.translate.instant('REGISTRATIONS.LOAD_ERROR'));
        },
      });
  }

  toggleMultiHospital(): void {
    this.showMultiHospital.update((v) => !v);
    if (this.showMultiHospital() && this.multiHospitalRows().length === 0) {
      this.multiHospitalLoading.set(true);
      this.registrationService.multiHospital().subscribe({
        next: (rows) => {
          this.multiHospitalRows.set(rows ?? []);
          this.multiHospitalLoading.set(false);
        },
        error: () => {
          this.multiHospitalRows.set([]);
          this.multiHospitalLoading.set(false);
          this.toast.error(this.translate.instant('REGISTRATIONS.MULTI_LOAD_ERROR'));
        },
      });
    }
  }

  stayStatusClass(status: PatientStayStatus | undefined): string {
    switch (status) {
      case 'ADMITTED':
        return 'status-badge status-acknowledged';
      case 'READY_FOR_DISCHARGE':
        return 'status-badge status-warning';
      case 'DISCHARGED':
        return 'status-badge status-completed';
      case 'TRANSFERRED':
        return 'status-badge status-in-progress';
      case 'HOLD':
        return 'status-badge status-submitted';
      default:
        return 'status-badge';
    }
  }

  /* ── Create / edit ── */

  /** The edit form is about to deactivate an active registration: it asks why (QA D1430). */
  get endsTheRegistration(): boolean {
    return !!this.editingRegistration()?.active && !this.form.active;
  }

  openCreate(): void {
    this.form = { stayStatus: 'REGISTERED', active: true };
    this.formPatient.set(null);
    this.editingRegistration.set(null);
    this.showFormModal.set(true);
  }

  openEdit(registration: HospitalRegistration): void {
    // PUT overwrites every editable field — carry the full current state.
    this.form = {
      patientId: registration.patientId,
      hospitalId: registration.hospitalId,
      active: registration.active,
      deactivationReason: registration.deactivationReason ?? '',
      currentRoom: registration.currentRoom ?? '',
      currentBed: registration.currentBed ?? '',
      attendingPhysicianName: registration.attendingPhysicianName ?? '',
      stayStatus: registration.stayStatus ?? 'REGISTERED',
      readyForDischargeNote: registration.readyForDischargeNote ?? '',
    };
    this.editingRegistration.set(registration);
    this.showFormModal.set(true);
  }

  closeFormModal(): void {
    this.showFormModal.set(false);
    this.editingRegistration.set(null);
  }

  onFormPatientPicked(p: PatientResponse | null): void {
    this.formPatient.set(p);
    this.form.patientId = p?.id ?? undefined;
  }

  submitForm(): void {
    const editing = this.editingRegistration();
    if (!editing && !this.form.patientId) {
      const message = this.translate.instant('REGISTRATIONS.PATIENT_REQUIRED');
      refuseFields(this.host.nativeElement, ['reg-patient'], ['reg-patient'], message);
      this.toast.error(message);
      return;
    }
    // QA D1430: the box in the edit form ends the registration as the row's button does.
    if (this.endsTheRegistration && !this.form.deactivationReason?.trim()) {
      const message = this.translate.instant('REGISTRATIONS.DEACTIVATE_REASON_REQUIRED');
      refuseFields(
        this.host.nativeElement,
        [DEACTIVATION_REASON_FIELD],
        [DEACTIVATION_REASON_FIELD],
        message,
      );
      this.toast.error(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], ['reg-patient', DEACTIVATION_REASON_FIELD], '');
    this.formSaving.set(true);
    const op = editing
      ? this.registrationService.update(editing.id, this.form)
      : this.registrationService.create({
          ...this.form,
          hospitalId: this.hospitalId ?? undefined,
        });
    op.subscribe({
      next: (saved) => {
        this.toast.success(
          this.translate.instant(editing ? 'REGISTRATIONS.UPDATED' : 'REGISTRATIONS.CREATED'),
        );
        this.formSaving.set(false);
        this.closeFormModal();
        this.registrations.update((list) =>
          editing ? list.map((r) => (r.id === saved.id ? saved : r)) : [saved, ...list],
        );
      },
      error: (err: { status?: number }) => {
        this.toast.error(
          this.translate.instant(
            err?.status === 409 || err?.status === 400
              ? 'REGISTRATIONS.SAVE_CONFLICT'
              : 'REGISTRATIONS.SAVE_ERROR',
          ),
        );
        this.formSaving.set(false);
      },
    });
  }

  /** QA D1430: the row's button only asks; one click deactivated the patient's registration. */
  askDeactivate(registration: HospitalRegistration): void {
    this.deactivating.set(registration);
  }

  /** Deactivate = PUT with active:false (never the hard DELETE), from the dialog that took the reason. */
  deactivate(registration: HospitalRegistration, reason: string): void {
    this.deactivateBusy.set(true);
    this.registrationService
      .update(registration.id, {
        patientId: registration.patientId,
        hospitalId: registration.hospitalId,
        active: false,
        deactivationReason: reason,
        currentRoom: registration.currentRoom,
        currentBed: registration.currentBed,
        attendingPhysicianName: registration.attendingPhysicianName,
        stayStatus: registration.stayStatus,
        readyForDischargeNote: registration.readyForDischargeNote,
      })
      .subscribe({
        next: (saved) => {
          this.toast.success(this.translate.instant('REGISTRATIONS.DEACTIVATED'));
          this.deactivateBusy.set(false);
          this.deactivating.set(null);
          this.registrations.update((list) => list.map((r) => (r.id === saved.id ? saved : r)));
        },
        error: (err: unknown) => {
          this.deactivateBusy.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('REGISTRATIONS.SAVE_ERROR'),
          );
        },
      });
  }

  confirmDelete(registration: HospitalRegistration): void {
    this.deleteTarget.set(registration);
  }

  closeDelete(): void {
    this.deleteTarget.set(null);
  }

  submitDelete(): void {
    const target = this.deleteTarget();
    if (!target) return;
    this.deleteBusy.set(true);
    this.registrationService.delete(target.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('REGISTRATIONS.DELETED'));
        this.deleteBusy.set(false);
        this.closeDelete();
        this.registrations.update((list) => list.filter((r) => r.id !== target.id));
      },
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('REGISTRATIONS.DELETE_ERROR'),
        );
        this.deleteBusy.set(false);
      },
    });
  }
}

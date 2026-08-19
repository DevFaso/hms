import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
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

type ActiveFilter = 'all' | 'active' | 'inactive';

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
  imports: [CommonModule, FormsModule, TranslateModule, PatientPickerComponent],
  templateUrl: './registrations.html',
  styleUrl: './registrations.scss',
})
export class RegistrationsComponent implements OnInit {
  private readonly registrationService = inject(RegistrationService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  private hospitalId: string | null = null;

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
    this.hospitalId = this.roleContext.activeHospitalId ?? this.auth.getHospitalId();
    this.load();
  }

  setActiveFilter(filter: ActiveFilter): void {
    this.activeFilter.set(filter);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    const filter = this.activeFilter();
    this.registrationService
      .list({
        hospitalId: this.hospitalId ?? undefined,
        active: filter === 'all' ? undefined : filter === 'active',
        size: 200,
      })
      .subscribe({
        next: (registrations) => {
          this.registrations.set(registrations ?? []);
          this.loading.set(false);
        },
        error: () => {
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

  openCreate(): void {
    this.form = { stayStatus: 'ADMITTED', active: true };
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
      currentRoom: registration.currentRoom ?? '',
      currentBed: registration.currentBed ?? '',
      attendingPhysicianName: registration.attendingPhysicianName ?? '',
      stayStatus: registration.stayStatus ?? 'ADMITTED',
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
      this.toast.error(this.translate.instant('REGISTRATIONS.PATIENT_REQUIRED'));
      return;
    }
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

  /** Deactivate = PUT with active:false (never the hard DELETE). */
  deactivate(registration: HospitalRegistration): void {
    this.registrationService
      .update(registration.id, {
        patientId: registration.patientId,
        hospitalId: registration.hospitalId,
        active: false,
        currentRoom: registration.currentRoom,
        currentBed: registration.currentBed,
        attendingPhysicianName: registration.attendingPhysicianName,
        stayStatus: registration.stayStatus,
        readyForDischargeNote: registration.readyForDischargeNote,
      })
      .subscribe({
        next: (saved) => {
          this.toast.success(this.translate.instant('REGISTRATIONS.DEACTIVATED'));
          this.registrations.update((list) => list.map((r) => (r.id === saved.id ? saved : r)));
        },
        error: () => this.toast.error(this.translate.instant('REGISTRATIONS.SAVE_ERROR')),
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
      error: () => {
        this.toast.error(this.translate.instant('REGISTRATIONS.DELETE_ERROR'));
        this.deleteBusy.set(false);
      },
    });
  }
}

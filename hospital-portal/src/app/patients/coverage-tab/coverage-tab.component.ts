import { ChangeDetectionStrategy, Component, Input, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  PatientInsuranceService,
  LinkInsuranceRequest,
  PatientInsurance,
  PatientInsuranceUpdateRequest,
} from '../../services/patient-insurance.service';
import { RegistrationService, HospitalRegistration } from '../../services/registration.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';

/**
 * Coverage tab on patient detail (Phase 3 task 17): insurance policies and
 * hospital registrations for one patient. Insurance roles are HOSPITAL_ADMIN/
 * RECEPTIONIST/NURSE/DOCTOR — SUPER_ADMIN is NOT granted by the backend, so
 * the host tab is hidden for super admins. Setting the primary flag goes
 * through PUT /link (the plain update ignores it).
 */
@Component({
  selector: 'app-coverage-tab',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './coverage-tab.component.html',
  styleUrl: './coverage-tab.component.scss',
})
export class CoverageTabComponent implements OnInit {
  @Input({ required: true }) patientId = '';

  private readonly insuranceService = inject(PatientInsuranceService);
  private readonly registrationService = inject(RegistrationService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly canEdit = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_RECEPTIONIST',
    'ROLE_NURSE',
    'ROLE_DOCTOR',
  ]);

  insurances = signal<PatientInsurance[]>([]);
  insurancesLoading = signal(false);
  registrations = signal<HospitalRegistration[]>([]);
  registrationsLoading = signal(false);

  /* ── Link modal (also the "set primary / add" path) ── */
  showLinkModal = signal(false);
  linkSaving = signal(false);
  linkForm: LinkInsuranceRequest = { patientId: '', payerCode: '', policyNumber: '' };
  linkPrimary = false;

  /* ── Edit modal ── */
  showEditModal = signal(false);
  editingInsurance = signal<PatientInsurance | null>(null);
  editSaving = signal(false);
  editForm: PatientInsuranceUpdateRequest = { patientId: '', providerName: '', policyNumber: '' };

  /* ── Delete ── */
  deleteTarget = signal<PatientInsurance | null>(null);
  deleteBusy = signal(false);

  ngOnInit(): void {
    this.loadInsurances();
    this.loadRegistrations();
  }

  loadInsurances(): void {
    this.insurancesLoading.set(true);
    this.insuranceService.forPatient(this.patientId).subscribe({
      next: (insurances) => {
        this.insurances.set(insurances ?? []);
        this.insurancesLoading.set(false);
      },
      error: () => {
        this.insurancesLoading.set(false);
        this.toast.error(this.translate.instant('COVERAGE.INSURANCE_LOAD_ERROR'));
      },
    });
  }

  loadRegistrations(): void {
    this.registrationsLoading.set(true);
    this.registrationService.list({ patientId: this.patientId }).subscribe({
      next: (registrations) => {
        this.registrations.set(registrations ?? []);
        this.registrationsLoading.set(false);
      },
      error: () => {
        this.registrations.set([]);
        this.registrationsLoading.set(false);
      },
    });
  }

  isExpired(insurance: PatientInsurance): boolean {
    if (!insurance.expirationDate) return false;
    const d = new Date();
    const pad = (n: number): string => String(n).padStart(2, '0');
    const today = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    return insurance.expirationDate < today;
  }

  /* ── Link ── */

  openLink(): void {
    this.linkForm = { patientId: this.patientId, payerCode: '', policyNumber: '' };
    this.linkPrimary = false;
    this.showLinkModal.set(true);
  }

  closeLink(): void {
    this.showLinkModal.set(false);
  }

  submitLink(): void {
    if (!this.linkForm.payerCode.trim() || !this.linkForm.policyNumber.trim()) {
      this.toast.error(this.translate.instant('COVERAGE.LINK_REQUIRED_FIELDS'));
      return;
    }
    this.linkSaving.set(true);
    this.insuranceService
      .link({
        ...this.linkForm,
        hospitalId: this.roleContext.activeHospitalId ?? undefined,
        primary: this.linkPrimary ? true : undefined,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('COVERAGE.LINKED'));
          this.linkSaving.set(false);
          this.closeLink();
          this.loadInsurances();
        },
        error: () => {
          this.toast.error(this.translate.instant('COVERAGE.LINK_ERROR'));
          this.linkSaving.set(false);
        },
      });
  }

  /* ── Edit ── */

  openEdit(insurance: PatientInsurance): void {
    this.editForm = {
      patientId: this.patientId,
      providerName: insurance.providerName ?? '',
      policyNumber: insurance.policyNumber ?? '',
      groupNumber: insurance.groupNumber ?? '',
      subscriberName: insurance.subscriberName ?? '',
      subscriberRelationship: insurance.subscriberRelationship ?? '',
      effectiveDate: insurance.effectiveDate ?? undefined,
      expirationDate: insurance.expirationDate ?? undefined,
    };
    this.editingInsurance.set(insurance);
    this.showEditModal.set(true);
  }

  closeEdit(): void {
    this.showEditModal.set(false);
    this.editingInsurance.set(null);
  }

  submitEdit(): void {
    const insurance = this.editingInsurance();
    if (!insurance || !this.editForm.providerName.trim() || !this.editForm.policyNumber.trim()) {
      this.toast.error(this.translate.instant('COVERAGE.EDIT_REQUIRED_FIELDS'));
      return;
    }
    this.editSaving.set(true);
    this.insuranceService.update(insurance.id, this.editForm).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('COVERAGE.UPDATED'));
        this.editSaving.set(false);
        this.closeEdit();
        this.insurances.update((list) => list.map((i) => (i.id === updated.id ? updated : i)));
      },
      error: () => {
        this.toast.error(this.translate.instant('COVERAGE.UPDATE_ERROR'));
        this.editSaving.set(false);
      },
    });
  }

  /* ── Delete ── */

  confirmDelete(insurance: PatientInsurance): void {
    this.deleteTarget.set(insurance);
  }

  closeDelete(): void {
    this.deleteTarget.set(null);
  }

  submitDelete(): void {
    const target = this.deleteTarget();
    if (!target) return;
    this.deleteBusy.set(true);
    // Backend responds 200 text/plain with a localized message.
    this.insuranceService.delete(target.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('COVERAGE.DELETED'));
        this.deleteBusy.set(false);
        this.closeDelete();
        this.insurances.update((list) => list.filter((i) => i.id !== target.id));
      },
      error: () => {
        this.toast.error(this.translate.instant('COVERAGE.DELETE_ERROR'));
        this.deleteBusy.set(false);
      },
    });
  }
}

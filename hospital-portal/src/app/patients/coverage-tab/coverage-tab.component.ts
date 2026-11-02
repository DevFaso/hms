import { refusalReason } from '../../shared/refusal-reason';
import { DialogFocusDirective } from '../../shared/a11y/dialog-focus.directive';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Input,
  OnInit,
  inject,
  signal,
} from '@angular/core';
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
import {
  Guarantor,
  GuarantorRequest,
  RegistrationExtrasService,
  TreatmentConsent,
  TreatmentConsentMethod,
} from '../../services/registration-extras.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { PhoneFieldDirective } from '../../shared/forms/phone-field.directive';
import { ReasonDialogComponent } from '../../shared/reason-dialog/reason-dialog.component';
import { focusWhenRendered } from '../../shared/a11y/focus-when-rendered';
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
  imports: [
    PhoneFieldDirective,
    SubmitGuardDirective,
    ReasonDialogComponent,
    DialogFocusDirective,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
  ],
  templateUrl: './coverage-tab.component.html',
  styleUrl: './coverage-tab.component.scss',
})
export class CoverageTabComponent implements OnInit {
  @Input({ required: true }) patientId = '';

  private readonly insuranceService = inject(PatientInsuranceService);
  private readonly registrationService = inject(RegistrationService);
  private readonly extrasService = inject(RegistrationExtrasService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

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

  /* ── Guarantors + consent-to-treat (P3 #21) ── */
  guarantors = signal<Guarantor[]>([]);
  guarantorsLoading = signal(false);
  showGuarantorModal = signal(false);
  guarantorSaving = signal(false);
  editingGuarantor = signal<Guarantor | null>(null);
  guarantorForm: GuarantorRequest = { fullName: '' };
  /** QA D1247: the guarantor whose deactivation is being confirmed (a reason is asked). */
  deactivatingGuarantor = signal<Guarantor | null>(null);
  guarantorToggleBusy = signal(false);

  consents = signal<TreatmentConsent[]>([]);
  consentsLoading = signal(false);
  showConsentModal = signal(false);
  consentSaving = signal(false);
  consentForm: { method: TreatmentConsentMethod; signedName: string; notes: string } = {
    method: 'ELECTRONIC',
    signedName: '',
    notes: '',
  };
  revokeTarget = signal<TreatmentConsent | null>(null);
  revokeReason = signal('');
  revokeBusy = signal(false);

  ngOnInit(): void {
    this.loadInsurances();
    this.loadRegistrations();
    this.loadGuarantors();
    this.loadConsents();
  }

  /**
   * QA D1247: a reload after a change keeps the table on screen (the spinner
   * replaced it and took the focused row control with it, so focus fell to
   * the page); `focusAfter` then puts focus back on the row's control.
   */
  loadGuarantors(focusAfter?: string): void {
    this.guarantorsLoading.set(this.guarantors().length === 0);
    this.extrasService.listGuarantors(this.patientId).subscribe({
      next: (guarantors) => {
        this.guarantors.set(guarantors ?? []);
        this.guarantorsLoading.set(false);
        if (focusAfter) focusWhenRendered(this.host.nativeElement, focusAfter);
      },
      error: () => {
        this.guarantors.set([]);
        this.guarantorsLoading.set(false);
      },
    });
  }

  loadConsents(): void {
    this.consentsLoading.set(true);
    this.extrasService.listConsents(this.patientId).subscribe({
      next: (consents) => {
        this.consents.set(consents ?? []);
        this.consentsLoading.set(false);
      },
      error: () => {
        this.consents.set([]);
        this.consentsLoading.set(false);
      },
    });
  }

  openGuarantor(existing: Guarantor | null): void {
    this.editingGuarantor.set(existing);
    this.guarantorForm = existing
      ? {
          fullName: existing.fullName,
          relationship: existing.relationship ?? undefined,
          phone: existing.phone ?? undefined,
          email: existing.email ?? undefined,
          address: existing.address ?? undefined,
          primary: existing.primary,
          notes: existing.notes ?? undefined,
        }
      : { fullName: '' };
    this.showGuarantorModal.set(true);
  }

  closeGuarantor(): void {
    if (this.guarantorSaving()) return;
    this.showGuarantorModal.set(false);
  }

  submitGuarantor(): void {
    if (!this.guarantorForm.fullName.trim()) {
      this.toast.error(this.translate.instant('COVERAGE.GUARANTOR_NAME_REQUIRED'));
      return;
    }
    if (this.guarantorSaving()) return;
    this.guarantorSaving.set(true);
    const existing = this.editingGuarantor();
    const call = existing
      ? this.extrasService.updateGuarantor(this.patientId, existing.id, this.guarantorForm)
      : this.extrasService.addGuarantor(this.patientId, this.guarantorForm);
    call.subscribe({
      next: (saved) => {
        this.guarantorSaving.set(false);
        this.showGuarantorModal.set(false);
        this.toast.success(this.translate.instant('COVERAGE.GUARANTOR_SAVED'));
        // QA D1247: after an edit, focus returns to that row's pencil.
        const id = existing?.id ?? saved?.id;
        this.loadGuarantors(id ? guarantorControl(id, 'edit-guarantor') : undefined);
      },
      error: (err) => {
        this.guarantorSaving.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('COVERAGE.GUARANTOR_SAVE_ERROR'),
        );
      },
    });
  }

  /**
   * QA D1247: "Désactiver" acted on one click with an empty body, no toast,
   * and focus fell to the page. Deactivating now asks for a reason in a
   * confirmation (the server requires and audits it); reactivating acts at
   * once. Both say what happened and put focus back on the row's button.
   */
  toggleGuarantorActive(guarantor: Guarantor): void {
    if (guarantor.active) {
      this.deactivatingGuarantor.set(guarantor);
      return;
    }
    this.runGuarantorToggle(
      guarantor,
      this.extrasService.reactivateGuarantor(this.patientId, guarantor.id),
      'COVERAGE.GUARANTOR_REACTIVATED',
    );
  }

  confirmDeactivateGuarantor(guarantor: Guarantor, reason: string): void {
    this.runGuarantorToggle(
      guarantor,
      this.extrasService.deactivateGuarantor(this.patientId, guarantor.id, reason),
      'COVERAGE.GUARANTOR_DEACTIVATED',
    );
  }

  private runGuarantorToggle(
    guarantor: Guarantor,
    call: ReturnType<RegistrationExtrasService['reactivateGuarantor']>,
    doneKey: string,
  ): void {
    if (this.guarantorToggleBusy()) return;
    this.guarantorToggleBusy.set(true);
    call.subscribe({
      next: () => {
        this.guarantorToggleBusy.set(false);
        this.deactivatingGuarantor.set(null);
        this.toast.success(this.translate.instant(doneKey, { name: guarantor.fullName }));
        this.loadGuarantors(guarantorControl(guarantor.id, 'toggle-guarantor'));
      },
      error: (err) => {
        this.guarantorToggleBusy.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('COVERAGE.GUARANTOR_SAVE_ERROR'),
        );
      },
    });
  }

  openConsent(): void {
    this.consentForm = { method: 'ELECTRONIC', signedName: '', notes: '' };
    this.showConsentModal.set(true);
  }

  closeConsent(): void {
    if (this.consentSaving()) return;
    this.showConsentModal.set(false);
  }

  submitConsent(): void {
    if (this.consentSaving()) return;
    this.consentSaving.set(true);
    this.extrasService
      .recordConsent(this.patientId, {
        method: this.consentForm.method,
        signedName: this.consentForm.signedName.trim() || undefined,
        notes: this.consentForm.notes.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.consentSaving.set(false);
          this.showConsentModal.set(false);
          this.toast.success(this.translate.instant('COVERAGE.CONSENT_RECORDED'));
          this.loadConsents();
        },
        error: (err) => {
          this.consentSaving.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('COVERAGE.CONSENT_SAVE_ERROR'),
          );
        },
      });
  }

  openRevoke(consent: TreatmentConsent): void {
    this.revokeTarget.set(consent);
    this.revokeReason.set('');
  }

  closeRevoke(): void {
    if (this.revokeBusy()) return;
    this.revokeTarget.set(null);
  }

  submitRevoke(): void {
    const target = this.revokeTarget();
    const reason = this.revokeReason().trim();
    if (!target) return;
    if (!reason) {
      this.toast.error(this.translate.instant('COVERAGE.CONSENT_REVOKE_REASON_REQUIRED'));
      return;
    }
    this.revokeBusy.set(true);
    this.extrasService.revokeConsent(this.patientId, target.id, reason).subscribe({
      next: () => {
        this.revokeBusy.set(false);
        this.revokeTarget.set(null);
        this.toast.success(this.translate.instant('COVERAGE.CONSENT_REVOKED_OK'));
        this.loadConsents();
      },
      error: (err) => {
        this.revokeBusy.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('COVERAGE.CONSENT_SAVE_ERROR'),
        );
      },
    });
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
        error: (err: unknown) => {
          this.toast.error(refusalReason(err) ?? this.translate.instant('COVERAGE.LINK_ERROR'));
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
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('COVERAGE.UPDATE_ERROR'));
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
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('COVERAGE.DELETE_ERROR'));
        this.deleteBusy.set(false);
      },
    });
  }
}

/** The selector of one guarantor row's button (QA D1247: focus goes back to it). */
function guarantorControl(id: string, testId: 'edit-guarantor' | 'toggle-guarantor'): string {
  return `[data-guarantor-id="${CSS.escape(id)}"] [data-testid="${testId}"]`;
}

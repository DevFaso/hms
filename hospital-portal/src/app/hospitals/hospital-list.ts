import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { HospitalService, HospitalResponse, HospitalRequest } from '../services/hospital.service';
import { OrganizationService, OrganizationResponse } from '../services/organization.service';

import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { OrgTextPipe } from '../shared/pipes/org-text.pipe';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { PhoneFieldDirective } from '../shared/forms/phone-field.directive';

type RequiredHospitalField = 'name' | 'phoneNumber' | 'country' | 'city' | 'address';

/** The form's required fields, in the order they appear, with their input ids. */
const REQUIRED_HOSPITAL_FIELDS: readonly { key: RequiredHospitalField; inputId: string }[] = [
  { key: 'name', inputId: 'hName' },
  { key: 'phoneNumber', inputId: 'hPhone' },
  { key: 'country', inputId: 'hCountry' },
  { key: 'city', inputId: 'hCity' },
  { key: 'address', inputId: 'hAddress' },
];

@Component({
  selector: 'app-hospital-list',
  standalone: true,
  imports: [
    PhoneFieldDirective,
    SubmitGuardDirective,
    ModalDialogDirective,
    FormsModule,
    RouterLink,
    TranslateModule,
    OrgTextPipe,
  ],
  templateUrl: './hospital-list.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './hospital-list.scss',
})
export class HospitalListComponent implements OnInit {
  private readonly hospitalService = inject(HospitalService);
  private readonly orgService = inject(OrganizationService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);

  /**
   * Copilot review fix — the lifecycle detail page is gated to
   * ROLE_SUPER_ADMIN, so the row-level "open detail" link is hidden
   * for other roles to avoid a click that would always 403.
   */
  readonly isSuperAdmin = this.roleContext.isSuperAdmin;

  hospitals = signal<HospitalResponse[]>([]);
  filtered = signal<HospitalResponse[]>([]);
  loading = signal(true);
  searchTerm = '';

  /** Organizations loaded for the dropdown */
  organizations = signal<OrganizationResponse[]>([]);

  // Create / Edit
  showModal = signal(false);
  editing = signal<HospitalResponse | null>(null);
  saving = signal(false);
  form: HospitalRequest = this.emptyForm();
  /** QA D817: set by a refused submit, so the empty required fields are marked. */
  showMissing = signal(false);

  // Delete
  showDeleteConfirm = signal(false);
  deletingHospital = signal<HospitalResponse | null>(null);
  deleting = signal(false);

  ngOnInit(): void {
    this.loadHospitals();
    this.orgService.list(0, 100, true).subscribe({
      next: (page) => this.organizations.set(page.content),
    });
  }

  loadHospitals(): void {
    this.loading.set(true);
    this.hospitalService.list().subscribe({
      next: (data) => {
        this.hospitals.set(data);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('HOSPITALS.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  applyFilter(): void {
    const term = this.searchTerm.toLowerCase().trim();
    if (!term) {
      this.filtered.set(this.hospitals());
      return;
    }
    this.filtered.set(
      this.hospitals().filter(
        (h) =>
          h.name.toLowerCase().includes(term) ||
          (h.code?.toLowerCase().includes(term) ?? false) ||
          (h.city?.toLowerCase().includes(term) ?? false) ||
          (h.country?.toLowerCase().includes(term) ?? false) ||
          (h.phoneNumber?.toLowerCase().includes(term) ?? false),
      ),
    );
  }

  // ---------- Create ----------
  openCreate(): void {
    this.showMissing.set(false);
    this.form = this.emptyForm();
    this.editing.set(null);
    this.showModal.set(true);
  }

  // ---------- Edit ----------
  openEdit(hospital: HospitalResponse): void {
    this.showMissing.set(false);
    this.editing.set(hospital);
    this.form = {
      name: hospital.name,
      address: hospital.address ?? '',
      city: hospital.city,
      state: hospital.state ?? '',
      zipCode: hospital.zipCode ?? '',
      country: hospital.country,
      province: hospital.province ?? '',
      region: hospital.region ?? '',
      sector: hospital.sector ?? '',
      poBox: hospital.poBox ?? '',
      phoneNumber: hospital.phoneNumber,
      email: hospital.email ?? '',
      website: hospital.website ?? '',
      organizationId: hospital.organizationId ?? '',
      active: hospital.active,
    };
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
    this.editing.set(null);
  }

  /** QA D734/D747: a value counts only when it has more than spaces. */
  hasText(value: string | null | undefined): boolean {
    return !!value?.trim();
  }

  /** QA D817: the required fields still empty, in the order the form shows them. */
  missingFields(): RequiredHospitalField[] {
    return REQUIRED_HOSPITAL_FIELDS.filter((f) => !this.hasText(this.form[f.key])).map(
      (f) => f.key,
    );
  }

  /** Whether to mark this field: only after a refused submit, and only while empty. */
  isMissing(field: RequiredHospitalField): boolean {
    return this.showMissing() && !this.hasText(this.form[field]);
  }

  submitForm(): void {
    // QA D734/D747: the street is required (a P.O. box no longer stands in
    // for it); say so here instead of after the round trip.
    // QA D817: the toast said a P.O. box could replace the street, and no
    // field was marked. It now names the street, and the empty fields are
    // highlighted with the first one focused.
    const missing = this.missingFields();
    if (missing.length > 0) {
      this.showMissing.set(true);
      this.toast.error(this.translate.instant('HOSPITALS.REQUIRED_FIELDS'));
      const first = REQUIRED_HOSPITAL_FIELDS.find((f) => f.key === missing[0]);
      if (first) {
        // After the change detection that applies the marks.
        setTimeout(() => document.getElementById(first.inputId)?.focus());
      }
      return;
    }
    this.showMissing.set(false);
    this.saving.set(true);

    // Remove empty optional strings before sending
    const payload: HospitalRequest = { ...this.form };
    if (!payload.organizationId) delete payload.organizationId;

    const existing = this.editing();
    const request$ = existing
      ? this.hospitalService.update(existing.id, payload)
      : this.hospitalService.create(payload);

    request$.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(existing ? 'HOSPITALS.UPDATED' : 'HOSPITALS.CREATED'),
        );
        this.closeModal();
        this.saving.set(false);
        this.loadHospitals();
      },
      error: (err) => {
        const body = err?.error;
        let msg = this.translate.instant(
          existing ? 'HOSPITALS.UPDATE_FAILED' : 'HOSPITALS.CREATE_FAILED',
        );
        if (body?.fieldErrors) {
          msg = Object.values(body.fieldErrors).join('. ');
        } else if (body?.message) {
          msg = body.message;
        }
        this.toast.error(msg);
        this.saving.set(false);
      },
    });
  }

  // ---------- Delete ----------
  confirmDelete(hospital: HospitalResponse): void {
    this.deletingHospital.set(hospital);
    this.showDeleteConfirm.set(true);
  }

  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingHospital.set(null);
  }

  executeDelete(): void {
    const hospital = this.deletingHospital();
    if (!hospital) return;
    this.deleting.set(true);
    this.hospitalService.delete(hospital.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('HOSPITALS.DELETED'));
        this.cancelDelete();
        this.deleting.set(false);
        this.loadHospitals();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? this.translate.instant('HOSPITALS.DELETE_FAILED'));
        this.deleting.set(false);
      },
    });
  }

  // ---------- Helpers ----------
  private emptyForm(): HospitalRequest {
    return {
      name: '',
      address: '',
      city: '',
      state: '',
      zipCode: '',
      country: '',
      province: '',
      region: '',
      sector: '',
      poBox: '',
      phoneNumber: '',
      email: '',
      website: '',
      organizationId: '',
      active: true,
    };
  }
}

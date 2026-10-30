import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { ToastService } from '../core/toast.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { RoleContextService } from '../core/role-context.service';
import { PharmacyService, PharmacyRequest, PharmacyResponse } from '../services/pharmacy.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';

@Component({
  selector: 'app-pharmacy-registry',
  standalone: true,
  imports: [FormsModule, TranslateModule, EnumLabelPipe, ModalDialogDirective],
  templateUrl: './pharmacy-registry.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './pharmacy-registry.scss',
})
export class PharmacyRegistryComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly hospitalService = inject(HospitalService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  pharmacies = signal<PharmacyResponse[]>([]);
  loading = signal(true);
  searchTerm = '';

  hospitals = signal<HospitalResponse[]>([]);
  hospitalLocked = !this.roleContext.isSuperAdmin();

  // Pagination
  currentPage = 0;
  pageSize = 20;
  totalElements = 0;
  totalPages = 0;

  // Modal
  showModal = signal(false);
  editing = signal<PharmacyResponse | null>(null);
  saving = signal(false);
  form: PharmacyRequest = this.emptyForm();

  // Delete
  showDeleteConfirm = signal(false);
  deletingItem = signal<PharmacyResponse | null>(null);
  deleting = signal(false);

  ngOnInit(): void {
    this.loadPharmacies();
    this.loadHospitals();
  }

  private loadHospitals(): void {
    if (this.roleContext.isSuperAdmin()) {
      this.hospitalService.list().subscribe({
        next: (data) => this.hospitals.set(data),
      });
    } else {
      this.hospitalService.getMyHospitalAsResponse().subscribe({
        next: (h) => {
          this.hospitals.set([h]);
          this.form.hospitalId = h.id;
        },
      });
    }
  }

  get lockedHospitalName(): string {
    const h = this.hospitals();
    return h.length === 1 ? h[0].name : this.translate.instant('COMMON.NO_HOSPITAL_ASSIGNED');
  }

  loadPharmacies(): void {
    this.loading.set(true);
    const obs = this.searchTerm.trim()
      ? this.svc.searchPharmacies(this.searchTerm.trim(), this.currentPage, this.pageSize)
      : this.svc.listPharmacies(this.currentPage, this.pageSize);

    obs.subscribe({
      next: (page) => {
        const list = page?.content ?? [];
        this.pharmacies.set(list);
        this.totalElements = page?.totalElements ?? 0;
        this.totalPages = page?.totalPages ?? 0;
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('PHARMACY.TOAST.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  onSearch(): void {
    this.currentPage = 0;
    this.loadPharmacies();
  }

  prevPage(): void {
    if (this.currentPage > 0) {
      this.currentPage--;
      this.loadPharmacies();
    }
  }

  nextPage(): void {
    if (this.currentPage < this.totalPages - 1) {
      this.currentPage++;
      this.loadPharmacies();
    }
  }

  openCreate(): void {
    this.form = this.emptyForm();
    const h = this.hospitals();
    if (this.hospitalLocked && h.length === 1) {
      this.form.hospitalId = h[0].id;
    }
    this.editing.set(null);
    this.showModal.set(true);
  }

  openEdit(item: PharmacyResponse): void {
    this.editing.set(item);
    this.form = {
      hospitalId: item.hospitalId,
      name: item.name,
      pharmacyType: item.pharmacyType ?? '',
      licenseNumber: item.licenseNumber ?? '',
      facilityCode: item.facilityCode ?? '',
      phoneNumber: item.phoneNumber ?? '',
      email: item.email ?? '',
      addressLine1: item.addressLine1 ?? '',
      addressLine2: item.addressLine2 ?? '',
      city: item.city ?? '',
      region: item.region ?? '',
      postalCode: item.postalCode ?? '',
      country: item.country ?? '',
      fulfillmentMode: item.fulfillmentMode ?? '',
      active: item.active,
    };
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
    this.editing.set(null);
  }

  submitForm(): void {
    if (!this.form.name || !this.form.hospitalId) {
      this.toast.error(this.translate.instant('PHARMACY.TOAST.REQUIRED_FIELDS'));
      return;
    }
    this.saving.set(true);
    const existing = this.editing();
    // QA D336: an unselected type or mode went out as "" and the server
    // could not read it as a value, so the save failed with a bare field name.
    const payload = this.toRequest();
    const req$ = existing
      ? this.svc.updatePharmacy(existing.id, payload)
      : this.svc.createPharmacy(payload);

    req$.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(existing ? 'PHARMACY.TOAST.UPDATED' : 'PHARMACY.TOAST.CREATED'),
        );
        this.closeModal();
        this.saving.set(false);
        this.loadPharmacies();
      },
      error: (err) => {
        const msg =
          err?.error?.message ??
          this.translate.instant(
            existing ? 'PHARMACY.TOAST.UPDATE_FAILED' : 'PHARMACY.TOAST.CREATE_FAILED',
          );
        this.toast.error(msg);
        this.saving.set(false);
      },
    });
  }

  confirmDelete(item: PharmacyResponse): void {
    this.deletingItem.set(item);
    this.showDeleteConfirm.set(true);
  }

  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingItem.set(null);
  }

  executeDelete(): void {
    const item = this.deletingItem();
    if (!item) return;
    this.deleting.set(true);
    this.svc.deletePharmacy(item.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.TOAST.DELETED'));
        this.cancelDelete();
        this.deleting.set(false);
        this.loadPharmacies();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('PHARMACY.TOAST.DELETE_FAILED'),
        );
        this.deleting.set(false);
      },
    });
  }

  /** The form as the server reads it: an empty choice is no value, not "". */
  toRequest(): PharmacyRequest {
    return {
      ...this.form,
      pharmacyType: this.form.pharmacyType || undefined,
      fulfillmentMode: this.form.fulfillmentMode || undefined,
    } as PharmacyRequest;
  }

  /**
   * QA D336: only a hospital or super admin may create, edit or delete a
   * registry entry (the server refuses everyone else with 403); a pharmacist
   * reads it.
   */
  canManage(): boolean {
    return this.roleContext.hasAnyActiveRoleExactly(['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN']);
  }

  private emptyForm(): PharmacyRequest {
    return {
      hospitalId: '',
      name: '',
      pharmacyType: '',
      licenseNumber: '',
      facilityCode: '',
      phoneNumber: '',
      email: '',
      addressLine1: '',
      addressLine2: '',
      city: '',
      region: '',
      postalCode: '',
      country: 'Burkina Faso',
      fulfillmentMode: '',
      active: true,
    };
  }
}

import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { RoleContextService } from '../core/role-context.service';
import { PharmacyService, PharmacyRequest, PharmacyResponse } from '../services/pharmacy.service';

@Component({
  selector: 'app-pharmacy-registry',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './pharmacy-registry.html',
  styleUrl: './pharmacy-registry.scss',
})
export class PharmacyRegistryComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly hospitalService = inject(HospitalService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);

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
    return h.length === 1 ? h[0].name : 'No hospital assigned';
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
        this.toast.error('Failed to load pharmacies');
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
      this.toast.error('Hospital and name are required');
      return;
    }
    this.saving.set(true);
    const existing = this.editing();
    const req$ = existing
      ? this.svc.updatePharmacy(existing.id, this.form)
      : this.svc.createPharmacy(this.form);

    req$.subscribe({
      next: () => {
        this.toast.success(existing ? 'Pharmacy updated' : 'Pharmacy created');
        this.closeModal();
        this.saving.set(false);
        this.loadPharmacies();
      },
      error: (err) => {
        const msg = err?.error?.message ?? `Failed to ${existing ? 'update' : 'create'} pharmacy`;
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
        this.toast.success('Pharmacy deleted');
        this.cancelDelete();
        this.deleting.set(false);
        this.loadPharmacies();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to delete pharmacy');
        this.deleting.set(false);
      },
    });
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

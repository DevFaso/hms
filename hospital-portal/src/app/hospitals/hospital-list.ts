import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HospitalService, HospitalResponse, HospitalRequest } from '../services/hospital.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-hospital-list',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './hospital-list.html',
  styleUrl: './hospital-list.scss',
})
export class HospitalListComponent implements OnInit {
  private readonly hospitalService = inject(HospitalService);
  private readonly toast = inject(ToastService);

  hospitals = signal<HospitalResponse[]>([]);
  filtered = signal<HospitalResponse[]>([]);
  loading = signal(true);
  searchTerm = '';

  // Create / Edit
  showModal = signal(false);
  editing = signal<HospitalResponse | null>(null);
  saving = signal(false);
  form: HospitalRequest = this.emptyForm();

  // Delete
  showDeleteConfirm = signal(false);
  deletingHospital = signal<HospitalResponse | null>(null);
  deleting = signal(false);

  // Pagination
  currentPage = signal(0);
  totalPages = signal(0);
  totalElements = signal(0);

  ngOnInit(): void {
    this.loadHospitals();
  }

  loadHospitals(page = 0): void {
    this.loading.set(true);
    this.hospitalService.list(page, 20).subscribe({
      next: (res) => {
        this.hospitals.set(res.content);
        this.currentPage.set(res.number);
        this.totalPages.set(res.totalPages);
        this.totalElements.set(res.totalElements);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load hospitals');
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
    this.form = this.emptyForm();
    this.editing.set(null);
    this.showModal.set(true);
  }

  // ---------- Edit ----------
  openEdit(hospital: HospitalResponse): void {
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

  submitForm(): void {
    if (!this.form.name || !this.form.city || !this.form.country || !this.form.phoneNumber) {
      this.toast.error('Name, city, country, and phone number are required');
      return;
    }
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
        this.toast.success(existing ? 'Hospital updated' : 'Hospital created');
        this.closeModal();
        this.saving.set(false);
        this.loadHospitals(this.currentPage());
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? `Failed to ${existing ? 'update' : 'create'} hospital`,
        );
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
        this.toast.success('Hospital deleted');
        this.cancelDelete();
        this.deleting.set(false);
        this.loadHospitals(this.currentPage());
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to delete hospital');
        this.deleting.set(false);
      },
    });
  }

  // ---------- Pagination ----------
  goToPage(page: number): void {
    if (page >= 0 && page < this.totalPages()) {
      this.loadHospitals(page);
    }
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

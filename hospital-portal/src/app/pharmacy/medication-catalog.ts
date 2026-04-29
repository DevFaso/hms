import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import {
  PharmacyService,
  MedicationCatalogItemRequest,
  MedicationCatalogItemResponse,
} from '../services/pharmacy.service';

@Component({
  selector: 'app-medication-catalog',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './medication-catalog.html',
  styleUrl: './medication-catalog.scss',
})
export class MedicationCatalogComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);

  items = signal<MedicationCatalogItemResponse[]>([]);
  filtered = signal<MedicationCatalogItemResponse[]>([]);
  loading = signal(true);
  searchTerm = '';

  // Pagination
  currentPage = 0;
  pageSize = 20;
  totalElements = 0;
  totalPages = 0;

  // Modal
  showModal = signal(false);
  editing = signal<MedicationCatalogItemResponse | null>(null);
  saving = signal(false);
  form: MedicationCatalogItemRequest = this.emptyForm();

  // Delete
  showDeleteConfirm = signal(false);
  deletingItem = signal<MedicationCatalogItemResponse | null>(null);
  deleting = signal(false);

  ngOnInit(): void {
    this.loadItems();
  }

  loadItems(): void {
    this.loading.set(true);
    const obs = this.searchTerm.trim()
      ? this.svc.searchMedications(this.searchTerm.trim(), this.currentPage, this.pageSize)
      : this.svc.listMedications(this.currentPage, this.pageSize);

    obs.subscribe({
      next: (page) => {
        const list = page?.content ?? [];
        this.items.set(list);
        this.filtered.set(list);
        this.totalElements = page?.totalElements ?? 0;
        this.totalPages = page?.totalPages ?? 0;
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load medication catalog');
        this.loading.set(false);
      },
    });
  }

  onSearch(): void {
    this.currentPage = 0;
    this.loadItems();
  }

  prevPage(): void {
    if (this.currentPage > 0) {
      this.currentPage--;
      this.loadItems();
    }
  }

  nextPage(): void {
    if (this.currentPage < this.totalPages - 1) {
      this.currentPage++;
      this.loadItems();
    }
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(null);
    this.showModal.set(true);
  }

  openEdit(item: MedicationCatalogItemResponse): void {
    this.editing.set(item);
    this.form = {
      nameFr: item.nameFr,
      genericName: item.genericName,
      brandName: item.brandName ?? '',
      atcCode: item.atcCode ?? '',
      form: item.form ?? '',
      strength: item.strength ?? '',
      strengthUnit: item.strengthUnit ?? '',
      rxnormCode: item.rxnormCode ?? '',
      route: item.route ?? '',
      category: item.category ?? '',
      essentialList: item.essentialList ?? false,
      controlled: item.controlled ?? false,
      description: item.description ?? '',
    };
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
    this.editing.set(null);
  }

  /** WHO ATC: anatomical letter, two digits, two letters, two digits (e.g. J01CA04). */
  static readonly ATC_PATTERN = /^[A-Z]\d{2}[A-Z]{2}\d{2}$/;

  /** RxNorm RxCUI: 1–12 digits. */
  static readonly RXNORM_PATTERN = /^\d{1,12}$/;

  isAtcValid(): boolean {
    const value = (this.form.atcCode ?? '').trim().toUpperCase();
    if (value.length === 0) return true;
    return MedicationCatalogComponent.ATC_PATTERN.test(value);
  }

  isRxNormValid(): boolean {
    const value = (this.form.rxnormCode ?? '').trim();
    if (value.length === 0) return true;
    return MedicationCatalogComponent.RXNORM_PATTERN.test(value);
  }

  submitForm(): void {
    if (!this.form.nameFr || !this.form.genericName) {
      this.toast.error('Name (FR) and generic name are required');
      return;
    }
    if (!this.isAtcValid()) {
      this.toast.error('ATC code must match WHO format L##LL## (e.g. J01CA04)');
      return;
    }
    if (!this.isRxNormValid()) {
      this.toast.error('RxNorm code must be 1–12 digits');
      return;
    }
    this.saving.set(true);
    const existing = this.editing();
    const req$ = existing
      ? this.svc.updateMedication(existing.id, this.form)
      : this.svc.createMedication(this.form);

    req$.subscribe({
      next: () => {
        this.toast.success(existing ? 'Medication updated' : 'Medication created');
        this.closeModal();
        this.saving.set(false);
        this.loadItems();
      },
      error: (err) => {
        const msg = err?.error?.message ?? `Failed to ${existing ? 'update' : 'create'} medication`;
        this.toast.error(msg);
        this.saving.set(false);
      },
    });
  }

  confirmDelete(item: MedicationCatalogItemResponse): void {
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
    this.svc.deleteMedication(item.id).subscribe({
      next: () => {
        this.toast.success('Medication deleted');
        this.cancelDelete();
        this.deleting.set(false);
        this.loadItems();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to delete medication');
        this.deleting.set(false);
      },
    });
  }

  private emptyForm(): MedicationCatalogItemRequest {
    return {
      nameFr: '',
      genericName: '',
      brandName: '',
      atcCode: '',
      form: '',
      strength: '',
      strengthUnit: '',
      rxnormCode: '',
      route: '',
      category: '',
      essentialList: false,
      controlled: false,
      description: '',
    };
  }
}

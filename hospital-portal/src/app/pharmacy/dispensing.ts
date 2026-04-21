import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import {
  PharmacyService,
  PharmacyResponse,
  InventoryItemResponse,
  DispenseRequest,
  DispenseResponse,
} from '../services/pharmacy.service';
import { AuthService } from '../auth/auth.service';

@Component({
  selector: 'app-dispensing',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './dispensing.html',
  styleUrl: './dispensing.scss',
})
export class DispensingComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);

  // Work queue
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  workQueue = signal<any[]>([]);
  queueLoading = signal(false);
  queuePage = 0;
  queueTotalPages = 0;

  // Pharmacies
  pharmacies = signal<PharmacyResponse[]>([]);
  selectedPharmacyId = '';

  // Inventory items for stock lot selection
  inventoryItems = signal<InventoryItemResponse[]>([]);

  // Dispensing form
  showForm = signal(false);
  saving = signal(false);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  selectedPrescription: any = null;
  form: DispenseRequest = this.emptyForm();

  // Recent dispenses
  recentDispenses = signal<DispenseResponse[]>([]);
  dispensesLoading = signal(false);

  ngOnInit(): void {
    this.loadPharmacies();
  }

  private loadPharmacies(): void {
    this.svc.listPharmacies(0, 100).subscribe({
      next: (page) => {
        const list = page?.content ?? [];
        this.pharmacies.set(list);
        if (list.length > 0) {
          this.selectedPharmacyId = list[0].id;
          this.loadWorkQueue();
          this.loadRecentDispenses();
          this.loadInventory();
        }
      },
      error: () => this.toast.error('Failed to load pharmacies'),
    });
  }

  loadWorkQueue(): void {
    this.queueLoading.set(true);
    this.svc.getDispenseWorkQueue(this.queuePage, 20).subscribe({
      next: (res) => {
        const page = res?.data;
        this.workQueue.set(page?.content ?? []);
        this.queueTotalPages = page?.totalPages ?? 0;
        this.queueLoading.set(false);
      },
      error: () => {
        this.queueLoading.set(false);
        this.toast.error('Failed to load work queue');
      },
    });
  }

  private loadRecentDispenses(): void {
    if (!this.selectedPharmacyId) return;
    this.dispensesLoading.set(true);
    this.svc.listDispensesByPharmacy(this.selectedPharmacyId, 0, 10).subscribe({
      next: (res) => {
        this.recentDispenses.set(res?.data?.content ?? []);
        this.dispensesLoading.set(false);
      },
      error: () => {
        this.dispensesLoading.set(false);
      },
    });
  }

  private loadInventory(): void {
    if (!this.selectedPharmacyId) return;
    this.svc.listInventoryByPharmacy(this.selectedPharmacyId, 0, 200).subscribe({
      next: (res) => {
        const page = res?.data;
        this.inventoryItems.set(page?.content ?? []);
      },
    });
  }

  onPharmacyChange(): void {
    this.loadWorkQueue();
    this.loadRecentDispenses();
    this.loadInventory();
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  selectPrescription(rx: any): void {
    this.selectedPrescription = rx;
    this.form = this.emptyForm();
    this.form.prescriptionId = rx.id;
    this.form.patientId = rx.patient?.id ?? '';
    this.form.pharmacyId = this.selectedPharmacyId;
    this.form.medicationName = rx.medicationName ?? '';
    this.form.quantityRequested = rx.quantity ?? 0;
    this.form.dispensedBy = this.auth.currentProfile()?.id ?? '';
    this.showForm.set(true);
  }

  submitDispense(): void {
    this.saving.set(true);
    this.svc.createDispense(this.form).subscribe({
      next: () => {
        this.toast.success('Medication dispensed successfully');
        this.saving.set(false);
        this.showForm.set(false);
        this.selectedPrescription = null;
        this.loadWorkQueue();
        this.loadRecentDispenses();
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(err?.error?.message ?? 'Dispense failed');
      },
    });
  }

  cancelDispense(id: string): void {
    if (!confirm('Cancel this dispense and reverse stock changes?')) return;
    this.svc.cancelDispense(id).subscribe({
      next: () => {
        this.toast.success('Dispense cancelled');
        this.loadRecentDispenses();
        this.loadWorkQueue();
      },
      error: (err) => this.toast.error(err?.error?.message ?? 'Cancel failed'),
    });
  }

  closeForm(): void {
    this.showForm.set(false);
    this.selectedPrescription = null;
  }

  prevPage(): void {
    if (this.queuePage > 0) {
      this.queuePage--;
      this.loadWorkQueue();
    }
  }

  nextPage(): void {
    if (this.queuePage < this.queueTotalPages - 1) {
      this.queuePage++;
      this.loadWorkQueue();
    }
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'COMPLETED':
        return 'badge-success';
      case 'PARTIAL':
        return 'badge-warning';
      case 'CANCELLED':
        return 'badge-danger';
      default:
        return 'badge-info';
    }
  }

  private emptyForm(): DispenseRequest {
    return {
      prescriptionId: '',
      patientId: '',
      pharmacyId: '',
      dispensedBy: '',
      medicationName: '',
      quantityRequested: 0,
      quantityDispensed: 0,
    };
  }
}

import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import {
  PharmacyService,
  PharmacyResponse,
  InventoryItemResponse,
  StockTransactionRequest,
  StockTransactionResponse,
} from '../services/pharmacy.service';

@Component({
  selector: 'app-stock-adjustment',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './stock-adjustment.html',
  styleUrl: './stock-adjustment.scss',
})
export class StockAdjustmentComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);

  pharmacies = signal<PharmacyResponse[]>([]);
  selectedPharmacyId = '';
  inventoryItems = signal<InventoryItemResponse[]>([]);

  // Recent transactions
  transactions = signal<StockTransactionResponse[]>([]);
  txLoading = signal(false);

  // Filter
  filterType = '';
  transactionTypes = [
    'RECEIVE',
    'DISPENSE',
    'ADJUSTMENT_IN',
    'ADJUSTMENT_OUT',
    'RETURN',
    'EXPIRED',
    'DAMAGED',
    'TRANSFER_IN',
    'TRANSFER_OUT',
  ];

  // Form
  showForm = signal(false);
  saving = signal(false);
  form: StockTransactionRequest = this.emptyForm();

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
          this.onPharmacyChange();
        }
      },
    });
  }

  onPharmacyChange(): void {
    if (!this.selectedPharmacyId) return;
    this.loadInventoryItems();
    this.loadTransactions();
  }

  private loadInventoryItems(): void {
    this.svc
      .listInventoryByPharmacy(this.selectedPharmacyId, 0, 200)
      .subscribe({
        next: (res) => {
          this.inventoryItems.set(res?.data?.content ?? []);
        },
      });
  }

  loadTransactions(): void {
    this.txLoading.set(true);
    const obs = this.filterType
      ? this.svc.listTransactionsByType(this.selectedPharmacyId, this.filterType)
      : this.svc.listTransactionsByPharmacy(this.selectedPharmacyId);

    obs.subscribe({
      next: (res) => {
        const page = res?.data;
        this.transactions.set(page?.content ?? []);
        this.txLoading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load transactions');
        this.txLoading.set(false);
      },
    });
  }

  onFilterChange(): void {
    this.loadTransactions();
  }

  openForm(): void {
    this.form = this.emptyForm();
    this.showForm.set(true);
  }

  closeForm(): void {
    this.showForm.set(false);
  }

  submitForm(): void {
    if (!this.form.inventoryItemId || !this.form.transactionType || this.form.quantity <= 0) {
      this.toast.error('Item, type, and quantity are required');
      return;
    }
    this.saving.set(true);
    this.svc.recordTransaction(this.form).subscribe({
      next: () => {
        this.toast.success('Transaction recorded');
        this.closeForm();
        this.saving.set(false);
        this.loadTransactions();
        this.loadInventoryItems();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to record transaction');
        this.saving.set(false);
      },
    });
  }

  getItemName(item: InventoryItemResponse): string {
    return item.medicationName ?? item.medicationCode ?? 'Unknown item';
  }

  getTypeClass(type: string): string {
    if (['RECEIVE', 'ADJUSTMENT_IN', 'RETURN', 'TRANSFER_IN'].includes(type)) return 'tx-in';
    return 'tx-out';
  }

  private emptyForm(): StockTransactionRequest {
    return {
      inventoryItemId: '',
      transactionType: '',
      quantity: 0,
      reason: '',
      referenceId: '',
    };
  }
}

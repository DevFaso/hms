import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { DatePipe } from '@angular/common';
import { LabValuePipe } from '../shared/lab-value.pipe';
import {
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';

import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  LabInstrumentService,
  LabInventoryItemResponse,
  LabInventoryItemRequest,
} from '../../services/lab-instrument.service';
import { AuthService } from '../../auth/auth.service';
import { ToastService } from '../../core/toast.service';
import { ModalDialogDirective } from '../../shared/a11y/modal-dialog.directive';
import { labelledRefusalReason, refusalReason } from '../../shared/refusal-reason';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';

/**
 * QA D1193 (round 22): a field the server refuses is named with its label, not
 * shown as the bare constraint ("ne doit pas être vide").
 */
const SAVE_FIELD_LABELS: Readonly<Record<string, string>> = {
  name: 'LAB_INVENTORY.NAME',
  itemCode: 'LAB_INVENTORY.ITEM_CODE',
  quantity: 'LAB_INVENTORY.QUANTITY',
  reorderThreshold: 'LAB_INVENTORY.REORDER_THRESHOLD',
};

@Component({
  selector: 'app-lab-inventory',
  standalone: true,
  imports: [
    SubmitGuardDirective,
    ModalDialogDirective,
    DatePipe,
    ServerTimePipe,
    LabValuePipe,
    RouterModule,
    FormsModule,
    TranslateModule,
  ],
  templateUrl: './lab-inventory.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './lab-inventory.scss',
})
export class LabInventoryComponent implements OnInit {
  private readonly instrumentService = inject(LabInstrumentService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  loading = signal(true);
  error = signal<string | null>(null);
  items = signal<LabInventoryItemResponse[]>([]);
  totalElements = signal(0);

  canManage = computed(() => {
    const roles = this.auth.getRoles();
    return (
      roles.includes('ROLE_SUPER_ADMIN') ||
      roles.includes('ROLE_HOSPITAL_ADMIN') ||
      roles.includes('ROLE_LAB_DIRECTOR') ||
      roles.includes('ROLE_LAB_MANAGER')
    );
  });

  showForm = signal(false);
  editingId = signal<string | null>(null);
  saving = signal(false);

  form: LabInventoryItemRequest = this.emptyForm();

  ngOnInit(): void {
    this.loadItems();
  }

  loadItems(): void {
    const hospitalId = this.auth.getHospitalId();
    if (!hospitalId) {
      this.error.set(this.translate.instant('LAB_INVENTORY.NO_HOSPITAL'));
      this.loading.set(false);
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.instrumentService.getInventoryItems(hospitalId).subscribe({
      next: (page) => {
        this.items.set(page.content ?? []);
        this.totalElements.set(page.totalElements ?? 0);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Failed to load inventory', err);
        this.error.set(this.translate.instant('LAB_INVENTORY.LOAD_ERROR'));
        this.loading.set(false);
      },
    });
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editingId.set(null);
    this.showForm.set(true);
  }

  openEdit(item: LabInventoryItemResponse): void {
    this.form = {
      name: item.name,
      itemCode: item.itemCode,
      category: item.category,
      quantity: item.quantity,
      unit: item.unit,
      reorderThreshold: item.reorderThreshold,
      supplier: item.supplier,
      lotNumber: item.lotNumber,
      expirationDate: item.expirationDate,
      notes: item.notes,
    };
    this.editingId.set(item.id);
    this.showForm.set(true);
  }

  cancelForm(): void {
    this.showForm.set(false);
    this.editingId.set(null);
    this.form = this.emptyForm();
  }

  save(): void {
    const hospitalId = this.auth.getHospitalId();
    if (!hospitalId) return;
    this.saving.set(true);

    const id = this.editingId();
    const op = id
      ? this.instrumentService.updateInventoryItem(id, this.form)
      : this.instrumentService.createInventoryItem(hospitalId, this.form);

    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(id ? 'LAB_INVENTORY.UPDATED' : 'LAB_INVENTORY.CREATED'),
        );
        this.cancelForm();
        this.loadItems();
        this.saving.set(false);
      },
      error: (err) => {
        console.error('Save inventory item failed', err);
        this.toast.error(
          labelledRefusalReason(err, this.translate, SAVE_FIELD_LABELS) ??
            this.translate.instant('LAB_INVENTORY.SAVE_ERROR'),
        );
        this.saving.set(false);
      },
    });
  }

  deactivate(item: LabInventoryItemResponse): void {
    if (!confirm(this.translate.instant('LAB_INVENTORY.CONFIRM_DEACTIVATE'))) return;

    this.instrumentService.deactivateInventoryItem(item.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB_INVENTORY.DEACTIVATED'));
        this.loadItems();
      },
      error: (err) => {
        console.error('Deactivate inventory item failed', err);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('LAB_INVENTORY.DEACTIVATE_ERROR'),
        );
      },
    });
  }

  private emptyForm(): LabInventoryItemRequest {
    return {
      name: '',
      itemCode: '',
      quantity: 0,
      reorderThreshold: 0,
    };
  }
}

import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import {
  ControlledRegister,
  ControlledRegisterDrug,
  InventoryItemResponse,
  PharmacyResponse,
  PharmacyService,
} from '../services/pharmacy.service';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';

/** ISO date (YYYY-MM-DD) in the browser's calendar. */
function localIsoDate(d: Date): string {
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

/**
 * QA D967: the controlled-drug (stupéfiants) register. Only the general
 * ledger existed: no medicine column, no patient or prescriber, no balance.
 * This page reads the register the server builds from the ledger - per
 * controlled drug, every movement with its running balance, the patient and
 * prescriber of a dispense, who recorded it and the witness - for a period,
 * and exports it as CSV or PDF for an inspection.
 */
@Component({
  selector: 'app-controlled-register',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, ServerTimePipe, EnumLabelPipe],
  templateUrl: './controlled-register.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './controlled-register.scss',
})
export class ControlledRegisterComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly pharmacies = signal<PharmacyResponse[]>([]);
  readonly pharmaciesLoaded = signal(false);
  /** The pharmacy's controlled items, for the drug filter. */
  readonly controlledItems = signal<InventoryItemResponse[]>([]);
  readonly register = signal<ControlledRegister | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly exporting = signal(false);

  selectedPharmacyId = '';
  /** Empty: every controlled drug of the pharmacy. */
  selectedItemId = '';
  from = '';
  to = '';
  private loadSeq = 0;

  ngOnInit(): void {
    const today = new Date();
    this.to = localIsoDate(today);
    this.from = localIsoDate(new Date(today.getFullYear(), today.getMonth(), 1));
    this.svc.listPharmacies(0, 100).subscribe({
      next: (page) => {
        const list = page?.content ?? [];
        this.pharmacies.set(list);
        this.pharmaciesLoaded.set(true);
        if (list.length > 0) {
          this.selectedPharmacyId = list[0].id;
          this.onPharmacyChange();
        }
      },
      error: () => this.pharmaciesLoaded.set(true),
    });
  }

  onPharmacyChange(): void {
    this.selectedItemId = '';
    this.controlledItems.set([]);
    this.register.set(null);
    if (!this.selectedPharmacyId) return;
    this.svc.listInventoryByPharmacy(this.selectedPharmacyId, 0, 500).subscribe({
      next: (res) =>
        this.controlledItems.set((res?.data?.content ?? []).filter((item) => !!item.controlled)),
      error: () => this.controlledItems.set([]),
    });
    this.load();
  }

  /** True when the period can be read: both dates, the end not before the start. */
  periodValid(): boolean {
    return !!this.from && !!this.to && this.from <= this.to;
  }

  load(): void {
    if (!this.selectedPharmacyId) return;
    if (!this.periodValid()) {
      this.toast.error(this.translate.instant('PHARMACY.REGISTER_PERIOD_INVALID'));
      return;
    }
    const seq = ++this.loadSeq;
    this.loading.set(true);
    this.failed.set(false);
    this.svc
      .getControlledRegister(
        this.selectedPharmacyId,
        this.from,
        this.to,
        this.selectedItemId || undefined,
      )
      .subscribe({
        next: (res) => {
          if (seq !== this.loadSeq) return;
          this.register.set(res?.data ?? null);
          this.loading.set(false);
        },
        error: (err) => {
          if (seq !== this.loadSeq) return;
          this.loading.set(false);
          this.failed.set(true);
          this.toast.error(
            err?.error?.message ?? this.translate.instant('PHARMACY.REGISTER_LOAD_FAILED'),
          );
        },
      });
  }

  /** The shelf differs from what the register carries out: for the inspector to see. */
  hasDiscrepancy(drug: ControlledRegisterDrug): boolean {
    return (
      drug.quantityOnHand != null && Number(drug.quantityOnHand) !== Number(drug.closingBalance)
    );
  }

  signed(quantity: number): string {
    const q = Number(quantity);
    return q > 0 ? `+${q}` : `${q}`;
  }

  export(format: 'csv' | 'pdf'): void {
    if (!this.selectedPharmacyId || !this.periodValid()) return;
    this.exporting.set(true);
    this.svc
      .exportControlledRegister(
        format,
        this.selectedPharmacyId,
        this.from,
        this.to,
        this.selectedItemId || undefined,
      )
      .subscribe({
        next: (blob) => {
          this.exporting.set(false);
          this.download(blob, `registre-stupefiants-${this.from}-${this.to}.${format}`);
        },
        error: () => {
          this.exporting.set(false);
          this.toast.error(this.translate.instant('PHARMACY.REGISTER_EXPORT_FAILED'));
        },
      });
  }

  private download(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }
}

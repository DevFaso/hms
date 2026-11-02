import { refusalReason } from '../shared/refusal-reason';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
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

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { FieldRefusal, refuseFields } from '../shared/forms/invalid-fields';
import { missingRequiredMessage } from '../shared/required-fields';
import { DashPipe } from '../shared/pipes/dash.pipe';

/** The period's two date inputs. */
const REGISTER_PERIOD_IDS = ['register-from', 'register-to'];
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
  imports: [
    DashPipe,
    SubmitGuardDirective,
    CommonModule,
    FormsModule,
    TranslateModule,
    ServerTimePipe,
    EnumLabelPipe,
  ],
  templateUrl: './controlled-register.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './controlled-register.scss',
})
export class ControlledRegisterComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

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

  /**
   * QA D1337 (round 29): each refused date with its own reason — a blank
   * "Du" / "Au" is named as missing ("Champs obligatoires manquants : Du,
   * Au"), only two filled dates in the wrong order say "end before start".
   * Round 28 gave every case the period sentence.
   */
  private periodRefusals(): FieldRefusal[] {
    const required = this.translate.instant('COMMON.FIELD_REQUIRED');
    const blank: FieldRefusal[] = [];
    if (!this.from) blank.push({ id: 'register-from', message: required });
    if (!this.to) blank.push({ id: 'register-to', message: required });
    if (blank.length > 0) return blank;
    return this.from > this.to
      ? [{ id: 'register-to', message: this.translate.instant('PHARMACY.REGISTER_PERIOD_INVALID') }]
      : [];
  }

  /**
   * Why an export refuses the period (QA D1337: a blank date is named as
   * missing, not as "end before start"), or `false` when it can be read.
   */
  periodReason(): string | false {
    const refusals = this.periodRefusals();
    if (refusals.length === 0) return false;
    const missing = refusals.some((r) => (r.id === 'register-from' ? !this.from : !this.to));
    return missing ? this.periodMessage(refusals) : this.translate.instant('SUBMIT_GUARD.PERIOD');
  }

  /** The sentence for the refused dates: the missing ones by label, else the period rule. */
  private periodMessage(refusals: readonly FieldRefusal[]): string {
    const missing = missingRequiredMessage(this.translate, [
      { labelKey: 'PHARMACY.REGISTER_FROM', value: this.from },
      { labelKey: 'PHARMACY.REGISTER_TO', value: this.to },
    ]);
    return missing ?? refusals[0]?.message ?? '';
  }

  load(): void {
    if (!this.selectedPharmacyId) return;
    const refusals = this.periodRefusals();
    if (refusals.length > 0) {
      // QA D1306 (round 28): the wrong date is marked, not only a toast.
      refuseFields(this.host.nativeElement, refusals, REGISTER_PERIOD_IDS);
      this.toast.error(this.periodMessage(refusals));
      return;
    }
    refuseFields(this.host.nativeElement, [], REGISTER_PERIOD_IDS, '');
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
            refusalReason(err) ?? this.translate.instant('PHARMACY.REGISTER_LOAD_FAILED'),
          );
        },
      });
  }

  /** The shelf differs from what the register carries out: for the inspector to see. */
  hasDiscrepancy(drug: ControlledRegisterDrug): boolean {
    return (
      drug.closingBalance != null &&
      drug.quantityOnHand != null &&
      Number(drug.quantityOnHand) !== Number(drug.closingBalance)
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
          this.download(
            blob,
            `${this.translate.instant('PHARMACY.REGISTER_FILE_STEM')}-${this.from}-${this.to}.${format}`,
          );
        },
        error: (err: unknown) => {
          this.exporting.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('PHARMACY.REGISTER_EXPORT_FAILED'),
          );
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

import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import { allRows } from '../shared/all-pages';
import { refusalReason, refusedFieldReasons } from '../shared/refusal-reason';
import { saveBlob } from '../shared/download-file';
import { refuseFields } from '../shared/forms/invalid-fields';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
import { CfaPipe } from '../shared/pipes/cfa.pipe';
import { KeyLabelPipe } from '../shared/pipes/key-label.pipe';
import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { DashPipe } from '../shared/pipes/dash.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { PharmacyResponse, PharmacyService } from '../services/pharmacy.service';
import {
  PharmacyMoneyService,
  PharmacyReconciliation,
  PharmacyRefund,
} from '../services/pharmacy-money.service';

/** ISO date (YYYY-MM-DD) in the browser's calendar. */
function localIsoDate(d: Date): string {
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

/**
 * QA D2673 (r65, billing plan PR 13, scorecard M4): a pharmacy's day, money against stock. The server lists
 * every line that does not reconcile; this page shows the money, the stock per medication and lot with the
 * differences highlighted, the controlled balances, the fills handed over « à facturer », and exports the day.
 * QA D2676: the open refunds of patient returns are decided (another admin or accountant) and paid out here.
 */
@Component({
  selector: 'app-pharmacy-reconciliation',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    CfaPipe,
    DashPipe,
    ServerTimePipe,
    KeyLabelPipe,
    SubmitGuardDirective,
  ],
  templateUrl: './pharmacy-reconciliation.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './pharmacy-reconciliation.scss',
})
export class PharmacyReconciliationComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly money = inject(PharmacyMoneyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly confirm = inject(ConfirmService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly pharmacies = signal<PharmacyResponse[]>([]);
  readonly pharmaciesLoaded = signal(false);
  readonly selectedPharmacyId = signal('');
  readonly date = signal(localIsoDate(new Date()));
  readonly day = signal<PharmacyReconciliation | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly exporting = signal(false);

  readonly refunds = signal<PharmacyRefund[]>([]);
  readonly refundsFailed = signal(false);
  readonly refundBusy = signal<string | null>(null);
  /** The refund being rejected, and its reason. */
  readonly rejecting = signal<string | null>(null);
  rejectReason = '';

  readonly reconciled = computed(() => (this.day()?.differences.length ?? 1) === 0);
  readonly salesMethods = computed(() => Object.entries(this.day()?.money.salesByMethod ?? {}));
  readonly paymentMethods = computed(() =>
    Object.entries(this.day()?.money.dispensePaymentsByMethod ?? {}),
  );

  private loadSeq = 0;

  ngOnInit(): void {
    allRows((page, size) => this.svc.listPharmacies(page, size)).subscribe({
      next: (list) => {
        this.pharmacies.set(list);
        this.pharmaciesLoaded.set(true);
        if (list.length > 0) {
          this.selectedPharmacyId.set(list[0].id);
          this.load();
        }
      },
      error: () => this.pharmaciesLoaded.set(true),
    });
    this.loadRefunds();
  }

  onPharmacyChange(id: string): void {
    this.selectedPharmacyId.set(id);
    this.load();
  }

  onDateChange(value: string): void {
    this.date.set(value);
    this.load();
  }

  load(): void {
    const pharmacyId = this.selectedPharmacyId();
    const date = this.date();
    if (!pharmacyId || !date) return;
    const seq = ++this.loadSeq;
    this.loading.set(true);
    this.failed.set(false);
    this.money.reconciliation(pharmacyId, date).subscribe({
      next: (res) => {
        if (seq !== this.loadSeq) return;
        this.day.set(res?.data ?? null);
        this.loading.set(false);
      },
      error: () => {
        if (seq !== this.loadSeq) return;
        this.day.set(null);
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }

  exportCsv(): void {
    const pharmacyId = this.selectedPharmacyId();
    const date = this.date();
    if (!pharmacyId || !date) return;
    this.exporting.set(true);
    this.money.reconciliationExport(pharmacyId, date).subscribe({
      next: (blob) => {
        saveBlob(blob, `rapprochement-pharmacie-${date}.csv`);
        this.exporting.set(false);
      },
      error: (err) => {
        this.exporting.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PHARMACY_MONEY.RECON.EXPORT_FAILED'),
        );
      },
    });
  }

  loadRefunds(): void {
    this.refundsFailed.set(false);
    this.money.refunds(['REQUESTED', 'APPROVED']).subscribe({
      next: (res) => this.refunds.set(res?.data ?? []),
      error: () => {
        this.refunds.set([]);
        this.refundsFailed.set(true);
      },
    });
  }

  approve(refund: PharmacyRefund): void {
    this.refundBusy.set(refund.id);
    this.money
      .approveRefund(refund.id)
      .subscribe(this.afterRefundStep('PHARMACY_MONEY.REFUND.APPROVED'));
  }

  startReject(refund: PharmacyRefund): void {
    this.rejecting.set(refund.id);
    this.rejectReason = '';
  }

  async confirmReject(refund: PharmacyRefund): Promise<void> {
    const reason = this.rejectReason.trim();
    if (!reason) {
      const id = 'refundRejectReason';
      const message = this.translate.instant('PHARMACY_MONEY.REFUND.REASON_REQUIRED');
      refuseFields(this.host.nativeElement, [id], [id], message);
      this.toast.error(message);
      return;
    }
    const ok = await this.confirm.ask({
      titleKey: 'PHARMACY_MONEY.REFUND.REJECT_CONFIRM',
      confirmKey: 'PHARMACY_MONEY.REFUND.REJECT',
    });
    if (!ok) return;
    this.refundBusy.set(refund.id);
    this.money.rejectRefund(refund.id, reason).subscribe({
      next: this.afterRefundStep('PHARMACY_MONEY.REFUND.REJECTED').next,
      error: (err: unknown) => {
        this.refundBusy.set(null);
        // A 400 that names the reason marks the field under it.
        const idByField = { reason: 'refundRejectReason' };
        const refused = refusedFieldReasons(err, idByField);
        if (refused.length > 0) {
          refuseFields(
            this.host.nativeElement,
            refused.map(([id, message]) => ({ id, message })),
            Object.values(idByField),
          );
        }
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PHARMACY_MONEY.REFUND.FAILED'),
        );
      },
    });
  }

  pay(refund: PharmacyRefund): void {
    this.refundBusy.set(refund.id);
    this.money.payRefund(refund.id).subscribe(this.afterRefundStep('PHARMACY_MONEY.REFUND.PAID'));
  }

  private afterRefundStep(successKey: string) {
    return {
      next: () => {
        this.refundBusy.set(null);
        this.rejecting.set(null);
        this.toast.success(this.translate.instant(successKey));
        this.loadRefunds();
        this.load();
      },
      error: (err: unknown) => {
        this.refundBusy.set(null);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PHARMACY_MONEY.REFUND.FAILED'),
        );
      },
    };
  }
}

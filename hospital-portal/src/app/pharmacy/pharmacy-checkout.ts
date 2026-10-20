import { Component, OnInit, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule, DatePipe, CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  PharmacyService,
  DispenseResponse,
  PharmacyPaymentMethod,
  PharmacyPaymentRequest,
  PharmacyPaymentResponse,
  PharmacyResponse,
} from '../services/pharmacy.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';

/**
 * T-43 / T-44: pharmacy checkout and French printable receipt.
 *
 * Pharmacist picks the dispense from the pharmacy's recent fills (QA D416:
 * no more typing a dispense UUID), selects payment method (Espèces / Mobile Money / Assurance),
 * posts to the backend, and the printable receipt slides in. Patient-facing text
 * is in French and is hardcoded in the template for now; moving copy to
 * ngx-translate keys is a follow-up task.
 */
@Component({
  selector: 'app-pharmacy-checkout',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, DatePipe, CurrencyPipe],
  templateUrl: './pharmacy-checkout.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './pharmacy-checkout.scss',
})
export class PharmacyCheckoutComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  dispenseId = '';
  amount: number | null = null;
  paymentMethod: PharmacyPaymentMethod = 'CASH';
  referenceNumber = '';
  notes = '';

  loading = signal(false);
  /** QA D416: the cashier picks a fill from the pharmacy's list. */
  pharmacies = signal<PharmacyResponse[]>([]);
  selectedPharmacyId = signal('');
  dispenses = signal<DispenseResponse[]>([]);
  dispensesLoading = signal(false);
  dispense = signal<DispenseResponse | null>(null);
  receipt = signal<PharmacyPaymentResponse | null>(null);

  readonly methods: { value: PharmacyPaymentMethod; labelKey: string }[] = [
    { value: 'CASH', labelKey: 'PORTAL.ENUM.PAYMENT_METHOD.CASH' },
    { value: 'MOBILE_MONEY', labelKey: 'PORTAL.ENUM.PAYMENT_METHOD.MOBILE_MONEY' },
    { value: 'INSURANCE', labelKey: 'PORTAL.ENUM.PAYMENT_METHOD.INSURANCE' },
  ];

  ngOnInit(): void {
    this.loadPharmacies();
  }

  private loadPharmacies(): void {
    this.svc.listPharmacies(0, 100).subscribe({
      next: (page) => {
        const list = page?.content ?? [];
        this.pharmacies.set(list);
        if (list.length > 0) {
          this.selectPharmacy(list[0].id);
        }
      },
      error: () => this.toast.error(this.translate.instant('PHARMACY.PHARMACIES_LOAD_FAILED')),
    });
  }

  selectPharmacy(id: string): void {
    this.selectedPharmacyId.set(id);
    this.dispenseId = '';
    this.dispense.set(null);
    this.loadDispenses();
  }

  /** QA D416: the pharmacy's recent fills that can still be paid (a cancelled one cannot). */
  loadDispenses(): void {
    const pharmacyId = this.selectedPharmacyId();
    if (!pharmacyId) {
      this.dispenses.set([]);
      return;
    }
    this.dispensesLoading.set(true);
    this.svc.listDispensesByPharmacy(pharmacyId, 0, 50).subscribe({
      next: (res) => {
        const rows = res?.data?.content ?? [];
        this.dispenses.set(rows.filter((d) => d.status !== 'CANCELLED'));
        this.dispensesLoading.set(false);
      },
      error: () => {
        this.dispenses.set([]);
        this.dispensesLoading.set(false);
        this.toast.error(this.translate.instant('PHARMACY_CHECKOUT.DISPENSES_LOAD_FAILED'));
      },
    });
  }

  /** QA D416: picking a fill loads it, priced by the server. */
  pickDispense(id: string): void {
    this.dispenseId = id;
    if (!id) {
      this.dispense.set(null);
      this.amount = null;
      return;
    }
    this.loadDispense();
  }

  /** QA D264: the server priced this dispense, so the amount is fixed. */
  hasCharge(): boolean {
    const charge = this.dispense()?.chargeAmount;
    return charge !== null && charge !== undefined;
  }

  loadDispense(): void {
    if (!this.dispenseId) {
      this.toast.error(this.translate.instant('PHARMACY.CHECKOUT_DISPENSE_ID_REQUIRED'));
      return;
    }
    this.loading.set(true);
    this.svc.getDispense(this.dispenseId).subscribe({
      next: (res) => {
        const loaded = res?.data ?? null;
        this.dispense.set(loaded);
        // QA D264: the amount is the dispense's charge, never a typed figure.
        this.amount = loaded?.chargeAmount ?? null;
        this.loading.set(false);
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('PHARMACY.CHECKOUT_DISPENSE_NOT_FOUND'),
        );
        this.dispense.set(null);
        this.loading.set(false);
      },
    });
  }

  submit(): void {
    const d = this.dispense();
    if (!d) {
      this.toast.error(this.translate.instant('PHARMACY.CHECKOUT_LOAD_DISPENSE_FIRST'));
      return;
    }
    if (!this.amount || this.amount <= 0) {
      this.toast.error(this.translate.instant('PHARMACY.CHECKOUT_INVALID_AMOUNT'));
      return;
    }
    // QA D204: the franc CFA has no minor unit.
    if (!Number.isInteger(this.amount)) {
      this.toast.error(this.translate.instant('BILLING.PAYMENT_WHOLE_AMOUNT'));
      return;
    }
    const hospitalId = this.auth.getHospitalId();
    const userId = this.auth.getUserId();
    if (!hospitalId || !userId) {
      this.toast.error(this.translate.instant('PHARMACY.CHECKOUT_USER_OR_HOSPITAL_MISSING'));
      return;
    }

    const req: PharmacyPaymentRequest = {
      dispenseId: d.id,
      patientId: d.patientId,
      hospitalId,
      paymentMethod: this.paymentMethod,
      amount: this.amount,
      currency: 'XOF',
      referenceNumber: this.referenceNumber || undefined,
      receivedBy: userId,
      notes: this.notes || undefined,
    };

    this.loading.set(true);
    this.svc.createPayment(req).subscribe({
      next: (res) => {
        this.receipt.set(res?.data ?? null);
        this.toast.success(this.translate.instant('PHARMACY.PAYMENT_RECORDED'));
        this.loading.set(false);
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? this.translate.instant('PHARMACY.PAYMENT_FAILED'));
        this.loading.set(false);
      },
    });
  }

  printReceipt(): void {
    window.print();
  }

  reset(): void {
    this.loadDispenses();
    this.dispenseId = '';
    this.amount = null;
    this.paymentMethod = 'CASH';
    this.referenceNumber = '';
    this.notes = '';
    this.dispense.set(null);
    this.receipt.set(null);
  }

  /** A short receipt reference instead of the raw payment UUID (QA D102). */
  receiptNumber(id: string | null | undefined): string {
    return (id ?? '').replace(/-/g, '').substring(0, 10).toUpperCase();
  }

  methodLabel(m: PharmacyPaymentMethod): string {
    const key = this.methods.find((x) => x.value === m)?.labelKey;
    return key ? this.translate.instant(key) : m;
  }
}

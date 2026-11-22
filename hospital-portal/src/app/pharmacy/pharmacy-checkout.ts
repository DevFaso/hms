import { map } from 'rxjs';
import { allRows } from '../shared/all-pages';
import { refusalReason } from '../shared/refusal-reason';
import {
  newPaymentRequestKey,
  paymentOutcomeUnknown,
  PAYMENT_OUTCOME_UNKNOWN_KEY,
  retryPaymentWithSameKey,
} from '../shared/payment-request-key';
import {
  Component,
  ElementRef,
  OnInit,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
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
import { CfaPipe } from '../shared/pipes/cfa.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { cfaAmountMessageKey, cfaAmountProblem } from '../shared/cfa-amount';
import { FieldRefusal, refuseFields } from '../shared/forms/invalid-fields';
import {
  MOBILE_MONEY_OPERATORS,
  MobileMoneyOperator,
  containsCardNumber,
  mobileMoneyOperatorLabelKey,
} from '../shared/mobile-money-operator';

/** The payment fields the checkout may refuse. */
const CHECKOUT_FIELDS = ['amount', 'operator', 'ref'];

/**
 * T-43 / T-44: pharmacy checkout and French printable receipt.
 *
 * Pharmacist picks the dispense from the pharmacy's recent fills (QA D416:
 * no more typing a dispense UUID), selects payment method (Espèces / Mobile Money / Assurance),
 * posts to the backend, and the printable receipt slides in. Patient-facing text
 * is in French and is hardcoded in the template for now; moving copy to
 * ngx-translate keys is a follow-up task.
 */
/** The fill picker, refused when blank (QA D1306). */
const DISPENSE_PICK_IDS = ['dispenseId'];

/**
 * What the pharmacy checkout takes. QA D1611 (round 44): never INSURANCE. It
 * printed "Total payé" and counted the insurer's share as collected, with no
 * claim to the payer; the server refuses it now. The insurer's share of a
 * dispense is a pharmacy claim, not a checkout payment.
 */
export const PHARMACY_CHECKOUT_METHODS: readonly {
  value: PharmacyPaymentMethod;
  labelKey: string;
}[] = [
  { value: 'CASH', labelKey: 'PORTAL.ENUM.PAYMENT_METHOD.CASH' },
  { value: 'MOBILE_MONEY', labelKey: 'PORTAL.ENUM.PAYMENT_METHOD.MOBILE_MONEY' },
];

@Component({
  selector: 'app-pharmacy-checkout',
  standalone: true,
  imports: [ServerTimePipe, CommonModule, FormsModule, TranslateModule, DatePipe, CfaPipe],
  templateUrl: './pharmacy-checkout.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './pharmacy-checkout.scss',
})
export class PharmacyCheckoutComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  dispenseId = '';
  amount: number | null = null;
  paymentMethod: PharmacyPaymentMethod = 'CASH';
  referenceNumber = '';
  /** Billing plan PR 9: Orange Money and Moov Money are totalled apart at the close. */
  mobileMoneyOperator: MobileMoneyOperator | null = null;
  readonly mobileMoneyOperators = MOBILE_MONEY_OPERATORS;
  readonly operatorLabelKey = mobileMoneyOperatorLabelKey;
  notes = '';

  loading = signal(false);
  /**
   * Billing plan PR 11 (QA D2430): one key per dispense loaded for checkout, sent with every press, so a
   * payment whose answer was lost is answered again, not recorded twice. Kept while the same dispense stays.
   */
  paymentKey: string | undefined;
  private keyedDispenseId: string | null = null;
  /** QA D416: the cashier picks a fill from the pharmacy's list. */
  pharmacies = signal<PharmacyResponse[]>([]);
  selectedPharmacyId = signal('');
  dispenses = signal<DispenseResponse[]>([]);
  dispensesLoading = signal(false);
  dispense = signal<DispenseResponse | null>(null);
  receipt = signal<PharmacyPaymentResponse | null>(null);

  readonly methods = PHARMACY_CHECKOUT_METHODS;

  ngOnInit(): void {
    this.loadPharmacies();
  }

  private loadPharmacies(): void {
    // QA D1347: every pharmacy (all pages), not the first 100.
    allRows((page, size) => this.svc.listPharmacies(page, size)).subscribe({
      next: (list) => {
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

  /**
   * QA D416: the pharmacy's recent fills that can still be paid. A cancelled
   * one cannot be, and (QA D843) a paid one is not offered again: the server
   * already refuses a second charge, the cashier must not be shown one.
   */
  loadDispenses(): void {
    const pharmacyId = this.selectedPharmacyId();
    if (!pharmacyId) {
      this.dispenses.set([]);
      return;
    }
    this.dispensesLoading.set(true);
    // QA D1347: every dispense of the pharmacy (all pages), not the 50 newest:
    // an older unpaid dispense was never offered at the till.
    allRows((page, size) =>
      this.svc.listDispensesByPharmacy(pharmacyId, page, size).pipe(map((r) => r?.data)),
    ).subscribe({
      next: (rows) => {
        this.dispenses.set(rows.filter((d) => d.status !== 'CANCELLED' && d.paid !== true));
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

  /** QA D843: this dispense already carries a payment; nothing is taken twice. */
  isPaid(): boolean {
    return this.dispense()?.paid === true;
  }

  /** QA D264: the server priced this dispense, so the amount is fixed. */
  hasCharge(): boolean {
    const charge = this.dispense()?.chargeAmount;
    return charge !== null && charge !== undefined;
  }

  loadDispense(): void {
    if (!this.dispenseId) {
      // QA D1306 (round 28): the fill picker is marked, not only a toast.
      const message = this.translate.instant('PHARMACY.CHECKOUT_DISPENSE_ID_REQUIRED');
      refuseFields(this.host.nativeElement, DISPENSE_PICK_IDS, DISPENSE_PICK_IDS, message);
      this.toast.error(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], DISPENSE_PICK_IDS, '');
    this.loading.set(true);
    this.svc.getDispense(this.dispenseId).subscribe({
      next: (res) => {
        const loaded = res?.data ?? null;
        if ((loaded?.id ?? null) !== this.keyedDispenseId) {
          this.keyedDispenseId = loaded?.id ?? null;
          this.paymentKey = loaded ? newPaymentRequestKey() : undefined;
        }
        this.dispense.set(loaded);
        // QA D264: the amount is the dispense's charge, never a typed figure.
        // QA D3408 (r70): with an insurer share, the patient pays the rest only.
        this.amount = loaded?.patientShareAmount ?? loaded?.chargeAmount ?? null;
        this.loading.set(false);
      },
      error: (err) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PHARMACY.CHECKOUT_DISPENSE_NOT_FOUND'),
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
    if (d.paid === true) {
      this.toast.error(this.translate.instant('PHARMACY_CHECKOUT.ALREADY_PAID'));
      return;
    }
    // QA D204 / D1089: an amount first, then whole francs (the franc CFA has no minor unit).
    const amount = this.amount;
    const amountProblem = cfaAmountProblem(amount);
    // QA D1306 (round 28): every refused field is marked (aria-invalid, its
    // own reason under it, focus on the first) in one press; a toast alone
    // named none of them.
    const refusals: FieldRefusal[] = [];
    if (amountProblem || amount === null) {
      refusals.push({
        id: 'amount',
        message: this.translate.instant(
          cfaAmountMessageKey(amountProblem ?? 'required', 'PHARMACY.CHECKOUT_INVALID_AMOUNT'),
        ),
      });
    }
    // QA D1033: a mobile-money payment is recorded with the transaction
    // reference the patient's phone shows; the server refuses one without it.
    if (this.paymentMethod === 'MOBILE_MONEY' && !this.mobileMoneyOperator) {
      refusals.push({
        id: 'operator',
        message: this.translate.instant('MOBILE_MONEY.OPERATOR_REQUIRED'),
      });
    }
    if (this.paymentMethod === 'MOBILE_MONEY' && !this.referenceNumber.trim()) {
      refusals.push({
        id: 'ref',
        message: this.translate.instant('PHARMACY_CHECKOUT.REFERENCE_REQUIRED'),
      });
    } else if (containsCardNumber(this.referenceNumber)) {
      // Owner decision D-15: never a card number (the server refuses it too).
      refusals.push({
        id: 'ref',
        message: this.translate.instant('MOBILE_MONEY.CARD_NUMBER_REFUSED'),
      });
    }
    if (refusals.length > 0 || amount === null) {
      refuseFields(this.host.nativeElement, refusals, CHECKOUT_FIELDS);
      this.toast.error(refusals.map((r) => r.message).join(' '));
      return;
    }
    refuseFields(this.host.nativeElement, [], CHECKOUT_FIELDS);
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
      amount,
      currency: 'XOF',
      referenceNumber: this.referenceNumber.trim() || undefined,
      ...(this.paymentMethod === 'MOBILE_MONEY' && this.mobileMoneyOperator
        ? { mobileMoneyOperator: this.mobileMoneyOperator }
        : {}),
      receivedBy: userId,
      notes: this.notes || undefined,
      ...(this.paymentKey ? { clientRequestId: this.paymentKey } : {}),
    };

    this.loading.set(true);
    this.svc
      .createPayment(req)
      .pipe(retryPaymentWithSameKey(this.paymentKey))
      .subscribe({
        next: (res) => {
          this.receipt.set(res?.data ?? null);
          this.toast.success(this.translate.instant('PHARMACY.PAYMENT_RECORDED'));
          this.loading.set(false);
        },
        error: (err) => {
          if (paymentOutcomeUnknown(err)) {
            // QA D2430: no answer is not a failure; pressing again with the same key records nothing twice.
            this.toast.warning(this.translate.instant(PAYMENT_OUTCOME_UNKNOWN_KEY));
          } else {
            this.toast.error(
              refusalReason(err) ?? this.translate.instant('PHARMACY.PAYMENT_FAILED'),
            );
          }
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
    this.mobileMoneyOperator = null;
    this.notes = '';
    this.dispense.set(null);
    this.receipt.set(null);
  }

  methodLabel(m: PharmacyPaymentMethod): string {
    const key = this.methods.find((x) => x.value === m)?.labelKey;
    return key ? this.translate.instant(key) : m;
  }
}

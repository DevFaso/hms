import { Component, inject, signal } from '@angular/core';
import { CommonModule, DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import {
  PharmacyService,
  DispenseResponse,
  PharmacyPaymentMethod,
  PharmacyPaymentRequest,
  PharmacyPaymentResponse,
} from '../services/pharmacy.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';

/**
 * T-43 / T-44: pharmacy checkout and French printable receipt.
 *
 * Pharmacist enters a dispense ID, selects payment method (Espèces / Mobile Money / Assurance),
 * posts to the backend, and the printable receipt slides in. Patient-facing text
 * is in French and is hardcoded in the template for now; moving copy to
 * ngx-translate keys is a follow-up task.
 */
@Component({
  selector: 'app-pharmacy-checkout',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, DatePipe, DecimalPipe],
  templateUrl: './pharmacy-checkout.html',
  styleUrl: './pharmacy-checkout.scss',
})
export class PharmacyCheckoutComponent {
  private readonly svc = inject(PharmacyService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);

  dispenseId = '';
  amount: number | null = null;
  paymentMethod: PharmacyPaymentMethod = 'CASH';
  referenceNumber = '';
  notes = '';

  loading = signal(false);
  dispense = signal<DispenseResponse | null>(null);
  receipt = signal<PharmacyPaymentResponse | null>(null);

  readonly methods: { value: PharmacyPaymentMethod; labelFr: string }[] = [
    { value: 'CASH', labelFr: 'Espèces' },
    { value: 'MOBILE_MONEY', labelFr: 'Mobile Money' },
    { value: 'INSURANCE', labelFr: 'Assurance' },
  ];

  loadDispense(): void {
    if (!this.dispenseId) {
      this.toast.error('Dispense ID requis');
      return;
    }
    this.loading.set(true);
    this.svc.getDispense(this.dispenseId).subscribe({
      next: (res) => {
        this.dispense.set(res?.data ?? null);
        this.loading.set(false);
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Dispense introuvable');
        this.dispense.set(null);
        this.loading.set(false);
      },
    });
  }

  submit(): void {
    const d = this.dispense();
    if (!d) {
      this.toast.error('Veuillez charger un dispense');
      return;
    }
    if (!this.amount || this.amount <= 0) {
      this.toast.error('Montant invalide');
      return;
    }
    const hospitalId = this.auth.getHospitalId();
    const userId = this.auth.getUserId();
    if (!hospitalId || !userId) {
      this.toast.error('Utilisateur ou hôpital introuvable');
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
        this.toast.success('Paiement enregistré');
        this.loading.set(false);
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Échec du paiement');
        this.loading.set(false);
      },
    });
  }

  printReceipt(): void {
    window.print();
  }

  reset(): void {
    this.dispenseId = '';
    this.amount = null;
    this.paymentMethod = 'CASH';
    this.referenceNumber = '';
    this.notes = '';
    this.dispense.set(null);
    this.receipt.set(null);
  }

  methodLabel(m: PharmacyPaymentMethod): string {
    return this.methods.find((x) => x.value === m)?.labelFr ?? m;
  }
}

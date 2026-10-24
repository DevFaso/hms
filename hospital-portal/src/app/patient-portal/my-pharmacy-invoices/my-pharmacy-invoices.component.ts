import { Component, OnInit, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EnumLabelService } from '../../core/enum-label.service';
import {
  PharmacyClaimResponse,
  PharmacyService,
  PharmacyPaymentResponse,
} from '../../services/pharmacy.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

/**
 * T-45: patient portal — pharmacy invoices & payment history.
 *
 * Lists the patient's pharmacy payments (cash / mobile-money / insurance),
 * most recent first, with running total paid. Patient-facing copy is French.
 */
@Component({
  selector: 'app-my-pharmacy-invoices',
  standalone: true,
  imports: [ServerTimePipe, CommonModule, TranslateModule, DatePipe, CfaPipe],
  templateUrl: './my-pharmacy-invoices.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./my-pharmacy-invoices.component.scss', '../patient-portal-pages.scss'],
})
export class MyPharmacyInvoicesComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly translate = inject(TranslateService);
  private readonly enumLabels = inject(EnumLabelService);

  payments = signal<PharmacyPaymentResponse[]>([]);
  claims = signal<PharmacyClaimResponse[]>([]);
  loading = signal(true);
  totalPaid = signal(0);
  totalClaimed = signal(0);

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    // Backend resolves the patient from the JWT (/me/patient/pharmacy/payments),
    // so no patient UUID is sent from the client (prevents IDOR).
    this.svc.listMyPharmacyPayments(0, 50).subscribe({
      next: (res) => {
        const items = res?.data?.content ?? [];
        this.payments.set(items);
        // QA D404: a payment voided with its cancelled dispense is not counted.
        this.totalPaid.set(
          items.filter((p) => p.status !== 'VOIDED').reduce((sum, p) => sum + (p.amount ?? 0), 0),
        );
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });

    this.svc.listMyPharmacyClaims(0, 50).subscribe({
      next: (res) => {
        const items = res?.data?.content ?? [];
        this.claims.set(items);
        this.totalClaimed.set(items.reduce((sum, c) => sum + (c.amount ?? 0), 0));
      },
      error: () => undefined,
    });
  }

  methodLabel(m: string): string {
    return this.enumLabels.transform(m, 'paymentMethod');
  }

  statusLabel(status: string): string {
    const key = `PHARMACY_CLAIMS.STATUS.${status}`;
    const label = this.translate.instant(key);
    return label === key ? this.enumLabels.transform(status) : label;
  }
}

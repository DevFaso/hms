import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe, DecimalPipe } from '@angular/common';
import { PatientPortalService } from '../../services/patient-portal.service';
import { PharmacyService, PharmacyPaymentResponse } from '../../services/pharmacy.service';

/**
 * T-45: patient portal — pharmacy invoices & payment history.
 *
 * Lists the patient's pharmacy payments (cash / mobile-money / insurance),
 * most recent first, with running total paid. Patient-facing copy is French.
 */
@Component({
  selector: 'app-my-pharmacy-invoices',
  standalone: true,
  imports: [CommonModule, DatePipe, DecimalPipe],
  templateUrl: './my-pharmacy-invoices.component.html',
  styleUrls: ['./my-pharmacy-invoices.component.scss', '../patient-portal-pages.scss'],
})
export class MyPharmacyInvoicesComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly portal = inject(PatientPortalService);

  payments = signal<PharmacyPaymentResponse[]>([]);
  loading = signal(true);
  totalPaid = signal(0);

  readonly methodLabels: Record<string, string> = {
    CASH: 'Espèces',
    MOBILE_MONEY: 'Mobile Money',
    INSURANCE: 'Assurance',
  };

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    // Resolve the real Patient UUID via the portal profile; AuthService.getUserId()
    // returns the User UUID which is distinct from the Patient entity id.
    this.portal.getMyProfile().subscribe({
      next: (profile) => {
        const patientId = profile?.patientId;
        if (!patientId) {
          this.loading.set(false);
          return;
        }
        this.svc.listPaymentsByPatient(patientId, 0, 50).subscribe({
          next: (res) => {
            const items = res?.data?.content ?? [];
            this.payments.set(items);
            this.totalPaid.set(items.reduce((sum, p) => sum + (p.amount ?? 0), 0));
            this.loading.set(false);
          },
          error: () => this.loading.set(false),
        });
      },
      error: () => this.loading.set(false),
    });
  }

  methodLabel(m: string): string {
    return this.methodLabels[m] ?? m;
  }
}

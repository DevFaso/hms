import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import {
  PharmacyService,
  StockCheckResult,
  PartnerOption,
  RoutingDecisionResponse,
} from '../services/pharmacy.service';

@Component({
  selector: 'app-stock-routing',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './stock-routing.html',
  styleUrl: './stock-routing.scss',
})
export class StockRoutingComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly route = inject(ActivatedRoute);

  // Prescription lookup
  prescriptionId = '';
  checking = signal(false);
  stockResult = signal<StockCheckResult | null>(null);

  ngOnInit(): void {
    // P-05: when navigated to via /pharmacy/stock-routing/:prescriptionId, skip the
    // manual lookup and run checkStock immediately. The plain /pharmacy/stock-routing
    // route still supports manual entry as a fallback.
    const idFromRoute = this.route.snapshot.paramMap.get('prescriptionId')?.trim();
    if (idFromRoute) {
      this.prescriptionId = idFromRoute;
      this.checkStock();
    }
  }

  // Routing decisions history
  decisions = signal<RoutingDecisionResponse[]>([]);
  decisionsLoading = signal(false);
  decisionsPage = 0;
  decisionsTotalPages = 0;

  // Partner routing form
  showPartnerForm = signal(false);
  selectedPartner = signal<PartnerOption | null>(null);
  routingReason = '';

  // Back-order form
  showBackOrderForm = signal(false);
  estimatedRestockDate = '';

  // Action state
  saving = signal(false);

  checkStock(): void {
    if (!this.prescriptionId.trim()) return;
    // Reset pagination whenever a new prescription is checked
    this.decisionsPage = 0;
    this.checking.set(true);
    this.stockResult.set(null);
    this.svc.checkStock(this.prescriptionId).subscribe({
      next: (res) => {
        this.stockResult.set(res.data);
        this.checking.set(false);
        this.loadDecisions();
      },
      error: (err) => {
        this.toast.error(this.errorMessage(err, 'PHARMACY.STOCK_CHECK_FAILED'));
        this.checking.set(false);
      },
    });
  }

  loadDecisions(): void {
    if (!this.prescriptionId.trim()) return;
    this.decisionsLoading.set(true);
    this.svc
      .listRoutingDecisionsByPrescription(this.prescriptionId, this.decisionsPage, 10)
      .subscribe({
        next: (res) => {
          this.decisions.set(res.data.content);
          this.decisionsTotalPages = res.data.totalPages;
          this.decisionsLoading.set(false);
        },
        error: () => this.decisionsLoading.set(false),
      });
  }

  // ── Route to partner ──

  openPartnerForm(partner: PartnerOption): void {
    this.selectedPartner.set(partner);
    this.routingReason = '';
    this.showPartnerForm.set(true);
  }

  closePartnerForm(): void {
    this.showPartnerForm.set(false);
    this.selectedPartner.set(null);
  }

  submitRouteToPartner(): void {
    const partner = this.selectedPartner();
    if (!partner) return;
    this.saving.set(true);
    this.svc
      .routeToPartner({
        prescriptionId: this.prescriptionId,
        routingType: 'PARTNER',
        targetPharmacyId: partner.pharmacyId,
        reason: this.routingReason || undefined,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('PHARMACY.ROUTED_TO_PARTNER'));
          this.saving.set(false);
          this.closePartnerForm();
          this.checkStock();
        },
        error: (err) => {
          this.toast.error(this.errorMessage(err, 'PHARMACY.ROUTING_FAILED'));
          this.saving.set(false);
        },
      });
  }

  // ── Print for patient (Tier 3) ──

  printForPatient(): void {
    this.saving.set(true);
    this.svc.printForPatient(this.prescriptionId).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.PRINTED_FOR_PATIENT'));
        this.saving.set(false);
        this.checkStock();
      },
      error: (err) => {
        this.toast.error(this.errorMessage(err, 'PHARMACY.ROUTING_FAILED'));
        this.saving.set(false);
      },
    });
  }

  // ── Back-order ──

  openBackOrderForm(): void {
    this.estimatedRestockDate = '';
    this.showBackOrderForm.set(true);
  }

  closeBackOrderForm(): void {
    this.showBackOrderForm.set(false);
  }

  submitBackOrder(): void {
    this.saving.set(true);
    this.svc.backOrder(this.prescriptionId, this.estimatedRestockDate || undefined).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.BACK_ORDER_PLACED'));
        this.saving.set(false);
        this.closeBackOrderForm();
        this.checkStock();
      },
      error: (err) => {
        this.toast.error(this.errorMessage(err, 'PHARMACY.ROUTING_FAILED'));
        this.saving.set(false);
      },
    });
  }

  // ── Partner response (accept/reject) ──

  respondToPartner(decisionId: string, accepted: boolean): void {
    this.saving.set(true);
    this.svc.partnerRespond(decisionId, accepted).subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(
            accepted ? 'PHARMACY.PARTNER_ACCEPTED' : 'PHARMACY.PARTNER_REJECTED',
          ),
        );
        this.saving.set(false);
        this.loadDecisions();
      },
      error: (err) => {
        this.toast.error(this.errorMessage(err, 'PHARMACY.ROUTING_FAILED'));
        this.saving.set(false);
      },
    });
  }

  confirmPartnerDispense(decisionId: string): void {
    this.saving.set(true);
    this.svc.confirmPartnerDispense(decisionId).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.PARTNER_DISPENSED'));
        this.saving.set(false);
        this.loadDecisions();
      },
      error: (err) => {
        this.toast.error(this.errorMessage(err, 'PHARMACY.ROUTING_FAILED'));
        this.saving.set(false);
      },
    });
  }

  /**
   * Prefer server-provided error text; fall back to a translated i18n key so
   * we never display raw keys like 'PHARMACY.ROUTING_FAILED' to the user.
   */
  private errorMessage(err: unknown, fallbackKey: string): string {
    const serverMessage = (err as { error?: { message?: string } })?.error?.message;
    return serverMessage || this.translate.instant(fallbackKey);
  }

  // ── Pagination ──

  prevPage(): void {
    if (this.decisionsPage > 0) {
      this.decisionsPage--;
      this.loadDecisions();
    }
  }

  nextPage(): void {
    if (this.decisionsPage < this.decisionsTotalPages - 1) {
      this.decisionsPage++;
      this.loadDecisions();
    }
  }

  // ── Helpers ──

  statusBadgeClass(status: string): string {
    switch (status) {
      case 'PENDING':
        return 'badge-warning';
      case 'ACCEPTED':
      case 'COMPLETED':
        return 'badge-success';
      case 'REJECTED':
      case 'CANCELLED':
        return 'badge-danger';
      default:
        return 'badge-info';
    }
  }

  routingTypeBadgeClass(type: string): string {
    switch (type) {
      case 'PARTNER':
        return 'badge-info';
      case 'PRINT':
        return 'badge-secondary';
      case 'BACKORDER':
        return 'badge-warning';
      default:
        return 'badge-info';
    }
  }
}

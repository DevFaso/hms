import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subject, of } from 'rxjs';
import { catchError, map, switchMap } from 'rxjs/operators';
import { ToastService } from '../core/toast.service';
import {
  PharmacyService,
  StockCheckResult,
  PartnerOption,
  RoutingDecisionResponse,
} from '../services/pharmacy.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeHintComponent } from '../shared/hospital-scope-chip/hospital-scope-hint.component';

@Component({
  selector: 'app-stock-routing',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, EnumLabelPipe, HospitalScopeHintComponent],
  templateUrl: './stock-routing.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './stock-routing.scss',
})
export class StockRoutingComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly route = inject(ActivatedRoute);
  private readonly roleContext = inject(RoleContextService);

  /**
   * Whether this caller has a hospital pinned, and therefore whether the
   * write controls on this page can do anything.
   *
   * <p>The reads on this screen answer for a super-admin in global view —
   * that is what the backend half of this PR fixed — but every WRITE behind
   * them (route to a partner, print for the patient, place a back order,
   * record a partner's answer) deliberately still refuses without a scope.
   * Rendering them live would turn one honest error into four buttons that
   * always fail. Same gate as the MTM review screen.
   */
  readonly scopeReady = this.roleContext.hasHospitalScope;

  // Prescription lookup
  prescriptionId = '';
  checking = signal(false);
  stockResult = signal<StockCheckResult | null>(null);

  /**
   * One in-flight history read at a time; a newer one cancels its predecessor.
   *
   * <p>Independent subscriptions raced, and this screen made that race
   * destructive: page forward, or re-check a different prescription, while an
   * earlier read is still out, and the earlier failure would land last —
   * clearing a history the newer request had already loaded and painting the
   * red panel over it. While the error handler only cleared the spinner the
   * race was harmless; it is not any more. Same shape as the lab release
   * worklist, and `catchError` sits INSIDE so a failure ends that attempt
   * rather than the stream.
   */
  private readonly decisionRequests = new Subject<boolean>();

  constructor() {
    this.decisionRequests
      .pipe(
        switchMap((load) =>
          // `false` is a cancel: switchMap drops whatever is in flight and
          // this emits nothing to act on. Clearing the field or checking a
          // different prescription has to CANCEL the old read, not merely
          // outrun it — otherwise the previous order's history lands under
          // the new id, which is what the reset in checkStock is for.
          load
            ? this.svc
                .listRoutingDecisionsByPrescription(this.prescriptionId, this.decisionsPage, 10)
                .pipe(
                  map((res) => ({ page: res.data, failed: false, cancelled: false })),
                  catchError(() => of({ page: null, failed: true, cancelled: false })),
                )
            : of({ page: null, failed: false, cancelled: true }),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(({ page, failed, cancelled }) => {
        if (cancelled) {
          return;
        }
        this.decisionsLoading.set(false);
        if (failed || !page) {
          // Never swallowed into an empty table: the history section is absent
          // when there is nothing recorded, so a failure that only cleared the
          // spinner read as "this order was never routed" — which is exactly
          // what a pharmacist decides the next route from.
          this.decisions.set([]);
          this.decisionsTotalPages = 0;
          this.decisionsError.set(true);
          return;
        }
        this.decisions.set(page.content);
        this.decisionsTotalPages = page.totalPages;
      });
  }

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
  decisionsError = signal(false);
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
    // The history belongs to the PREVIOUS prescription until this one answers.
    // Leaving its rows — or its red panel — on screen attributes one order's
    // routing to another, which is the worst thing this screen could say. Any
    // read still in flight for that previous order is cancelled outright:
    // clearing the signals is not enough if the old response is yet to land.
    this.decisionRequests.next(false);
    this.decisions.set([]);
    this.decisionsTotalPages = 0;
    this.decisionsLoading.set(false);
    this.decisionsError.set(false);
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
    if (!this.prescriptionId.trim()) {
      // Clear the failure too: leaving it would keep a red "could not be
      // loaded" panel — with a Retry that returns here and does nothing — on
      // screen for a prescription the user has just cleared.
      this.decisionRequests.next(false);
      this.decisions.set([]);
      this.decisionsTotalPages = 0;
      this.decisionsLoading.set(false);
      this.decisionsError.set(false);
      return;
    }
    this.decisionsLoading.set(true);
    this.decisionsError.set(false);
    this.decisionRequests.next(true);
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

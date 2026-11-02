import { personName } from '../shared/pipes/person-name.pipe';
import {
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
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
  WorkQueuePrescription,
} from '../services/pharmacy.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeHintComponent } from '../shared/hospital-scope-chip/hospital-scope-hint.component';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { PrintLabelService } from '../services/print-label.service';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { refusalReason } from '../shared/refusal-reason';
@Component({
  selector: 'app-stock-routing',
  standalone: true,
  imports: [
    SubmitGuardDirective,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    HospitalScopeHintComponent,
  ],
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
  /** QA D966: opens the printed copy for printing. */
  private readonly printService = inject(PrintLabelService);

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

  /**
   * QA D888: the page only offered a box for a raw prescription UUID, which
   * nobody at a counter has. The pharmacist now searches the orders waiting
   * at this hospital's dispensary (the work queue) by patient or medication
   * and picks one; the deep link from the dispensing queue still fills it in.
   */
  readonly queue = signal<WorkQueuePrescription[]>([]);
  readonly queueLoading = signal(false);
  readonly queueError = signal(false);
  /** Orders waiting in all, of which `queue()` holds the newest pages loaded. */
  readonly queueTotal = signal(0);
  readonly queueLoadingMore = signal(false);
  readonly queueMoreError = signal(false);
  /**
   * #963 review: the search runs over the pages loaded, newest first. When
   * the dispensary has more waiting orders than that, the page says so and
   * offers the older ones, instead of a "nothing matches" that is false.
   */
  readonly queuePartial = computed(() => this.queue().length < this.queueTotal());
  static readonly QUEUE_PAGE_SIZE = 100;
  /**
   * The most rows one read returns: the server's page-size ceiling (Spring
   * Data's default, 2000), which it clamps a larger size to silently.
   */
  static readonly QUEUE_MAX_WINDOW = 2000;
  /**
   * Newest first, with the id as the tiebreaker so the order is total. That
   * alone does not make offset pages safe: an order that leaves the queue
   * between two reads shifts every later row back by one, and the row that
   * was 101st slides onto a page already loaded. So "load older" never asks
   * for page N; it re-reads the whole window from the start in one request
   * (#963 review round 2), and one read is one consistent snapshot.
   */
  static readonly QUEUE_SORT = ['createdAt,desc', 'id,desc'];
  /** Rows the last read asked for: the newest `queueWindow` orders. */
  private queueWindow = StockRoutingComponent.QUEUE_PAGE_SIZE;
  /** True once the window reached the server's ceiling: nothing older can be read. */
  readonly queueAtLimit = signal(false);
  private queueSeq = 0;
  readonly rxSearch = signal('');
  readonly selected = signal<WorkQueuePrescription | null>(null);
  /** At most this many matches are listed; typing more narrows them. */
  static readonly MAX_MATCHES = 8;
  readonly matches = computed(() => {
    const term = this.rxSearch().trim().toLocaleLowerCase();
    if (!term) {
      return [];
    }
    return this.queue()
      .filter((rx) => this.searchText(rx).includes(term))
      .slice(0, StockRoutingComponent.MAX_MATCHES);
  });
  stockResult = signal<StockCheckResult | null>(null);

  /**
   * QA D913: "not enough" is not "none". A shelf holding 50 of the 60 the
   * order needs was labelled out of stock; only an empty shelf is a stock-out.
   */
  readonly stockStatus = computed<'IN_STOCK' | 'INSUFFICIENT' | 'OUT_OF_STOCK' | null>(() => {
    const result = this.stockResult();
    if (!result) return null;
    if (result.sufficient) return 'IN_STOCK';
    return Number(result.quantityOnHand) > 0 ? 'INSUFFICIENT' : 'OUT_OF_STOCK';
  });

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

  /**
   * The history pages, so it needs an order: the endpoint's derived query has
   * none of its own, and without one "page 2" is not a continuation of page 1
   * — a row can appear on both or on neither. Newest decision first (what the
   * backend itself treats as the current one), then the id, which is unique,
   * so two decisions stamped in the same instant still have one fixed order.
   */
  private static readonly DECISION_ORDER = ['decidedAt,desc', 'id,desc'];

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
                .listRoutingDecisionsByPrescription(
                  this.prescriptionId,
                  this.decisionsPage,
                  10,
                  StockRoutingComponent.DECISION_ORDER,
                )
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
    this.loadQueue();
  }

  /**
   * QA D888: the orders a pharmacist can pick from, newest first. The
   * endpoint's default order is oldest first, so without the explicit sort
   * a dispensary with more than a page of waiting orders could never find
   * its newest ones (#963 review).
   */
  loadQueue(): void {
    const seq = ++this.queueSeq;
    this.queueWindow = StockRoutingComponent.QUEUE_PAGE_SIZE;
    this.queueAtLimit.set(false);
    this.queueLoading.set(true);
    this.queueLoadingMore.set(false);
    this.queueError.set(false);
    this.queueMoreError.set(false);
    this.svc
      .getDispenseWorkQueue(
        0,
        StockRoutingComponent.QUEUE_PAGE_SIZE,
        'ALL',
        StockRoutingComponent.QUEUE_SORT,
      )
      .subscribe({
        next: (res) => {
          if (seq !== this.queueSeq) return;
          const rows = res?.data?.content ?? [];
          this.queue.set(rows);
          this.queueTotal.set(Math.max(res?.data?.totalElements ?? 0, rows.length));
          this.queueLoading.set(false);
          this.syncSelectedFromQueue();
        },
        error: () => {
          if (seq !== this.queueSeq) return;
          this.queue.set([]);
          this.queueTotal.set(0);
          this.queueLoading.set(false);
          this.queueError.set(true);
        },
      });
  }

  /**
   * The next hundred older waiting orders. Re-reads the newest
   * `queueWindow + 100` from the start in ONE request and replaces the list,
   * rather than fetching page N and appending: between two offset reads an
   * order can be dispensed, cancelled or routed out, and then the row that
   * was 101st lands on page 0, which is never read again — while the smaller
   * total made the search claim the whole queue was covered (#963 review
   * round 2). A single read cannot skip a row, and its total matches its rows.
   */
  loadOlderQueue(): void {
    if (
      this.queueLoading() ||
      this.queueLoadingMore() ||
      this.queueAtLimit() ||
      !this.queuePartial()
    ) {
      return;
    }
    const seq = this.queueSeq;
    const size = Math.min(
      this.queueWindow + StockRoutingComponent.QUEUE_PAGE_SIZE,
      StockRoutingComponent.QUEUE_MAX_WINDOW,
    );
    this.queueLoadingMore.set(true);
    this.queueMoreError.set(false);
    this.svc.getDispenseWorkQueue(0, size, 'ALL', StockRoutingComponent.QUEUE_SORT).subscribe({
      next: (res) => {
        if (seq !== this.queueSeq) return;
        const rows = res?.data?.content ?? [];
        this.queue.set(rows);
        this.queueWindow = size;
        this.queueTotal.set(Math.max(res?.data?.totalElements ?? 0, rows.length));
        this.queueAtLimit.set(size >= StockRoutingComponent.QUEUE_MAX_WINDOW);
        this.queueLoadingMore.set(false);
        this.syncSelectedFromQueue();
      },
      error: () => {
        if (seq !== this.queueSeq) return;
        this.queueLoadingMore.set(false);
        this.queueMoreError.set(true);
      },
    });
  }

  /** QA D888: picking an order runs the stock check for it. */
  pickPrescription(rx: WorkQueuePrescription): void {
    this.selected.set(rx);
    this.rxSearch.set('');
    this.prescriptionId = rx.id;
    this.checkStock();
  }

  /** Enter in the search box takes the first match, when there is one. */
  pickFirstMatch(): void {
    const first = this.matches()[0];
    if (first) {
      this.pickPrescription(first);
    }
  }

  patientLabel(rx: WorkQueuePrescription): string {
    return personName(rx.patient);
  }

  private searchText(rx: WorkQueuePrescription): string {
    return [this.patientLabel(rx), rx.medicationName, rx.dosage]
      .filter((p) => !!p)
      .join(' ')
      .toLocaleLowerCase();
  }

  /** A deep-linked order is named once the queue arrives, instead of shown as an id. */
  private syncSelectedFromQueue(): void {
    if (this.prescriptionId && !this.selected()) {
      this.selected.set(this.queue().find((rx) => rx.id === this.prescriptionId) ?? null);
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

  /**
   * QA D966: the decision is recorded, then the copy is opened for printing.
   * It only recorded the decision before, and said "printed" with nothing in
   * hand; the page then still offered the routing buttons, which failed.
   */
  printForPatient(): void {
    this.saving.set(true);
    const prescriptionId = this.prescriptionId;
    this.svc.printForPatient(prescriptionId).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.PRINTED_FOR_PATIENT'));
        this.saving.set(false);
        this.openPrintedCopy(prescriptionId);
        this.checkStock();
      },
      error: (err) => {
        this.toast.error(this.errorMessage(err, 'PHARMACY.ROUTING_FAILED'));
        this.saving.set(false);
      },
    });
  }

  /** QA D966: the copy of an order already printed for the patient, printed again. */
  reprintCopy(): void {
    this.openPrintedCopy(this.prescriptionId);
  }

  private openPrintedCopy(prescriptionId: string): void {
    this.svc.getPrintedPrescriptionCopy(prescriptionId).subscribe({
      next: (blob) => this.printService.openForPrint(blob),
      error: (err: unknown) =>
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PHARMACY.PRINT_COPY_FAILED'),
        ),
    });
  }

  /** QA D966: a routing action is offered only while the order can still be routed. */
  /**
   * QA D1124: why a routing decision refuses, shown next to its button; null
   * when it may be sent. The buttons are never silently disabled.
   */
  routeBlockReason(): string | null {
    if (!this.scopeReady()) return this.translate.instant('SUBMIT_GUARD.SCOPE');
    const result = this.stockResult();
    if (!result) return this.translate.instant('SUBMIT_GUARD.PRESCRIPTION');
    return result.routable === false ? this.translate.instant('SUBMIT_GUARD.NOT_ROUTABLE') : null;
  }

  canRoute(): boolean {
    const result = this.stockResult();
    return this.scopeReady() && !!result && result.routable !== false;
  }

  /** QA D966: the order was printed for the patient; its copy can be printed again. */
  isPrinted(): boolean {
    return this.stockResult()?.prescriptionStatus === 'PRINTED_FOR_PATIENT';
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

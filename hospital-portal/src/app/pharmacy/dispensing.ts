import {
  Component,
  computed,
  inject,
  ElementRef,
  OnDestroy,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { refuseFields } from '../shared/forms/invalid-fields';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { firstValueFrom, Subscription } from 'rxjs';
import { ToastService } from '../core/toast.service';
import {
  PharmacyService,
  PharmacyResponse,
  InventoryItemResponse,
  DispenseRequest,
  DispenseResponse,
  WorkQueuePrescription,
  RefillDecisionStatus,
  StockLotResponse,
  READY_CANCEL_REASONS,
  ReadyCancelReason,
  QueueClaimFilter,
  WorkQueueClaim,
  StockWitnessCandidate,
} from '../services/pharmacy.service';
import { AuthService } from '../auth/auth.service';
import { PrintLabelService } from '../services/print-label.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { OfflineDispenseQueueService } from './offline-dispense-queue.service';
import { PrescriptionClarificationComponent } from '../shared/prescription-clarification/prescription-clarification.component';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { PersonNamePipe } from '../shared/pipes/person-name.pipe';
import { serverInstant } from '../shared/date-utils';
import { DialogFocusDirective } from '../shared/a11y/dialog-focus.directive';
import { earlierLotWithStock, fefoAllocation, FefoPart, fefoFirstLot } from './fefo-lots';

/** The viewer's local calendar date as YYYY-MM-DD (not the UTC one). */
function localIsoDate(d: Date): string {
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

/**
 * Why a work-queue row is not a plain fill, keyed by the backend's
 * `attentionReason`. The projection reports ONE reason by precedence
 * (status, then an outstanding back order, then a clarification), so this
 * map is exhaustive over that single value — "the prescriber has answered"
 * is reported separately, on `clarificationResolvedAt`, because resolving a
 * clarification restores the status the question was asked from and would
 * otherwise be masked by it.
 *
 * `labelKey` is the field name on purpose — check-i18n-referenced-keys.mjs
 * reads it, so a typo fails the gate instead of rendering the raw key.
 */
export const QUEUE_ATTENTION_REASONS: readonly { reason: string; labelKey: string }[] = [
  { reason: 'PENDING_STOCK', labelKey: 'PHARMACY.ATTENTION.PENDING_STOCK' },
  { reason: 'PARTNER_REJECTED', labelKey: 'PHARMACY.ATTENTION.PARTNER_REJECTED' },
  { reason: 'TRANSMISSION_FAILED', labelKey: 'PHARMACY.ATTENTION.TRANSMISSION_FAILED' },
  { reason: 'PARTNER_ACCEPTED', labelKey: 'PHARMACY.ATTENTION.PARTNER_ACCEPTED' },
  { reason: 'BACK_ORDER_OUTSTANDING', labelKey: 'PHARMACY.ATTENTION.BACK_ORDER_OUTSTANDING' },
  { reason: 'CLARIFICATION_RESOLVED', labelKey: 'PHARMACY.ATTENTION.CLARIFICATION_RESOLVED' },
  // G15: a prepared fill nobody has come for. Lowest precedence; carries
  // the number of days it has waited.
  { reason: 'READY_UNCOLLECTED', labelKey: 'PHARMACY.ATTENTION.READY_UNCOLLECTED' },
];

const DAY_MS = 24 * 60 * 60 * 1000;

/** G13: how often the queue refreshes while the tab is visible (claims change under us). */
export const QUEUE_POLL_MS = 60_000;

/** G13: after a refused claim, the row's state is looked up in pages of this size... */
const QUEUE_LOOKUP_PAGE_SIZE = 100;
/** ...and at most this many pages. */
const QUEUE_LOOKUP_MAX_PAGES = 10;

/** G13: the remembered claim filter, a per-viewer convenience. */
const CLAIM_FILTER_STORAGE_KEY = 'hms.pharmacy.queueClaimFilter';

/** G13: the claim filters, in the order the control shows them. */
export const QUEUE_CLAIM_FILTERS: readonly { value: QueueClaimFilter; labelKey: string }[] = [
  { value: 'ALL', labelKey: 'PHARMACY.QUEUE_CLAIM.FILTER.ALL' },
  { value: 'MINE', labelKey: 'PHARMACY.QUEUE_CLAIM.FILTER.MINE' },
  { value: 'UNCLAIMED', labelKey: 'PHARMACY.QUEUE_CLAIM.FILTER.UNCLAIMED' },
];

function readStoredClaimFilter(): QueueClaimFilter {
  try {
    const stored = globalThis.localStorage?.getItem(CLAIM_FILTER_STORAGE_KEY);
    return stored === 'MINE' || stored === 'UNCLAIMED' ? stored : 'ALL';
  } catch {
    return 'ALL';
  }
}

function storeClaimFilter(filter: QueueClaimFilter): void {
  try {
    globalThis.localStorage?.setItem(CLAIM_FILTER_STORAGE_KEY, filter);
  } catch {
    // storage unavailable (private window, blocked site data): not remembered
  }
}

/** Newest first; a missing or unparseable time sorts last. */
function eventTime(primary?: string | null, fallback?: string | null): number {
  const raw = primary ?? fallback;
  if (!raw) return 0;
  // #948 review: a zone-less UTC server time (daysWaiting counts from it).
  const parsed = serverInstant(raw).getTime();
  return Number.isNaN(parsed) ? 0 : parsed;
}

/** The reason whose label already says the prescriber answered. */
const CLARIFICATION_RESOLVED_LABEL_KEY = 'PHARMACY.ATTENTION.CLARIFICATION_RESOLVED';

/**
 * A reason this build has never heard of still flags the row. A row the
 * pharmacist should look at is the point; a value added to the backend after
 * this build shipped is exactly the case a hard-coded list gets wrong.
 */
export const UNRECOGNISED_ATTENTION = {
  labelKey: 'PHARMACY.ATTENTION.UNRECOGNISED',
};

@Component({
  selector: 'app-dispensing',
  standalone: true,
  imports: [
    ServerTimePipe,
    PersonNamePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    PrescriptionClarificationComponent,
    DialogFocusDirective,
  ],
  templateUrl: './dispensing.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './dispensing.scss',
})
export class DispensingComponent implements OnInit, OnDestroy {
  private readonly svc = inject(PharmacyService);
  /** QA D966: opens a fetched PDF for printing. */
  private readonly printService = inject(PrintLabelService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly offlineQueue = inject(OfflineDispenseQueueService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  // Work queue
  workQueue = signal<WorkQueuePrescription[]>([]);
  queueLoading = signal(false);
  queuePage = 0;
  queueTotalPages = 0;

  // Roadmap row 4 / T-68 — offline-queue UI state. `pendingQueued` mirrors
  // OfflineDispenseQueueService.pending$ so the offline banner re-renders
  // automatically as the user (or the online-event auto-replay) drains it.
  // `syncing` flips while a replay is in flight to suppress double-clicks
  // on the manual "Sync now" button.
  pendingQueued = signal(0);
  syncing = signal(false);
  private pendingSubscription: Subscription | null = null;
  private onlineHandler: (() => void) | null = null;

  // Pharmacies
  pharmacies = signal<PharmacyResponse[]>([]);
  /** True once the pharmacy list answered, so an empty list shows a hint, not a blank select (D59). */
  pharmaciesLoaded = signal(false);
  selectedPharmacyId = '';

  // Inventory items for stock lot selection
  inventoryItems = signal<InventoryItemResponse[]>([]);

  // Tier 2 item 34 — the real stock lots the picker binds to.
  stockLots = signal<StockLotResponse[]>([]);
  readonly selectedLotId = signal('');

  /** QA D654: colleagues who may witness a controlled substance leaving or coming back. */
  witnessCandidates = signal<StockWitnessCandidate[]>([]);
  /** QA D654: the controlled fill whose cancel waits for its witness. */
  /** QA D654 / D1069: the fill whose cancel panel is open (reason always, witness for a controlled one). */
  readonly cancelWitnessFor = signal<string | null>(null);
  readonly cancelNeedsWitness = signal(false);
  cancelWitnessId: string | undefined = undefined;
  /** QA D1069: why the fill is cancelled; it voids the payment and returns the stock. */
  dispenseCancelReason = '';

  // Dispensing form
  showForm = signal(false);
  saving = signal(false);
  selectedPrescription: WorkQueuePrescription | null = null;
  form: DispenseRequest = this.emptyForm();
  /**
   * QA D1102 class: the serious interaction alerts the server refused the
   * last fill with. While set, the form shows them and asks for the
   * pharmacist's override reason; the screen had no such field, so the fill
   * could never go through.
   */
  readonly cdsAlerts = signal<string[]>([]);

  // Recent dispenses
  recentDispenses = signal<DispenseResponse[]>([]);
  dispensesLoading = signal(false);

  // G15 — ready for collection. The server says whether "Mark ready" is on
  // (GET /pharmacy/dispense/settings); off until it has answered, so a
  // pharmacist never sees a button the server would refuse with a 404.
  readonly readyForCollectionEnabled = signal(false);
  readonly readyCancelReasons = READY_CANCEL_REASONS;
  /** The prepared row being handed over or whose preparation is being cancelled. */
  readonly readyAction = signal<{ kind: 'handOver' | 'cancel'; rx: WorkQueuePrescription } | null>(
    null,
  );
  readyActionSaving = signal(false);
  handOverScan = '';
  cancelReason: ReadyCancelReason = 'NOT_COLLECTED';

  // G13 — work-queue claim ("being prepared by"). Advisory: it coordinates
  // pharmacists and never blocks a dispense. Off until the server says so.
  readonly queueClaimEnabled = signal(false);
  readonly claimFilters = QUEUE_CLAIM_FILTERS;
  readonly claimFilter = signal<QueueClaimFilter>(readStoredClaimFilter());
  /**
   * The pending take-over confirm: from a row's Take over button, from
   * Dispense on a row a colleague holds, or from Route on such a row.
   */
  readonly claimAction = signal<{
    rx: WorkQueuePrescription;
    purpose: 'takeOver' | 'dispense' | 'route';
  } | null>(null);
  readonly claimSaving = signal(false);
  /** The row whose Dispense claim request is in flight; a second click on it is ignored. */
  readonly claimPendingRowId = signal<string | null>(null);
  /** The row whose claim the dispense form made (renewed:false); released when the form is left. */
  /** QA D512: the pharmacist attests the prescription was verified. */
  readonly verifyAttested = signal(false);
  readonly formClaimedRowId = signal<string | null>(null);
  /** No background reload while the pharmacist is in a form or a dialog. */
  readonly pollPaused = computed(
    () => this.showForm() || this.claimAction() !== null || this.readyAction() !== null,
  );
  private pollHandle: ReturnType<typeof setInterval> | null = null;
  /** The work-queue request in flight; a newer one cancels it, so the latest answer wins. */
  private queueRequest: Subscription | null = null;
  private visibilityHandler: (() => void) | null = null;

  ngOnInit(): void {
    this.loadPharmacies();
    this.svc.listWitnessCandidates().subscribe({
      next: (res) => this.witnessCandidates.set(res?.data ?? []),
      error: () => this.witnessCandidates.set([]),
    });
    this.loadDispenseSettings();
    this.startQueuePolling();

    // Roadmap row 4 / T-68 — wire up the offline queue. Subscribe to the
    // pending count so the banner reacts in real time, and trigger a replay
    // sweep whenever the browser regains connectivity. Both teardowns happen
    // in ngOnDestroy below.
    this.pendingSubscription = this.offlineQueue.pending$.subscribe((n) =>
      this.pendingQueued.set(n),
    );
    if (typeof window !== 'undefined') {
      this.onlineHandler = () => {
        // Best-effort — failures are surfaced in the per-item attempt
        // counter. A toast on success keeps the pharmacist informed.
        void this.replayQueue();
      };
      window.addEventListener('online', this.onlineHandler);
    }
  }

  ngOnDestroy(): void {
    this.pendingSubscription?.unsubscribe();
    if (typeof window !== 'undefined' && this.onlineHandler) {
      window.removeEventListener('online', this.onlineHandler);
    }
    this.stopQueuePolling();
    this.queueRequest?.unsubscribe();
    // Best effort: a claim the form made is let go when the page is left.
    this.releaseFormClaim(false);
  }

  /**
   * G13 AC-17: the queue refreshes every minute while the tab is visible,
   * and at once when it becomes visible again, so a colleague's claim shows
   * up without a click. Never while a form or a dialog is open.
   */
  private startQueuePolling(): void {
    if (typeof document === 'undefined') return;
    this.pollHandle = setInterval(() => this.pollQueue(), QUEUE_POLL_MS);
    this.visibilityHandler = () => this.pollQueue();
    document.addEventListener('visibilitychange', this.visibilityHandler);
  }

  private stopQueuePolling(): void {
    if (this.pollHandle !== null) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
    }
    if (typeof document !== 'undefined' && this.visibilityHandler) {
      document.removeEventListener('visibilitychange', this.visibilityHandler);
      this.visibilityHandler = null;
    }
  }

  /** One background refresh, if the tab is visible and nothing is open. */
  pollQueue(): void {
    if (!this.queueClaimEnabled() || this.pollPaused() || !this.selectedPharmacyId) return;
    if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return;
    this.loadWorkQueue(true);
  }

  /**
   * Drains the offline dispense queue against the live backend. Idempotent
   * on the wire — every queued request carries an idempotency_key the
   * backend (V94) deduplicates on, so a retry that races a successful
   * original POST is a no-op rather than a double dispense.
   */
  async replayQueue(): Promise<void> {
    if (this.syncing()) return;
    this.syncing.set(true);
    try {
      const result = await this.offlineQueue.replayAll((req) =>
        firstValueFrom(this.svc.createDispense(req)).then((res) => res.data),
      );
      if (result.succeeded > 0) {
        // Two whole sentences rather than a concatenated tail: the
        // "still queued" clause does not sit in the same place in every
        // language.
        this.toast.success(
          result.failed > 0
            ? this.translate.instant('PHARMACY.OFFLINE_SYNCED_WITH_PENDING', {
                count: result.succeeded,
                failed: result.failed,
              })
            : this.translate.instant('PHARMACY.OFFLINE_SYNCED', { count: result.succeeded }),
        );
        // Refresh the on-screen queue so the just-synced rows appear.
        this.loadRecentDispenses();
        this.loadWorkQueue();
      } else if (result.failed > 0) {
        this.toast.error(
          this.translate.instant('PHARMACY.OFFLINE_SYNC_FAILED', { count: result.failed }),
        );
      }
    } finally {
      this.syncing.set(false);
    }
  }

  private loadDispenseSettings(): void {
    this.svc.getDispenseSettings().subscribe({
      next: (res) => {
        this.readyForCollectionEnabled.set(!!res?.data?.readyForCollectionEnabled);
        const claimsOn = !!res?.data?.queueClaimEnabled;
        const wasOn = this.queueClaimEnabled();
        this.queueClaimEnabled.set(claimsOn);
        // The first load went out before the server answered; with claims on
        // and a remembered filter, list what the filter says.
        if (claimsOn && !wasOn && this.claimFilter() !== 'ALL' && this.selectedPharmacyId) {
          this.loadWorkQueue();
        }
      },
      error: () => {
        this.readyForCollectionEnabled.set(false);
        this.queueClaimEnabled.set(false);
      },
    });
  }

  private loadPharmacies(): void {
    this.svc.listPharmacies(0, 100).subscribe({
      next: (page) => {
        const list = page?.content ?? [];
        this.pharmacies.set(list);
        this.pharmaciesLoaded.set(true);
        if (list.length > 0) {
          this.selectedPharmacyId = list[0].id;
          this.loadWorkQueue();
          this.loadRecentDispenses();
          this.loadInventory();
          this.loadStockLots();
        }
      },
      error: () => this.toast.error(this.translate.instant('PHARMACY.PHARMACIES_LOAD_FAILED')),
    });
  }

  /**
   * @param quiet a background refresh (G13 polling): no loading bar, no
   *              error toast; the rows already on screen stay until it lands
   */
  loadWorkQueue(quiet = false, onLoaded?: () => void, onFailed?: () => void): void {
    if (!quiet) this.queueLoading.set(true);
    const filter = this.currentQueueFilter();
    const pageNumber = this.queuePage;
    // The poll, the visibility refresh, the post-claim reload, the settings
    // reload and the filter change all land here: only the latest request's
    // answer may be applied, never an older one that happens to arrive last.
    this.queueRequest?.unsubscribe();
    this.queueRequest = this.svc.getDispenseWorkQueue(pageNumber, 20, filter).subscribe({
      next: (res) => {
        this.queueLoading.set(false);
        // Belt and braces: an answer for a filter or page no longer shown is dropped.
        if (filter !== this.currentQueueFilter() || pageNumber !== this.queuePage) return;
        const page = res?.data;
        this.workQueue.set(page?.content ?? []);
        this.queueTotalPages = page?.totalPages ?? 0;
        onLoaded?.();
      },
      error: () => {
        this.queueLoading.set(false);
        if (!quiet) this.toast.error(this.translate.instant('PHARMACY.WORK_QUEUE_LOAD_FAILED'));
        onFailed?.();
      },
    });
  }

  /** The filter the queue is listed by: the chosen one when claims are on, else ALL. */
  private currentQueueFilter(): QueueClaimFilter {
    return this.queueClaimEnabled() ? this.claimFilter() : 'ALL';
  }

  // ── G13: work-queue claim ──

  /** All / Mine / Unclaimed; remembered for this viewer. */
  setClaimFilter(filter: QueueClaimFilter): void {
    if (filter === this.claimFilter()) return;
    this.claimFilter.set(filter);
    storeClaimFilter(filter);
    this.queuePage = 0;
    this.loadWorkQueue();
  }

  /** A colleague's active claim on the row, or null (none, or it is the caller's). */
  colleagueClaim(rx: WorkQueuePrescription): WorkQueueClaim | null {
    return rx.claim && !rx.claim.mine ? rx.claim : null;
  }

  /** The claim the confirm names: the freshest copy of the row, else the one clicked. */
  claimShownFor(rx: WorkQueuePrescription): WorkQueueClaim | null {
    const fresh = this.workQueue().find((row) => row.id === rx.id);
    return (fresh ?? rx).claim ?? null;
  }

  /** "Claim": say this pharmacist is preparing the row. */
  claimRow(rx: WorkQueuePrescription): void {
    this.svc.claimQueueRow(rx.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.QUEUE_CLAIM.CLAIMED'));
        this.loadWorkQueue(true);
      },
      error: (err) => this.claimRefused(err),
    });
  }

  /** "Release": let go of one's own claim. */
  releaseRow(rx: WorkQueuePrescription): void {
    this.svc.releaseQueueRow(rx.id).subscribe({
      next: () => {
        if (this.formClaimedRowId() === rx.id) this.formClaimedRowId.set(null);
        this.toast.success(this.translate.instant('PHARMACY.QUEUE_CLAIM.RELEASED'));
        this.loadWorkQueue(true);
      },
      error: (err) => this.claimRefused(err),
    });
  }

  /** "Take over" on a colleague's row: ask first, naming the holder. */
  requestTakeOver(rx: WorkQueuePrescription): void {
    this.claimAction.set({ rx, purpose: 'takeOver' });
  }

  cancelClaimAction(): void {
    this.claimAction.set(null);
  }

  /** The confirm said Take over: take the claim, then do what was asked. */
  confirmTakeOver(): void {
    const action = this.claimAction();
    if (!action) return;
    this.claimSaving.set(true);
    this.svc.takeOverQueueRow(action.rx.id).subscribe({
      next: (res) => {
        this.claimSaving.set(false);
        this.claimAction.set(null);
        if (action.purpose === 'dispense') {
          this.openFormFor(action.rx, !res?.data?.renewed);
        } else if (action.purpose === 'route') {
          this.router.navigate(['/pharmacy/stock-routing', action.rx.id]);
        } else {
          this.toast.success(this.translate.instant('PHARMACY.QUEUE_CLAIM.TAKEN_OVER'));
          this.loadWorkQueue(true);
        }
      },
      error: (err) => {
        this.claimSaving.set(false);
        this.claimAction.set(null);
        this.claimRefused(err);
      },
    });
  }

  /** A claim call was refused: say why and show the queue as it now is. */
  private claimRefused(err: { status?: number; error?: { message?: string } }): void {
    this.toast.error(err?.error?.message ?? this.translate.instant('PHARMACY.QUEUE_CLAIM.FAILED'));
    this.loadWorkQueue(true);
  }

  /** Lets go of the claim the form made, if any (not after a dispense: the server ended it). */
  private releaseFormClaim(reload: boolean): void {
    const rowId = this.formClaimedRowId();
    if (!rowId) return;
    this.formClaimedRowId.set(null);
    this.releaseClaimOf(rowId, reload);
  }

  private releaseClaimOf(rowId: string, reload: boolean): void {
    this.svc.releaseQueueRow(rowId).subscribe({
      next: () => {
        if (reload) this.loadWorkQueue(true);
      },
      // Best effort: an unreleased claim lapses on its own.
      error: () => undefined,
    });
  }

  private loadRecentDispenses(): void {
    if (!this.selectedPharmacyId) return;
    this.dispensesLoading.set(true);
    this.svc.listDispensesByPharmacy(this.selectedPharmacyId, 0, 10).subscribe({
      next: (res) => {
        // G15: a prepared fill has no dispensedAt yet; it sorts by when it
        // was prepared (coalesce(dispensedAt, createdAt)), newest first.
        this.recentDispenses.set(
          [...(res?.data?.content ?? [])].sort(
            (a, b) => eventTime(b.dispensedAt, b.createdAt) - eventTime(a.dispensedAt, a.createdAt),
          ),
        );
        this.dispensesLoading.set(false);
      },
      error: () => {
        this.dispensesLoading.set(false);
        this.recentDispenses.set([]);
        this.toast.error(this.translate.instant('PHARMACY.RECENT_DISPENSES_LOAD_FAILED'));
      },
    });
  }

  private loadInventory(): void {
    if (!this.selectedPharmacyId) return;
    this.svc.listInventoryByPharmacy(this.selectedPharmacyId, 0, 200).subscribe({
      next: (res) => {
        const page = res?.data;
        this.inventoryItems.set(page?.content ?? []);
      },
    });
  }

  /**
   * Tier 2 item 34 — real stock lots for the lot picker.
   *
   * The picker previously listed INVENTORY ITEMS and put their ids into
   * `form.stockLotId`, so the backend looked each one up in the stock-lot
   * table and returned 404. Choosing a lot could therefore never succeed,
   * which is why the expiry and drug-match holes V138 closes went unnoticed
   * for so long: in practice every dispense went through with no lot at all,
   * and so with no stock decrement either.
   *
   * QA D241: only the lots of the prescribed drug. The picker used to list
   * every lot of the pharmacy (morphine included) for any prescription.
   */
  private loadStockLots(): void {
    const rxId = this.selectedPrescription?.id;
    this.stockLots.set([]);
    if (!this.selectedPharmacyId || !rxId) {
      return;
    }
    this.svc.listDispensableLots(rxId, this.selectedPharmacyId).subscribe({
      next: (res) => {
        // A late answer for a prescription that is no longer open is dropped.
        if (this.selectedPrescription?.id !== rxId) return;
        this.stockLots.set(res?.data ?? []);
        this.preselectFefoLot();
      },
      error: () => this.stockLots.set([]),
    });
  }

  /**
   * Lots with stock left, shortest-dated first (FEFO), expired ones dropped.
   *
   * Filtering client-side is a convenience, not the control: the server
   * refuses an expired lot regardless of what this list shows.
   */
  readonly dispensableLots = computed(() =>
    this.stockLots()
      .filter((lot) => lot.remainingQuantity > 0 && !this.isExpired(lot))
      .sort((a, b) => a.expiryDate.localeCompare(b.expiryDate)),
  );

  /**
   * QA D964 / D1029: first expired, first out. The shortest-dated usable lot,
   * whatever the quantity (a larger fill is split: {@link fefoSplit});
   * pre-selected when the lots arrive, unless the pharmacist already chose.
   */
  fefoLot(): StockLotResponse | undefined {
    return fefoFirstLot(this.dispensableLots());
  }

  /**
   * QA D1029: the fill split across lots in expiry order, when the chosen lot
   * is the shortest-dated one and holds less than the quantity: the first lot
   * is emptied and the rest comes from the next. Empty when one lot covers it
   * (or the lots together cannot, which the server refuses as before). A
   * method, not a computed: it reads the quantity typed in the form.
   */
  fefoSplit(): FefoPart<StockLotResponse>[] {
    const chosen = this.selectedLot();
    const qty = Number(this.form.quantityDispensed) || 0;
    if (!chosen || chosen.id !== this.fefoLot()?.id || qty <= chosen.remainingQuantity) return [];
    // An order with no stated quantity (or 0) counts one complete fill as the whole
    // order (the server's rule), so a first part would close it and refuse
    // the rest: such a fill is not split (the shortest lot, or a reason).
    if (!((this.selectedPrescription?.quantity ?? 0) > 0)) return [];
    const parts = fefoAllocation(this.dispensableLots(), qty);
    return parts.length > 1 ? parts : [];
  }

  private preselectFefoLot(): void {
    const lot = this.fefoLot();
    if (lot && !this.form.stockLotId) {
      this.form.stockLotId = lot.id;
      this.onLotChange();
    }
  }

  /**
   * QA D964 / D1029: a shorter-dated lot of the same item that still holds
   * stock (the server's FefoLotPolicy rule). While it exists, taking the
   * chosen lot needs a reason, whether or not the earlier lot could cover the
   * whole fill.
   */
  earlierLot(): StockLotResponse | undefined {
    return earlierLotWithStock(this.dispensableLots(), this.selectedLot());
  }

  /** QA D964: a FEFO skip with no reason keeps Dispense and Ready disabled. */
  fefoReasonMissing(): boolean {
    return !!this.earlierLot() && !this.form.fefoOverrideReason?.trim();
  }

  isExpired(lot: StockLotResponse): boolean {
    if (!lot?.expiryDate) return false;
    // QA D710: date-only comparison, and a lot is expired ON its expiry date,
    // the same rule as the server's receipt, pick, check and expiry report.
    return lot.expiryDate <= localIsoDate(new Date());
  }

  /** The lot currently chosen in the form, if any. */
  readonly selectedLot = computed(() =>
    this.stockLots().find((lot) => lot.id === this.selectedLotId()),
  );

  /** QA D654: the chosen lot is of a controlled substance, so the fill is witnessed. */
  readonly lotIsControlled = computed(() => {
    const lot = this.selectedLot();
    return (
      !!lot && this.inventoryItems().some((i) => i.id === lot.inventoryItemId && !!i.controlled)
    );
  });

  /** QA D654: a fill of a controlled substance from a lot, whose cancel is witnessed. */
  isControlledFill(d: DispenseResponse): boolean {
    return (
      !!d.stockLotId &&
      this.inventoryItems().some(
        (i) => !!i.controlled && i.medicationCatalogItemId === d.medicationCatalogItemId,
      )
    );
  }

  onLotChange(): void {
    this.selectedLotId.set(this.form.stockLotId ?? '');
    // A new lot invalidates a scan taken against the previous one.
    this.form.productScanValue = '';
  }

  /** Open the printable label so the pharmacist has something to scan. */
  printLotLabel(): void {
    const lotId = this.form.stockLotId;
    if (!lotId) return;
    // QA D966: fetched with the session (the interceptors add /api and the
    // token), then opened for printing; a refusal is said, not a blank tab.
    this.svc.getStockLotLabel(lotId).subscribe({
      next: (blob) => this.printService.openForPrint(blob),
      error: () => this.toast.error(this.translate.instant('PHARMACY.PRINT_LABEL_FAILED')),
    });
  }

  onPharmacyChange(): void {
    // Reset pagination and close any in-progress form — context is tied to pharmacy.
    this.queuePage = 0;
    this.closeForm();
    this.loadWorkQueue();
    this.loadRecentDispenses();
    this.loadInventory();
    this.loadStockLots();
  }

  /**
   * P-05: deep-link to stock-routing with the prescription ID pre-filled. This
   * removes the need for users to copy/paste a raw UUID into the routing form
   * when they discover an out-of-stock condition while dispensing.
   */
  routeFromQueue(rx: WorkQueuePrescription): void {
    if (!rx?.id) return;
    // G13: routing a row a colleague is preparing takes it over; ask first.
    if (this.queueClaimEnabled() && this.colleagueClaim(rx)) {
      this.claimAction.set({ rx, purpose: 'route' });
      return;
    }
    this.router.navigate(['/pharmacy/stock-routing', rx.id]);
  }

  /**
   * G13 AC-17: Dispense claims the row first, then opens the form. A row a
   * colleague holds asks for a take-over (Take over / Cancel); a row that is
   * no longer claimable says why and stays closed; any other failure opens
   * the form anyway, since the claim is advisory.
   */
  selectPrescription(rx: WorkQueuePrescription): void {
    if (!this.queueClaimEnabled()) {
      this.openForm(rx);
      return;
    }
    // The row whose form is already open: nothing to do, and the claim the
    // form made stays the form's (a re-claim would come back renewed).
    if (this.showForm() && this.selectedPrescription?.id === rx.id) return;
    if (this.colleagueClaim(rx)) {
      this.claimAction.set({ rx, purpose: 'dispense' });
      return;
    }
    // A double click must not send a second claim: its renewed:true answer
    // would make the form forget the claim it made.
    if (this.claimPendingRowId() === rx.id) return;
    this.claimPendingRowId.set(rx.id);
    this.svc.claimQueueRow(rx.id).subscribe({
      next: (res) => {
        this.claimPendingRowId.set(null);
        this.openFormFor(rx, !res?.data?.renewed);
      },
      error: (err) => {
        this.claimPendingRowId.set(null);
        this.dispenseClaimFailed(rx, err);
      },
    });
  }

  /**
   * Opens the form on {@code rx}. Only now, with the new form actually
   * opening, is the previous form's own claim let go: a cancelled take-over
   * confirm or a refused claim leaves the open form and its claim alone.
   *
   * @param claimedByForm the claim call created the claim (renewed:false)
   */
  private openFormFor(rx: WorkQueuePrescription, claimedByForm: boolean): void {
    const previous = this.formClaimedRowId();
    if (previous && previous !== rx.id) this.releaseClaimOf(previous, false);
    // A renewed answer for the row the form already claimed: still the form's claim.
    this.formClaimedRowId.set(claimedByForm || previous === rx.id ? rx.id : null);
    if (this.showForm() && this.selectedPrescription?.id === rx.id) return;
    this.openForm(rx);
  }

  /**
   * A 409 body carries the server's sentence, not its key, so the reloaded
   * row decides: a colleague's claim on it means "take over?", anything else
   * (gone from the queue, prepared meanwhile) means the form stays closed.
   */
  private dispenseClaimFailed(
    rx: WorkQueuePrescription,
    err: { status?: number; error?: { message?: string } },
  ): void {
    if (err?.status !== 409) {
      this.toast.error(this.translate.instant('PHARMACY.QUEUE_CLAIM.FAILED'));
      this.openFormFor(rx, false);
      return;
    }
    const refused = () =>
      this.toast.error(
        err?.error?.message ?? this.translate.instant('PHARMACY.QUEUE_CLAIM.FAILED'),
      );
    // The visible list refreshes with its own filter; the decision does not
    // read it, because MINE / UNCLAIMED omit exactly the row a colleague has
    // just claimed.
    this.loadWorkQueue(true);
    this.findQueueRowState(rx.id, 0, (fresh) => {
      if (fresh && this.colleagueClaim(fresh) && !fresh.readyForCollection) {
        this.claimAction.set({ rx: fresh, purpose: 'dispense' });
      } else {
        refused();
      }
    });
  }

  /**
   * The row's current state, whatever filter is shown: walks the unfiltered
   * queue (ALL) page by page until it finds the row, runs out of pages or
   * reaches {@link QUEUE_LOOKUP_MAX_PAGES}. Null when not found (gone from
   * the queue) or on a failure.
   */
  private findQueueRowState(
    rowId: string,
    pageNumber: number,
    done: (row: WorkQueuePrescription | null) => void,
  ): void {
    this.svc.getDispenseWorkQueue(pageNumber, QUEUE_LOOKUP_PAGE_SIZE, 'ALL').subscribe({
      next: (res) => {
        const page = res?.data;
        const hit = page?.content?.find((row) => row.id === rowId);
        if (hit) {
          done(hit);
          return;
        }
        const next = pageNumber + 1;
        if (next < (page?.totalPages ?? 0) && next < QUEUE_LOOKUP_MAX_PAGES) {
          this.findQueueRowState(rowId, next, done);
        } else {
          done(null);
        }
      },
      error: () => done(null),
    });
  }

  /** QA D1001: what is still owed on the order; the prescribed amount only when the server sent none. */
  remainingQuantity(rx: WorkQueuePrescription): number {
    return rx.remainingQuantity ?? rx.quantity ?? 0;
  }

  /** QA D1001: true when part of the order was already dispensed, so the queue shows both figures. */
  isPartlyDispensed(rx: WorkQueuePrescription): boolean {
    return (
      rx.remainingQuantity != null && rx.quantity != null && rx.remainingQuantity < rx.quantity
    );
  }

  private openForm(rx: WorkQueuePrescription): void {
    this.selectedPrescription = rx;
    this.form = this.emptyForm();
    this.form.prescriptionId = rx.id;
    this.form.patientId = rx.patient?.id ?? '';
    this.form.pharmacyId = this.selectedPharmacyId;
    this.form.medicationName = rx.medicationName ?? '';
    // QA D432 then D1001: the fill starts at what is still owed on the order
    // (the server's ceiling), not at 0 and not at the full prescribed amount,
    // which a partly filled order refuses ("il reste au plus 10").
    const owed = this.remainingQuantity(rx);
    this.form.quantityRequested = owed;
    this.form.quantityDispensed = owed;
    this.form.dispensedBy = this.auth.currentProfile()?.id ?? '';
    this.verifyAttested.set(false);
    this.cdsAlerts.set([]);
    this.showForm.set(true);
    this.loadStockLots();
  }

  /**
   * QA D512: the pharmacist's own verification of the prescription, sent as
   * `verifiedBy` (the server checks it is the session user). Without it the
   * server refuses the fill.
   */
  private attestedForm(): DispenseRequest {
    const body: DispenseRequest = { ...this.form };
    delete body.verifiedBy;
    if (!this.lotIsControlled()) {
      delete body.witnessedBy;
    }
    if (this.verifyAttested()) {
      body.verifiedBy = this.form.dispensedBy;
    }
    // QA D1102 class: the interaction override travels once the server asked for it.
    if (this.cdsAlerts().length > 0) {
      body.cdsOverrideReason = this.form.cdsOverrideReason?.trim();
    } else {
      delete body.cdsOverrideReason;
    }
    // QA D964: the reason travels only when a shorter-dated lot was skipped.
    if (this.earlierLot()) {
      body.fefoOverrideReason = this.form.fefoOverrideReason?.trim();
    } else {
      delete body.fefoOverrideReason;
    }
    return body;
  }

  /** QA D654: refuses a controlled fill with no witness before the server does. */
  private missingWitness(): boolean {
    // QA D1089 class (round 19): the FEFO reason is asked for where it is
    // typed; a dead "Délivrer" button used to hide the message.
    if (this.fefoReasonMissing()) {
      refuseFields(
        this.host.nativeElement,
        ['fefoReason'],
        ['fefoReason'],
        this.translate.instant('PHARMACY.FEFO_REASON_REQUIRED'),
      );
      return true;
    }
    if (this.lotIsControlled() && !this.form.witnessedBy) {
      refuseFields(
        this.host.nativeElement,
        ['dispWitness'],
        ['dispWitness'],
        this.translate.instant('PHARMACY.WITNESS_REQUIRED'),
      );
      this.toast.error(this.translate.instant('PHARMACY.WITNESS_REQUIRED'));
      return true;
    }
    return false;
  }

  submitDispense(): void {
    if (this.missingWitness()) return;
    const split = this.fefoSplit();
    if (split.length > 1) {
      this.submitSplitDispense(split);
      return;
    }
    this.saving.set(true);
    this.svc.createDispense(this.attestedForm()).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.DISPENSE_SUCCESS'));
        this.formClaimedRowId.set(null);
        this.saving.set(false);
        this.showForm.set(false);
        this.selectedPrescription = null;
        this.loadWorkQueue();
        this.loadRecentDispenses();
      },
      error: (err) => {
        this.saving.set(false);
        this.noteCdsAlerts(err);
        this.toast.error(err?.error?.message ?? this.translate.instant('PHARMACY.DISPENSE_FAILED'));
      },
    });
  }

  /** QA D1102 class: the alerts a refused fill came back with, kept for the override field. */
  private noteCdsAlerts(err: unknown): void {
    const cards = (err as { error?: { cdsAdvisories?: { summary?: string }[] } } | null)?.error
      ?.cdsAdvisories;
    if (Array.isArray(cards) && cards.length > 0) {
      this.cdsAlerts.set(cards.map((c) => c.summary ?? '').filter((s) => s.length > 0));
    }
  }

  /** The override reason is asked for and still empty: said before the server says it. */
  cdsOverrideMissing(): boolean {
    return this.cdsAlerts().length > 0 && !this.form.cdsOverrideReason?.trim();
  }

  /**
   * QA D1029: one fill per lot, in expiry order, so the shorter-dated lot is
   * emptied first. Sequential: the second fill is sent only once the first
   * is recorded (the order is then partly filled, and the next fill is FEFO
   * because the earlier lot is empty). If a later part fails, the parts
   * already recorded stand and the order shows what is still owed.
   */
  private async submitSplitDispense(parts: FefoPart<StockLotResponse>[]): Promise<void> {
    this.saving.set(true);
    const base = this.attestedForm();
    let recorded = 0;
    try {
      for (const [index, part] of parts.entries()) {
        const body: DispenseRequest = {
          ...base,
          stockLotId: part.lot.id,
          quantityRequested: part.quantity,
          quantityDispensed: part.quantity,
        };
        delete body.fefoOverrideReason;
        // The product scan names one pack's lot: it is checked on the first part only.
        if (index > 0) delete body.productScanValue;
        await firstValueFrom(this.svc.createDispense(body));
        recorded++;
      }
      this.toast.success(
        this.translate.instant('PHARMACY.DISPENSE_SPLIT_SUCCESS', { count: parts.length }),
      );
    } catch (err) {
      const e = err as { error?: { message?: string } };
      this.toast.error(e?.error?.message ?? this.translate.instant('PHARMACY.DISPENSE_FAILED'));
      if (recorded === 0) {
        this.saving.set(false);
        return;
      }
    }
    this.formClaimedRowId.set(null);
    this.saving.set(false);
    this.showForm.set(false);
    this.selectedPrescription = null;
    this.loadWorkQueue();
    this.loadRecentDispenses();
  }

  /**
   * G15 AC-1: prepare the fill and text the patient that it is ready. Same
   * form as a dispense; the wristband is checked at hand-over, not here.
   */
  submitReady(): void {
    if (this.missingWitness()) return;
    this.saving.set(true);
    this.svc.markReady({ ...this.attestedForm(), patientScanValue: '' }).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.READY_SUCCESS'));
        this.formClaimedRowId.set(null);
        this.saving.set(false);
        this.showForm.set(false);
        this.selectedPrescription = null;
        this.loadWorkQueue();
        this.loadRecentDispenses();
      },
      error: (err) => {
        this.saving.set(false);
        this.noteCdsAlerts(err);
        this.toast.error(err?.error?.message ?? this.translate.instant('PHARMACY.READY_FAILED'));
      },
    });
  }

  openHandOver(rx: WorkQueuePrescription): void {
    this.closeForm();
    this.handOverScan = '';
    this.readyAction.set({ kind: 'handOver', rx });
  }

  openCancelReady(rx: WorkQueuePrescription): void {
    this.closeForm();
    this.cancelReason = 'NOT_COLLECTED';
    this.readyAction.set({ kind: 'cancel', rx });
  }

  closeReadyAction(): void {
    this.readyAction.set(null);
  }

  /** G15 AC-4: the patient is at the counter; the optional scan is checked server-side. */
  confirmHandOver(): void {
    const dispenseId = this.readyAction()?.rx.readyForCollection?.dispenseId;
    if (!dispenseId) return;
    const scan = this.handOverScan.trim();
    this.readyActionSaving.set(true);
    this.svc.handOver(dispenseId, scan ? { patientScanValue: scan } : {}).subscribe({
      next: () => this.afterReadyAction('PHARMACY.HAND_OVER_SUCCESS'),
      error: (err) => this.readyActionFailed(err, 'PHARMACY.HAND_OVER_FAILED'),
    });
  }

  /** G15 AC-7: the stock goes back and the patient is told it is no longer ready. */
  confirmCancelReady(): void {
    const dispenseId = this.readyAction()?.rx.readyForCollection?.dispenseId;
    if (!dispenseId) return;
    this.readyActionSaving.set(true);
    this.svc.cancelReady(dispenseId, this.cancelReason).subscribe({
      next: () => this.afterReadyAction('PHARMACY.CANCEL_READY_SUCCESS'),
      error: (err) => this.readyActionFailed(err, 'PHARMACY.CANCEL_READY_FAILED'),
    });
  }

  private afterReadyAction(successKey: string): void {
    this.readyActionSaving.set(false);
    this.readyAction.set(null);
    this.toast.success(this.translate.instant(successKey));
    this.loadWorkQueue();
    this.loadRecentDispenses();
  }

  private readyActionFailed(err: { error?: { message?: string } }, fallbackKey: string): void {
    this.readyActionSaving.set(false);
    this.toast.error(err?.error?.message ?? this.translate.instant(fallbackKey));
  }

  /** Whole days a prepared fill has waited, for the queue's cue. */
  daysWaiting(rx: WorkQueuePrescription): number {
    const readyAt = rx.readyForCollection?.readyAt;
    if (!readyAt) return 0;
    return Math.max(0, Math.floor((Date.now() - eventTime(readyAt, null)) / DAY_MS));
  }

  /** Interpolation for an attention label: only READY_UNCOLLECTED has one. */
  attentionParams(rx: WorkQueuePrescription): Record<string, number> {
    return rx.attentionReason === 'READY_UNCOLLECTED' ? { days: this.daysWaiting(rx) } : {};
  }

  /**
   * QA D1069: every cancel opens the panel that asks why (the server refuses a
   * cancel without a reason; it voids the payment and returns the stock).
   * QA D654: a controlled fill also asks for the witness.
   */
  cancelDispense(id: string): void {
    const fill = this.recentDispenses().find((d) => d.id === id);
    this.cancelWitnessId = undefined;
    this.dispenseCancelReason = '';
    this.cancelNeedsWitness.set(!!fill && this.isControlledFill(fill));
    this.cancelWitnessFor.set(id);
  }

  /** QA D654 / D1069: the cancel, once the reason (and for a controlled fill, the witness) is given. */
  confirmWitnessedCancel(): void {
    const id = this.cancelWitnessFor();
    if (!id) return;
    const reason = this.dispenseCancelReason.trim();
    const missing = [
      !reason ? 'dispenseCancelReason' : null,
      this.cancelNeedsWitness() && !this.cancelWitnessId ? 'cancelWitness' : null,
    ].filter((f): f is string => f !== null);
    if (missing.length > 0) {
      const key =
        missing[0] === 'dispenseCancelReason'
          ? 'PHARMACY.CANCEL_REASON_REQUIRED'
          : 'PHARMACY.WITNESS_REQUIRED';
      refuseFields(
        this.host.nativeElement,
        missing,
        ['dispenseCancelReason', 'cancelWitness'],
        this.translate.instant(key),
      );
      this.toast.error(this.translate.instant(key));
      return;
    }
    this.sendCancel(id, reason, this.cancelNeedsWitness() ? this.cancelWitnessId : undefined);
  }

  private sendCancel(id: string, reason: string, witnessedBy?: string): void {
    this.svc.cancelDispense(id, reason, witnessedBy).subscribe({
      next: () => {
        this.cancelWitnessFor.set(null);
        this.toast.success(this.translate.instant('PHARMACY.DISPENSE_CANCELLED'));
        this.loadRecentDispenses();
        this.loadWorkQueue();
      },
      error: (err) =>
        this.toast.error(err?.error?.message ?? this.translate.instant('PHARMACY.CANCEL_FAILED')),
    });
  }

  closeForm(): void {
    this.showForm.set(false);
    this.selectedPrescription = null;
    // G13: left without dispensing; the claim the form made is let go.
    this.releaseFormClaim(true);
  }

  prevPage(): void {
    if (this.queuePage > 0) {
      this.queuePage--;
      this.loadWorkQueue();
    }
  }

  nextPage(): void {
    if (this.queuePage < this.queueTotalPages - 1) {
      this.queuePage++;
      this.loadWorkQueue();
    }
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'COMPLETED':
        return 'badge-success';
      case 'PARTIAL':
        return 'badge-warning';
      case 'CANCELLED':
        return 'badge-danger';
      default:
        return 'badge-info';
    }
  }

  /**
   * Why this row is not a plain fill, or null for one that is.
   *
   * <p>`needsAttention` had been on the model since the backend added it and
   * no template read it, so every one of these cues — a back order, a
   * partner's refusal, an order sitting with a partner — reached the
   * pharmacist as an ordinary row. Never the raw reason: it is an enum
   * name, not a sentence.
   */
  attentionKey(rx: WorkQueuePrescription): string | null {
    if (!rx.needsAttention && !rx.attentionReason) return null;
    const match = QUEUE_ATTENTION_REASONS.find((r) => r.reason === rx.attentionReason);
    return match ? match.labelKey : UNRECOGNISED_ATTENTION.labelKey;
  }

  /**
   * Whether the row needs its own "the prescriber has answered" line.
   *
   * <p>False when the attention reason is ALREADY the clarification: with
   * nothing of higher precedence to report the backend derives both from the
   * same timestamp, and the row would say it twice. The line earns its place
   * exactly when a status or a back order has taken the single reason — which
   * is the case the cue exists for.
   */
  showsAnswerCue(rx: WorkQueuePrescription): boolean {
    return (
      !!rx.clarificationResolvedAt && this.attentionKey(rx) !== CLARIFICATION_RESOLVED_LABEL_KEY
    );
  }

  /**
   * The refill decision drives whether medication should be handed over at all,
   * so DENIED and PAUSED are styled as stop signals rather than neutral chips.
   */
  refillBadgeClass(status: RefillDecisionStatus): string {
    switch (status) {
      case 'APPROVED':
        return 'badge badge-success';
      case 'DENIED':
        return 'badge badge-danger';
      case 'PAUSED':
        return 'badge badge-warning';
      default:
        return 'badge badge-info';
    }
  }

  private emptyForm(): DispenseRequest {
    this.selectedLotId.set('');
    return {
      prescriptionId: '',
      patientId: '',
      pharmacyId: '',
      dispensedBy: '',
      medicationName: '',
      quantityRequested: 0,
      quantityDispensed: 0,
      patientScanValue: '',
      productScanValue: '',
    };
  }
}

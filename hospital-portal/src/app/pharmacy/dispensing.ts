import {
  Component,
  computed,
  inject,
  OnDestroy,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
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
} from '../services/pharmacy.service';
import { AuthService } from '../auth/auth.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { OfflineDispenseQueueService } from './offline-dispense-queue.service';
import { PrescriptionClarificationComponent } from '../shared/prescription-clarification/prescription-clarification.component';

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
  { reason: 'PARTNER_ACCEPTED', labelKey: 'PHARMACY.ATTENTION.PARTNER_ACCEPTED' },
  { reason: 'BACK_ORDER_OUTSTANDING', labelKey: 'PHARMACY.ATTENTION.BACK_ORDER_OUTSTANDING' },
  { reason: 'CLARIFICATION_RESOLVED', labelKey: 'PHARMACY.ATTENTION.CLARIFICATION_RESOLVED' },
];

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
    CommonModule,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    PrescriptionClarificationComponent,
  ],
  templateUrl: './dispensing.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './dispensing.scss',
})
export class DispensingComponent implements OnInit, OnDestroy {
  private readonly svc = inject(PharmacyService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly offlineQueue = inject(OfflineDispenseQueueService);
  private readonly translate = inject(TranslateService);

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
  selectedPharmacyId = '';

  // Inventory items for stock lot selection
  inventoryItems = signal<InventoryItemResponse[]>([]);

  // Tier 2 item 34 — the real stock lots the picker binds to.
  stockLots = signal<StockLotResponse[]>([]);
  readonly selectedLotId = signal('');

  // Dispensing form
  showForm = signal(false);
  saving = signal(false);
  selectedPrescription: WorkQueuePrescription | null = null;
  form: DispenseRequest = this.emptyForm();

  // Recent dispenses
  recentDispenses = signal<DispenseResponse[]>([]);
  dispensesLoading = signal(false);

  ngOnInit(): void {
    this.loadPharmacies();

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

  private loadPharmacies(): void {
    this.svc.listPharmacies(0, 100).subscribe({
      next: (page) => {
        const list = page?.content ?? [];
        this.pharmacies.set(list);
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

  loadWorkQueue(): void {
    this.queueLoading.set(true);
    this.svc.getDispenseWorkQueue(this.queuePage, 20).subscribe({
      next: (res) => {
        const page = res?.data;
        this.workQueue.set(page?.content ?? []);
        this.queueTotalPages = page?.totalPages ?? 0;
        this.queueLoading.set(false);
      },
      error: () => {
        this.queueLoading.set(false);
        this.toast.error(this.translate.instant('PHARMACY.WORK_QUEUE_LOAD_FAILED'));
      },
    });
  }

  private loadRecentDispenses(): void {
    if (!this.selectedPharmacyId) return;
    this.dispensesLoading.set(true);
    this.svc.listDispensesByPharmacy(this.selectedPharmacyId, 0, 10).subscribe({
      next: (res) => {
        this.recentDispenses.set(res?.data?.content ?? []);
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
   */
  private loadStockLots(): void {
    if (!this.selectedPharmacyId) {
      this.stockLots.set([]);
      return;
    }
    this.svc.listLotsByPharmacy(this.selectedPharmacyId, 0, 200).subscribe({
      next: (res) => this.stockLots.set(res?.data?.content ?? []),
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

  isExpired(lot: StockLotResponse): boolean {
    if (!lot?.expiryDate) return false;
    // Date-only comparison: a lot is good through the end of its expiry day,
    // which is what "use before end of" on the pack means.
    return lot.expiryDate < new Date().toISOString().slice(0, 10);
  }

  /** The lot currently chosen in the form, if any. */
  readonly selectedLot = computed(() =>
    this.stockLots().find((lot) => lot.id === this.selectedLotId()),
  );

  onLotChange(): void {
    this.selectedLotId.set(this.form.stockLotId ?? '');
    // A new lot invalidates a scan taken against the previous one.
    this.form.productScanValue = '';
  }

  /** Open the printable label so the pharmacist has something to scan. */
  printLotLabel(): void {
    const lotId = this.form.stockLotId;
    if (!lotId) return;
    window.open(`/pharmacy/stock-lots/${lotId}/label.pdf`, '_blank', 'noopener');
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
    this.router.navigate(['/pharmacy/stock-routing', rx.id]);
  }

  selectPrescription(rx: WorkQueuePrescription): void {
    this.selectedPrescription = rx;
    this.form = this.emptyForm();
    this.form.prescriptionId = rx.id;
    this.form.patientId = rx.patient?.id ?? '';
    this.form.pharmacyId = this.selectedPharmacyId;
    this.form.medicationName = rx.medicationName ?? '';
    this.form.quantityRequested = rx.quantity ?? 0;
    this.form.dispensedBy = this.auth.currentProfile()?.id ?? '';
    this.showForm.set(true);
  }

  submitDispense(): void {
    this.saving.set(true);
    this.svc.createDispense(this.form).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.DISPENSE_SUCCESS'));
        this.saving.set(false);
        this.showForm.set(false);
        this.selectedPrescription = null;
        this.loadWorkQueue();
        this.loadRecentDispenses();
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(err?.error?.message ?? this.translate.instant('PHARMACY.DISPENSE_FAILED'));
      },
    });
  }

  cancelDispense(id: string): void {
    if (!confirm(this.translate.instant('PHARMACY.CANCEL_DISPENSE_CONFIRM'))) return;
    this.svc.cancelDispense(id).subscribe({
      next: () => {
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

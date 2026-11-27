import { allRows } from '../../shared/all-pages';
import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  signal,
  input,
  computed,
  untracked,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Router } from '@angular/router';
import { InBasketService, InBasketItem, InBasketSummary } from '../../services/in-basket.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextService } from '../../core/role-context.service';
import { RovingFocusDirective } from '../../shared/a11y/roving-focus.directive';
import { currentLocale } from '../../shared/i18n/app-locale';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { actionRoles } from '../../core/action-permissions';
import { AllergyReviewDialogComponent } from '../../shared/allergy-acknowledgement/allergy-review-dialog.component';
import { refusalReason } from '../../shared/refusal-reason';
import { ImagingPreAuthEntryComponent } from '../../billing/line-pre-authorisation/imaging-pre-auth-entry.component';

/** Policy 4: the notice of an allergy conflict awaiting the prescriber's review (its reference is the order). */
export const ALLERGY_REVIEW_REFERENCE = 'PRESCRIPTION_ALLERGY_REVIEW';

/**
 * Provisional platform rule: a closed visit's note still waiting for its author's signature (its reference is
 * the note). The item opens the visit, where the author signs; signing removes it.
 */
export const NOTE_TO_SIGN_REFERENCE = 'ENCOUNTER_NOTE_SIGNATURE';

/**
 * Insurer claims PR 1 (QA D3984): billing's follow-up of an urgent study done without its pre-authorisation number
 * (ImagingPreAuthorisationFollowUp.REFERENCE_TYPE; its reference is the imaging order). Answered in place.
 */
export const IMAGING_PREAUTH_REFERENCE = 'IMAGING_PREAUTH';

const SERVER_TIME = new ServerTimePipe();

@Component({
  selector: 'app-in-basket-panel',
  standalone: true,
  imports: [
    CommonModule,
    TranslateModule,
    RovingFocusDirective,
    AllergyReviewDialogComponent,
    ImagingPreAuthEntryComponent,
  ],
  templateUrl: './in-basket-panel.html',
  styleUrl: './in-basket-panel.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InBasketPanelComponent {
  private readonly inBasketService = inject(InBasketService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly roleContext = inject(RoleContextService);
  private readonly router = inject(Router);

  /** Only the latest load may write: a response issued under the previous scope is dropped. */
  private loadRequest = 0;

  /* ── state ── */
  readonly showTitle = input(true);
  items = signal<InBasketItem[]>([]);
  summary = signal<InBasketSummary>({
    totalUnread: 0,
    resultUnread: 0,
    orderUnread: 0,
    messageUnread: 0,
    taskUnread: 0,
  });
  loading = signal(false);
  loadError = signal(false);
  summaryError = signal(false);
  lastUpdated = signal<string | null>(null);
  activeFilter = signal<string | null>(null);

  /* ── derived ── */
  filteredItems = computed(() => {
    const filter = this.activeFilter();
    const all = this.items();
    if (!filter) return all;
    return all.filter((i) => i.itemType === filter);
  });

  badgeCount = computed(() => this.summary().totalUnread);

  /**
   * Policy 4 (pending clinical approval): the order whose allergy conflict is reviewed from its notice.
   * Offered to the roles POST /allergy-review admits; the server decides who may review.
   */
  readonly allergyReviewFor = signal<string | null>(null);
  private readonly canReviewAllergy = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('prescriptionAllergyReview')),
  );

  isAllergyReview(item: InBasketItem): boolean {
    return (
      item.referenceType === ALLERGY_REVIEW_REFERENCE &&
      !!item.referenceId &&
      this.canReviewAllergy()
    );
  }

  /** The roles that record a pre-authorisation number (RoleValidator#canRecordPreAuthorisation). */
  private readonly canRecordPreAuth = computed(() =>
    this.roleContext.hasAnyActiveRole([
      'ROLE_HOSPITAL_ADMIN',
      'ROLE_ACCOUNTANT',
      'ROLE_BILLING_SPECIALIST',
      'ROLE_CLAIMS_REVIEWER',
      'ROLE_RECEPTIONIST',
    ]),
  );

  isImagingPreAuth(item: InBasketItem): boolean {
    return (
      item.referenceType === IMAGING_PREAUTH_REFERENCE &&
      !!item.referenceId &&
      this.canRecordPreAuth()
    );
  }

  /** The server closed the follow-up task with the number: the list is read again. */
  onPreAuthRecorded(): void {
    this.loadData();
  }

  isNoteToSign(item: InBasketItem): boolean {
    return item.referenceType === NOTE_TO_SIGN_REFERENCE && !!item.encounterId;
  }

  openVisit(item: InBasketItem): void {
    this.router.navigate(['/encounters', item.encounterId]);
  }

  onAllergyReviewed(): void {
    this.allergyReviewFor.set(null);
    this.toast.success(this.translate.instant('ALLERGY_REVIEW.RECORDED'));
    this.loadData();
  }

  constructor() {
    // The load follows the hospital scope, not the mount: the endpoints are
    // hospital-scoped, and on /in-basket nothing rebuilds the panel when the
    // picker moves, so a load-once panel kept the old hospital's items and
    // unread badge beside a lab category showing the new one. The effect
    // covers the first render too. The previous hospital's rows are dropped
    // BEFORE the new read is issued, never shown under the new scope.
    effect(() => {
      this.roleContext.effectiveHospitalIdForRequest();
      untracked(() => {
        this.lastUpdated.set(null);
        this.items.set([]);
        this.summary.set({
          totalUnread: 0,
          resultUnread: 0,
          orderUnread: 0,
          messageUnread: 0,
          taskUnread: 0,
        });
        this.loadData();
      });
    });
  }

  /* ── public API ── */

  setFilter(type: string | null): void {
    this.activeFilter.set(type);
  }

  markRead(item: InBasketItem): void {
    if (item.status !== 'UNREAD') return;
    this.inBasketService.markAsRead(item.id).subscribe({
      next: (updated) => {
        this.patchItem(updated);
        this.loadSummary();
      },
      error: () =>
        this.toast.error(
          this.translate.instant('inBasket.error.markRead') || 'Failed to mark as read',
        ),
    });
  }

  acknowledgeItem(item: InBasketItem): void {
    if (item.status === 'ACKNOWLEDGED') return;
    this.inBasketService.acknowledge(item.id).subscribe({
      next: (updated) => {
        this.patchItem(updated);
        this.loadSummary();
        this.toast.success(this.translate.instant('inBasket.acknowledged') || 'Item acknowledged');
      },
      // K4: the server says why an acknowledgement is refused (a critical result needs its
      // read-back; an urgent clinical item needs a clinician), so the reason is shown.
      error: (err: unknown) =>
        this.toast.error(
          refusalReason(err) ??
            (this.translate.instant('inBasket.error.acknowledge') || 'Failed to acknowledge'),
        ),
    });
  }

  refresh(): void {
    this.loadData();
  }

  /* ── helpers ── */

  priorityClass(priority: string): string {
    switch (priority) {
      case 'CRITICAL':
        return 'priority-critical';
      case 'URGENT':
        return 'priority-urgent';
      default:
        return 'priority-normal';
    }
  }

  typeIcon(type: string): string {
    const icons: Record<string, string> = {
      RESULT: 'science',
      ORDER: 'prescriptions',
      MESSAGE: 'mail',
      TASK: 'task_alt',
    };
    return icons[type] ?? 'inbox';
  }

  formatDate(iso: string | null): string {
    if (!iso) return '';
    // QA D662 (D647): the server sends a zone-less UTC wall time; read raw,
    // the browser took it for local time and showed the UTC clock.
    const d = new Date(SERVER_TIME.transform(iso));
    // No hour12 override: the locale decides. Burkina Faso reads a 24-hour
    // clock; forcing 12-hour produced "15 juil., 10:00 AM" — French date,
    // American time.
    return d.toLocaleString(currentLocale(), {
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
    });
  }

  trackById(_index: number, item: InBasketItem): string {
    return item.id;
  }

  private loadData(): void {
    const request = ++this.loadRequest;
    this.loading.set(true);
    this.loadError.set(false);
    // QA D1347: every in-basket item (all pages), not the server's first 20.
    allRows((page, size) =>
      this.inBasketService.getItems(undefined, undefined, undefined, page, size),
    ).subscribe({
      next: (items) => {
        if (request !== this.loadRequest) return;
        this.items.set(items);
        this.lastUpdated.set(new Date().toISOString());
        this.loading.set(false);
      },
      error: () => {
        if (request === this.loadRequest) {
          this.loading.set(false);
          this.loadError.set(true);
        }
      },
    });
    this.loadSummary();
  }

  private loadSummary(): void {
    const request = this.loadRequest;
    this.summaryError.set(false);
    this.inBasketService.getSummary().subscribe({
      next: (s) => {
        if (request === this.loadRequest) this.summary.set(s);
      },
      error: () => {
        if (request === this.loadRequest) this.summaryError.set(true);
      },
    });
  }

  private patchItem(updated: InBasketItem): void {
    this.items.update((list) => list.map((i) => (i.id === updated.id ? updated : i)));
  }
}

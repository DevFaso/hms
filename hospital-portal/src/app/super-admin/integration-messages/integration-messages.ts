import { CommonModule, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  OnInit,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { catchError, of } from 'rxjs';

import { IntegrationMessagesService } from '../../services/integration-messages.service';
import {
  IntegrationMessageEvent,
  IntegrationMessagePage,
  IntegrationMessageStatus,
} from '../../services/integration-messages.model';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { toServerDateTime } from '../../shared/date-utils';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { KeyLabelPipe } from '../../shared/pipes/key-label.pipe';
import { DashPipe } from '../../shared/pipes/dash.pipe';
import { OrgTextPipe } from '../../shared/pipes/org-text.pipe';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { ToastService } from '../../core/toast.service';
import { refusalReason } from '../../shared/refusal-reason';
import { currentLocale } from '../../shared/i18n/app-locale';
import { ModalDialogDirective } from '../../shared/a11y/modal-dialog.directive';

const DEFAULT_PAGE_SIZE = 25;
const STATUS_OPTIONS: IntegrationMessageStatus[] = ['SENT', 'RECEIVED', 'FAILED', 'REPLAYED'];

interface ReplayState {
  messageId: string;
  busy: boolean;
  errorKey: string | null;
}

/**
 * MVP-c3 — operator surface for the Bridges-style message log + DLQ
 * + replay. Backed by `SuperAdminIntegrationMessageController` on the
 * server side; gated `ROLE_SUPER_ADMIN` via the route guard.
 */
@Component({
  selector: 'app-super-admin-integration-messages',
  standalone: true,
  imports: [
    DashPipe,
    KeyLabelPipe,
    DateTimePipe,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    RouterLink,
    TranslateModule,
    DatePipe,
    OrgTextPipe,
    ModalDialogDirective,
  ],
  templateUrl: './integration-messages.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './integration-messages.scss',
})
export class IntegrationMessagesComponent implements OnInit {
  private readonly service = inject(IntegrationMessagesService);
  private readonly confirm = inject(ConfirmService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly statusOptions = STATUS_OPTIONS;

  // ── search filters ─────────────────────────────────────────────────
  readonly integrationId = signal('');
  readonly organizationId = signal('');
  readonly status = signal<IntegrationMessageStatus | ''>('');
  readonly fromDate = signal('');
  readonly toDate = signal('');
  readonly pageNumber = signal(0);

  // ── result state ───────────────────────────────────────────────────
  readonly loading = signal(false);
  readonly errored = signal(false);
  readonly page = signal<IntegrationMessagePage | null>(null);
  readonly rows = computed<IntegrationMessageEvent[]>(() => this.page()?.content ?? []);
  readonly totalElements = computed(() => this.page()?.totalElements ?? 0);
  readonly totalPages = computed(() => this.page()?.totalPages ?? 0);
  readonly deadLetterCount = computed(() => this.page()?.deadLetterCount ?? 0);
  /** True only when the server says the sweep is actually running (not just configured). */
  readonly retentionActive = computed(() => this.page()?.retentionActive === true);
  readonly retentionOff = computed(() => this.page()?.retentionActive === false);
  readonly retentionDays = computed(() => this.page()?.payloadRetentionDays ?? null);
  readonly unresolvedMaxDays = computed(() => this.page()?.payloadUnresolvedMaxDays ?? null);
  readonly hasPrev = computed(() => this.pageNumber() > 0);
  readonly hasNext = computed(() => this.pageNumber() < this.totalPages() - 1);

  // ── replay state — keyed on messageId so multiple rows can be in
  //    flight without stomping on each other (a busy operator might
  //    fire several replays for a partner outage). ────────────────────
  readonly replayState = signal<Map<string, ReplayState>>(new Map());

  ngOnInit(): void {
    this.search();
  }

  search(): void {
    this.loading.set(true);
    this.errored.set(false);
    this.service
      .search({
        integrationId: this.integrationId().trim() || undefined,
        organizationId: this.organizationId().trim() || undefined,
        status: this.status() || undefined,
        // QA D777/D784: the picker gives local wall time; the server keeps UTC.
        fromDate: this.fromDate() ? toServerDateTime(this.fromDate()) : undefined,
        toDate: this.toDate() ? toServerDateTime(this.toDate()) : undefined,
        page: this.pageNumber(),
        size: DEFAULT_PAGE_SIZE,
      })
      .pipe(
        catchError(() => {
          this.errored.set(true);
          return of(null);
        }),
      )
      .subscribe((result) => {
        this.page.set(result);
        this.loading.set(false);
      });
  }

  /** DLQ-only filter — fast path operators reach for first. */
  showOnlyDeadLetters(): void {
    this.status.set('FAILED');
    this.pageNumber.set(0);
    this.search();
  }

  resetFilters(): void {
    this.integrationId.set('');
    this.organizationId.set('');
    this.status.set('');
    this.fromDate.set('');
    this.toDate.set('');
    this.pageNumber.set(0);
    this.search();
  }

  goToPage(target: number): void {
    if (target < 0 || target >= this.totalPages()) {
      return;
    }
    this.pageNumber.set(target);
    this.search();
  }

  /** Retention erased this row's content: it is shown as purged and cannot be replayed. */
  isPurged(row: IntegrationMessageEvent): boolean {
    return !!row.payloadPurgedAt;
  }

  /**
   * QA D4301: a failure is replayed once. One that a later attempt took over
   * (it was replayed, or the sender's retry got through) is shown as handled
   * and has no button - which is also what the banner counts.
   */
  canReplay(row: IntegrationMessageEvent): boolean {
    return row.status === 'FAILED' && !this.isPurged(row) && !row.resolved;
  }

  /** The status shown: a handled failure reads as such, not as one still waiting. */
  statusKey(row: { status: IntegrationMessageStatus; resolved?: boolean }): string {
    return row.status === 'FAILED' && row.resolved
      ? 'INTEGRATION_MESSAGES.STATUS.FAILED_RESOLVED'
      : `INTEGRATION_MESSAGES.STATUS.${row.status}`;
  }

  /** QA D4303: the product's own message types have words; a partner's (ADT^A04) is shown as sent. */
  typeLabel(messageType: string | null): string {
    if (!messageType) return '—';
    const key = `INTEGRATION_MESSAGES.TYPE.${messageType}`;
    const label = this.translate.instant(key);
    return label === key ? messageType : label;
  }

  /** French counts 0 and 1 in the singular; English and Spanish only 1. */
  readonly dlqBadgeKey = computed(() => {
    const count = this.deadLetterCount();
    const singular = currentLocale().startsWith('fr') ? count < 2 : count === 1;
    return singular ? 'INTEGRATION_MESSAGES.DLQ_BADGE_ONE' : 'INTEGRATION_MESSAGES.DLQ_BADGE';
  });

  // ── QA D4302: detail drawer ────────────────────────────────────────
  readonly detail = signal<IntegrationMessageEvent | null>(null);
  readonly detailLoading = signal(false);
  readonly detailErrored = signal(false);
  readonly detailOpen = signal(false);

  openDetail(row: IntegrationMessageEvent): void {
    this.detailOpen.set(true);
    this.detail.set(row);
    this.detailLoading.set(true);
    this.detailErrored.set(false);
    this.service
      .getById(row.id)
      .pipe(
        catchError(() => {
          this.detailErrored.set(true);
          return of(null);
        }),
      )
      .subscribe((full) => {
        // A slow answer for a row the operator has already left is dropped.
        if (this.detail()?.id !== row.id) return;
        if (full) this.detail.set(full);
        this.detailLoading.set(false);
      });
  }

  closeDetail(): void {
    this.detailOpen.set(false);
    this.detail.set(null);
  }

  async replay(messageId: string): Promise<void> {
    const row = this.rows().find((r) => r.id === messageId);
    if ((row && !this.canReplay(row)) || this.replayBusyFor(messageId)) {
      return;
    }
    // QA D4301: asked first, and said what it does.
    const confirmed = await this.confirm.ask({
      titleKey: 'INTEGRATION_MESSAGES.REPLAY_CONFIRM.TITLE',
      messageKey: 'INTEGRATION_MESSAGES.REPLAY_CONFIRM.MESSAGE',
      params: { integration: row?.integrationId ?? '' },
      confirmKey: 'INTEGRATION_MESSAGES.REPLAY',
    });
    // Asked again after the question: a second click may have started one meanwhile.
    if (!confirmed || this.replayBusyFor(messageId)) {
      return;
    }
    this.replayState.update((map) => {
      const next = new Map(map);
      next.set(messageId, { messageId, busy: true, errorKey: null });
      return next;
    });

    this.service
      .replay(messageId)
      .pipe(
        catchError((err: unknown) => {
          // 409 on a FAILED row means retention erased its content after
          // this page was loaded (the server re-checks under a row lock).
          // Say so, and reload so the row shows as purged and its Replay
          // button disables. Anything else keeps the generic error and the
          // row where the operator can see it.
          const purged = err instanceof HttpErrorResponse && err.status === 409;
          // The server's own reason when it gave one (already replayed, content
          // purged), in the reader's language.
          const reason =
            refusalReason(err) ??
            this.translate.instant('INTEGRATION_MESSAGES.ERROR.REPLAY_FAILED');
          this.toast.error(reason);
          this.replayState.update((map) => {
            const next = new Map(map);
            next.set(messageId, { messageId, busy: false, errorKey: reason });
            return next;
          });
          if (purged) {
            this.search();
          }
          return of(null);
        }),
      )
      .subscribe((result) => {
        if (!result) return;
        // Successful replay — clear the row's state and reload the
        // page so the new REPLAYED row appears at the top and the
        // DLQ counter ticks down.
        this.replayState.update((map) => {
          const next = new Map(map);
          next.delete(messageId);
          return next;
        });
        this.toast.success(this.translate.instant('INTEGRATION_MESSAGES.REPLAY_DONE'));
        if (this.detail()?.id === messageId) this.closeDetail();
        this.search();
      });
  }

  replayBusyFor(messageId: string): boolean {
    return this.replayState().get(messageId)?.busy === true;
  }

  replayErrorKeyFor(messageId: string): string | null {
    return this.replayState().get(messageId)?.errorKey ?? null;
  }
}

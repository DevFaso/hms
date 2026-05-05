import { CommonModule, DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { catchError, of } from 'rxjs';

import { IntegrationMessagesService } from '../../services/integration-messages.service';
import {
  IntegrationMessageEvent,
  IntegrationMessagePage,
  IntegrationMessageStatus,
} from '../../services/integration-messages.model';

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
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule, DatePipe],
  templateUrl: './integration-messages.html',
  styleUrl: './integration-messages.scss',
})
export class IntegrationMessagesComponent implements OnInit {
  private readonly service = inject(IntegrationMessagesService);

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
        fromDate: this.fromDate() || undefined,
        toDate: this.toDate() || undefined,
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

  replay(messageId: string): void {
    this.replayState.update((map) => {
      const next = new Map(map);
      next.set(messageId, { messageId, busy: true, errorKey: null });
      return next;
    });

    this.service
      .replay(messageId)
      .pipe(
        catchError(() => {
          this.replayState.update((map) => {
            const next = new Map(map);
            next.set(messageId, {
              messageId,
              busy: false,
              errorKey: 'INTEGRATION_MESSAGES.ERROR.REPLAY_FAILED',
            });
            return next;
          });
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

import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import {
  InstrumentOutboxService,
  OutboxMessage,
  OutboxPage,
  OutboxStatus,
  OutboxTransport,
} from '../../services/instrument-outbox.service';
import { AuthService } from '../../auth/auth.service';
import { ToastService } from '../../core/toast.service';

/**
 * Instrument outbox monitor (P2 #17).
 *
 * Every outbound HL7 message for the caller's hospital, with its delivery
 * state — the V119 tracking columns had no reader anywhere, so a permanently
 * failed interface was indistinguishable from a working one. ERROR rows can be
 * requeued from here; nothing else in the system can move a parked row.
 */
@Component({
  selector: 'app-lab-outbox',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './lab-outbox.html',
  styleUrl: './lab-outbox.scss',
})
export class LabOutboxComponent implements OnInit {
  private readonly outboxService = inject(InstrumentOutboxService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  loading = signal(true);
  error = signal<string | null>(null);
  page = signal<OutboxPage | null>(null);
  transport = signal<OutboxTransport | null>(null);

  statusFilter = signal<OutboxStatus | ''>('');
  pageIndex = signal(0);
  readonly pageSize = 25;

  /** Message id currently being requeued, so the button can't double-fire. */
  retryingId = signal<string | null>(null);

  detail = signal<{ message: OutboxMessage; siblings: OutboxMessage[] } | null>(null);
  detailLoading = signal(false);

  rows = computed(() => this.page()?.content ?? []);
  totalPages = computed(() => {
    const p = this.page();
    return p ? Math.max(1, Math.ceil(p.totalElements / p.size)) : 1;
  });

  /** Mirrors the backend RETRY_ROLES on POST /{id}/retry. */
  canRetry = computed(() => {
    const roles = this.auth.getRoles();
    return (
      roles.includes('ROLE_LAB_MANAGER') ||
      roles.includes('ROLE_LAB_DIRECTOR') ||
      roles.includes('ROLE_HOSPITAL_ADMIN') ||
      roles.includes('ROLE_SUPER_ADMIN')
    );
  });

  ngOnInit(): void {
    this.load();
    this.outboxService.getTransport().subscribe({
      next: (t) => this.transport.set(t),
      // The queue is still useful without the banner; don't fail the page.
      error: () => this.transport.set(null),
    });
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.outboxService.search(this.statusFilter(), this.pageIndex(), this.pageSize).subscribe({
      next: (result) => {
        this.page.set(result);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Failed to load instrument outbox', err);
        this.error.set(this.translate.instant('LAB_OUTBOX.LOAD_ERROR'));
        this.loading.set(false);
      },
    });
  }

  setStatusFilter(value: OutboxStatus | ''): void {
    this.statusFilter.set(value);
    this.pageIndex.set(0);
    this.load();
  }

  previousPage(): void {
    if (this.pageIndex() > 0) {
      this.pageIndex.set(this.pageIndex() - 1);
      this.load();
    }
  }

  nextPage(): void {
    if (this.pageIndex() + 1 < this.totalPages()) {
      this.pageIndex.set(this.pageIndex() + 1);
      this.load();
    }
  }

  openDetail(row: OutboxMessage): void {
    this.detailLoading.set(true);
    this.detail.set(null);
    forkJoin({
      message: this.outboxService.getMessage(row.id),
      siblings: row.labOrderId
        ? this.outboxService
            .getMessagesForOrder(row.labOrderId)
            .pipe(catchError(() => of([] as OutboxMessage[])))
        : of([] as OutboxMessage[]),
    }).subscribe({
      next: ({ message, siblings }) => {
        this.detail.set({ message, siblings: siblings.filter((s) => s.id !== message.id) });
        this.detailLoading.set(false);
      },
      error: (err) => {
        console.error('Failed to load outbox message', err);
        this.toast.error(this.translate.instant('LAB_OUTBOX.DETAIL_ERROR'));
        this.detailLoading.set(false);
      },
    });
  }

  closeDetail(): void {
    this.detail.set(null);
    this.detailLoading.set(false);
  }

  retry(row: OutboxMessage): void {
    if (this.retryingId()) {
      return;
    }
    // Set the in-flight guard BEFORE dispatching — a guard read after the
    // service call has fired guards nothing (the #443 lesson).
    this.retryingId.set(row.id);
    this.outboxService.retry(row.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB_OUTBOX.RETRIED'));
        this.retryingId.set(null);
        this.load();
      },
      error: (err) => {
        // The backend refusal names the actual state — surface it verbatim
        // rather than a generic failure.
        const message = err?.error?.message ?? this.translate.instant('LAB_OUTBOX.RETRY_ERROR');
        this.toast.error(message);
        this.retryingId.set(null);
      },
    });
  }
}

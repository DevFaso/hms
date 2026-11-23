import { refusalReason } from '../../shared/refusal-reason';
import { ModalDialogDirective } from '../../shared/a11y/modal-dialog.directive';
import {
  Component,
  OnInit,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
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
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { LabValuePipe } from '../../shared/pipes/lab-value.pipe';
import { LabCorrectionBadgeComponent } from '../shared/lab-correction-badge.component';
import {
  flagKey,
  hl7Readable,
  hl7SegmentLines,
  messageTypeKey,
  parseHl7Summary,
  resultStatusKey,
} from './hl7-summary';

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
  imports: [
    ModalDialogDirective,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    LabValuePipe,
    LabCorrectionBadgeComponent,
  ],
  templateUrl: './lab-outbox.html',
  changeDetection: ChangeDetectionStrategy.Eager,
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

  /** Display-only reading of the open message; null = show the raw text only. */
  detailSummary = computed(() => {
    const d = this.detail();
    return d ? parseHl7Summary(d.message.payload) : null;
  });
  /** One line per HL7 segment (CR, LF or CRLF), never the stored payload rewritten. */
  detailLines = computed(() => hl7SegmentLines(this.detail()?.message.payload));
  /**
   * Why the open message is still waiting: the transport is off, or the last
   * delivery error. Null once it has been acknowledged.
   */
  waitingReason = computed((): { key: string; params?: Record<string, string> } | null => {
    const d = this.detail();
    if (!d || d.message.status === 'ACK' || d.message.status === 'SENT') {
      return null;
    }
    if (this.transport()?.enabled === false) {
      return { key: 'LAB_OUTBOX.WAITING_TRANSPORT_OFF' };
    }
    if (d.message.lastError) {
      return { key: 'LAB_OUTBOX.WAITING_LAST_ERROR', params: { error: d.message.lastError } };
    }
    return d.message.status === 'PENDING' ? { key: 'LAB_OUTBOX.WAITING_NEXT_ATTEMPT' } : null;
  });

  readonly messageTypeKey = messageTypeKey;
  readonly resultStatusKey = resultStatusKey;
  readonly flagKey = flagKey;

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
        this.toast.error(refusalReason(err) ?? this.translate.instant('LAB_OUTBOX.DETAIL_ERROR'));
        this.detailLoading.set(false);
      },
    });
  }

  /** Copies the message with one segment per line (LF), ready to paste into a ticket. */
  copyPayload(): void {
    const payload = this.detail()?.message.payload;
    if (!payload) {
      return;
    }
    const done = () => this.toast.success(this.translate.instant('LAB_OUTBOX.COPIED'));
    const failed = () => this.toast.error(this.translate.instant('LAB_OUTBOX.COPY_ERROR'));
    try {
      navigator.clipboard.writeText(hl7Readable(payload)).then(done, failed);
    } catch {
      failed();
    }
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
        const message = refusalReason(err) ?? this.translate.instant('LAB_OUTBOX.RETRY_ERROR');
        this.toast.error(message);
        this.retryingId.set(null);
      },
    });
  }
}

import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  DeliveryEvent,
  DeliveryStatus,
  DeliveryStatusFilter,
  PatientNotificationDelivery,
  PatientNotificationDeliveryPage,
  PatientNotificationDeliveryService,
} from '../services/patient-notification-delivery.service';
import { ToastService } from '../core/toast.service';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { refusalReason } from '../shared/refusal-reason';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';

/**
 * Codes after which the patient may already have the message: the provider gave no answer, or every
 * attempt ran out without one recorded. Retrying them can text the patient twice, so it is asked.
 */
const MAYBE_DELIVERED: ReadonlySet<string> = new Set(['OUTCOME_UNKNOWN', 'ATTEMPTS_EXHAUSTED']);

/** Every status, in the order the counters show them. */
export const DELIVERY_STATUSES: readonly DeliveryStatus[] = [
  'FAILED',
  'RETRYING',
  'PENDING',
  'SENT',
  'SKIPPED',
];

const STATUS_KEYS: Record<DeliveryStatus, string> = {
  PENDING: 'PATIENT_NOTIFICATIONS.STATUS.PENDING',
  RETRYING: 'PATIENT_NOTIFICATIONS.STATUS.RETRYING',
  SENT: 'PATIENT_NOTIFICATIONS.STATUS.SENT',
  FAILED: 'PATIENT_NOTIFICATIONS.STATUS.FAILED',
  SKIPPED: 'PATIENT_NOTIFICATIONS.STATUS.SKIPPED',
};

const EVENT_KEYS: Record<DeliveryEvent, string> = {
  APPOINTMENT_CONFIRMATION: 'PATIENT_NOTIFICATIONS.EVENT.APPOINTMENT_CONFIRMATION',
  APPOINTMENT_REMINDER: 'PATIENT_NOTIFICATIONS.EVENT.APPOINTMENT_REMINDER',
  LAB_RESULT_AVAILABLE: 'PATIENT_NOTIFICATIONS.EVENT.LAB_RESULT_AVAILABLE',
  IMAGING_RESULT_AVAILABLE: 'PATIENT_NOTIFICATIONS.EVENT.IMAGING_RESULT_AVAILABLE',
  ULTRASOUND_RESULT_AVAILABLE: 'PATIENT_NOTIFICATIONS.EVENT.ULTRASOUND_RESULT_AVAILABLE',
  APPOINTMENT_RESCHEDULED: 'PATIENT_NOTIFICATIONS.EVENT.APPOINTMENT_RESCHEDULED',
  APPOINTMENT_CANCELLED: 'PATIENT_NOTIFICATIONS.EVENT.APPOINTMENT_CANCELLED',
  APPOINTMENT_COMPLETED: 'PATIENT_NOTIFICATIONS.EVENT.APPOINTMENT_COMPLETED',
  APPOINTMENT_NO_SHOW: 'PATIENT_NOTIFICATIONS.EVENT.APPOINTMENT_NO_SHOW',
  WAITLIST_OFFER: 'PATIENT_NOTIFICATIONS.EVENT.WAITLIST_OFFER',
  RECALL_DUE: 'PATIENT_NOTIFICATIONS.EVENT.RECALL_DUE',
  VISIT_SUMMARY: 'PATIENT_NOTIFICATIONS.EVENT.VISIT_SUMMARY',
  PHARMACY_DISPENSED: 'PATIENT_NOTIFICATIONS.EVENT.PHARMACY_DISPENSED',
  PHARMACY_READY: 'PATIENT_NOTIFICATIONS.EVENT.PHARMACY_READY',
  PHARMACY_READY_CANCELLED: 'PATIENT_NOTIFICATIONS.EVENT.PHARMACY_READY_CANCELLED',
  PHARMACY_READY_REMINDER: 'PATIENT_NOTIFICATIONS.EVENT.PHARMACY_READY_REMINDER',
  PHARMACY_OUT_OF_STOCK: 'PATIENT_NOTIFICATIONS.EVENT.PHARMACY_OUT_OF_STOCK',
  PHARMACY_REFILL_REMINDER: 'PATIENT_NOTIFICATIONS.EVENT.PHARMACY_REFILL_REMINDER',
  INVOICE: 'PATIENT_NOTIFICATIONS.EVENT.INVOICE',
};

const CHANNEL_KEYS: Record<string, string> = {
  SMS: 'PATIENT_NOTIFICATIONS.CHANNEL.SMS',
  EMAIL: 'PATIENT_NOTIFICATIONS.CHANNEL.EMAIL',
};

/** The fixed codes of PatientNotificationErrorCode (hospital-core). */
const ERROR_KEYS: Record<string, string> = {
  PROVIDER_REJECTED: 'PATIENT_NOTIFICATIONS.ERROR.PROVIDER_REJECTED',
  PROVIDER_AUTH_FAILED: 'PATIENT_NOTIFICATIONS.ERROR.PROVIDER_AUTH_FAILED',
  PROVIDER_UNREACHABLE: 'PATIENT_NOTIFICATIONS.ERROR.PROVIDER_UNREACHABLE',
  OUTCOME_UNKNOWN: 'PATIENT_NOTIFICATIONS.ERROR.OUTCOME_UNKNOWN',
  TRANSPORT_UNAVAILABLE: 'PATIENT_NOTIFICATIONS.ERROR.TRANSPORT_UNAVAILABLE',
  RECIPIENT_NOT_SUPPORTED: 'PATIENT_NOTIFICATIONS.ERROR.RECIPIENT_NOT_SUPPORTED',
  INVALID_ADDRESS: 'PATIENT_NOTIFICATIONS.ERROR.INVALID_ADDRESS',
  MAIL_SEND_FAILED: 'PATIENT_NOTIFICATIONS.ERROR.MAIL_SEND_FAILED',
  SEND_FAILED: 'PATIENT_NOTIFICATIONS.ERROR.SEND_FAILED',
  ATTEMPTS_EXHAUSTED: 'PATIENT_NOTIFICATIONS.ERROR.ATTEMPTS_EXHAUSTED',
  RECIPIENT_NOT_ALLOWED: 'PATIENT_NOTIFICATIONS.ERROR.RECIPIENT_NOT_ALLOWED',
  OPTED_OUT: 'PATIENT_NOTIFICATIONS.ERROR.OPTED_OUT',
  NO_CONTACT: 'PATIENT_NOTIFICATIONS.ERROR.NO_CONTACT',
  RECIPIENT_UNVERIFIED: 'PATIENT_NOTIFICATIONS.ERROR.RECIPIENT_UNVERIFIED',
  SUBJECT_WITHDRAWN: 'PATIENT_NOTIFICATIONS.ERROR.SUBJECT_WITHDRAWN',
  PATIENT_DECEASED: 'PATIENT_NOTIFICATIONS.ERROR.PATIENT_DECEASED',
};

/**
 * Patient notification sends (V312, scorecard K3). A hospital administrator
 * sees which appointment and result notices failed to reach patients, why
 * (a fixed code, never the number or the text), and can put a failed one back
 * in the queue. Signal-driven, hence OnPush.
 */
@Component({
  selector: 'app-patient-notifications',
  standalone: true,
  imports: [DatePipe, FormsModule, TranslateModule, ServerTimePipe],
  templateUrl: './patient-notifications.html',
  styleUrl: './patient-notifications.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PatientNotificationsComponent implements OnInit {
  private readonly service = inject(PatientNotificationDeliveryService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly confirm = inject(ConfirmService);

  readonly statuses = DELIVERY_STATUSES;
  readonly pageSize = 25;

  loading = signal(true);
  error = signal<string | null>(null);
  page = signal<PatientNotificationDeliveryPage | null>(null);
  statusFilter = signal<DeliveryStatusFilter>('FAILED');
  pageIndex = signal(0);
  /** Send being requeued, so the button cannot double-fire. */
  retryingId = signal<string | null>(null);

  rows = computed(() => this.page()?.content ?? []);
  totalPages = computed(() => {
    const p = this.page();
    return p ? Math.max(1, Math.ceil(p.totalElements / p.size)) : 1;
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.service.search(this.statusFilter(), this.pageIndex(), this.pageSize).subscribe({
      next: (result) => {
        this.page.set(result);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(
          refusalReason(err) ?? this.translate.instant('PATIENT_NOTIFICATIONS.LOAD_ERROR'),
        );
        this.loading.set(false);
      },
    });
  }

  setStatusFilter(value: DeliveryStatusFilter): void {
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

  count(status: DeliveryStatus): number {
    return this.page()?.countsByStatus?.[status] ?? 0;
  }

  statusKey(status: DeliveryStatus): string {
    return STATUS_KEYS[status] ?? 'PATIENT_NOTIFICATIONS.UNKNOWN';
  }

  eventKey(event: DeliveryEvent): string {
    return EVENT_KEYS[event] ?? 'PATIENT_NOTIFICATIONS.UNKNOWN';
  }

  channelKey(channel: string): string {
    return CHANNEL_KEYS[channel] ?? 'PATIENT_NOTIFICATIONS.UNKNOWN';
  }

  errorKey(code: string | null | undefined): string | null {
    if (!code) {
      return null;
    }
    return ERROR_KEYS[code] ?? 'PATIENT_NOTIFICATIONS.ERROR.SEND_FAILED';
  }

  /**
   * QA D2402: the statuses the current filter leaves out, with how many rows each holds, so the page
   * says so (the default « En échec » filter hid every « Non envoyé » row without a word).
   */
  readonly hiddenByFilter = computed(() => {
    const filter = this.statusFilter();
    if (filter === 'ALL') return 0;
    return this.statuses
      .filter((status) => status !== filter)
      .reduce((sum, status) => sum + this.count(status), 0);
  });

  /** QA D2402: the date a row shows: its last attempt, or when it was recorded (an opt-out has no attempt). */
  rowDate(row: PatientNotificationDelivery): string {
    return row.lastAttemptAt ?? row.createdAt;
  }

  /** QA D2402: what the notice was about: the appointment, the invoice or the lab order. */
  subject(
    row: PatientNotificationDelivery,
  ): { key: string; params: Record<string, string> } | null {
    if (row.subjectDate) {
      return {
        key: 'PATIENT_NOTIFICATIONS.SUBJECT_APPOINTMENT',
        params: {
          date: row.subjectDate.split('-').reverse().join('/'),
          time: (row.subjectTime ?? '').slice(0, 5),
        },
      };
    }
    if (!row.subjectReference) return null;
    return {
      key:
        row.eventType === 'INVOICE'
          ? 'PATIENT_NOTIFICATIONS.SUBJECT_INVOICE'
          : 'PATIENT_NOTIFICATIONS.SUBJECT_LAB_ORDER',
      params: { number: row.subjectReference },
    };
  }

  /** Whether the patient may already have received this message. */
  maybeDelivered(row: PatientNotificationDelivery): boolean {
    return !!row.errorCode && MAYBE_DELIVERED.has(row.errorCode);
  }

  async retry(row: PatientNotificationDelivery): Promise<void> {
    if (this.retryingId() || !row.retryable) {
      return;
    }
    if (
      this.maybeDelivered(row) &&
      !(await this.confirm.ask({
        titleKey: 'PATIENT_NOTIFICATIONS.RETRY_CONFIRM_TITLE',
        messageKey: 'PATIENT_NOTIFICATIONS.RETRY_CONFIRM_MESSAGE',
        confirmKey: 'PATIENT_NOTIFICATIONS.RETRY',
      }))
    ) {
      return;
    }
    // The in-flight guard is set BEFORE the call (the #443 lesson).
    this.retryingId.set(row.id);
    this.service.retry(row.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PATIENT_NOTIFICATIONS.RETRIED'));
        this.retryingId.set(null);
        this.load();
      },
      error: (err) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PATIENT_NOTIFICATIONS.RETRY_ERROR'),
        );
        this.retryingId.set(null);
      },
    });
  }
}

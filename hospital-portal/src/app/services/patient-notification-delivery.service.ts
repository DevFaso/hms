import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

/**
 * Patient notification sends (V312, go-live scorecard K3; QA D2206 adds the opt-out trace of the
 * messages sent inline: appointment e-mails, visit summary, pharmacy SMS, outreach, invoice): the SMS and email
 * appointment confirmations, reminders and result-available notices the
 * hospital sent its patients, failed ones first, with a manual retry. Mirrors
 * PatientNotificationDeliveryController. Never the message, the number or the
 * address: only who, what kind, which channel and how it went.
 */

export type DeliveryStatus = 'PENDING' | 'RETRYING' | 'SENT' | 'FAILED' | 'SKIPPED';

export type DeliveryStatusFilter = DeliveryStatus | 'ALL';

export type DeliveryEvent =
  | 'APPOINTMENT_CONFIRMATION'
  | 'APPOINTMENT_REMINDER'
  | 'LAB_RESULT_AVAILABLE'
  | 'IMAGING_RESULT_AVAILABLE'
  | 'ULTRASOUND_RESULT_AVAILABLE'
  | 'APPOINTMENT_RESCHEDULED'
  | 'APPOINTMENT_CANCELLED'
  | 'APPOINTMENT_COMPLETED'
  | 'APPOINTMENT_NO_SHOW'
  | 'WAITLIST_OFFER'
  | 'RECALL_DUE'
  | 'VISIT_SUMMARY'
  | 'PHARMACY_DISPENSED'
  | 'PHARMACY_READY'
  | 'PHARMACY_READY_CANCELLED'
  | 'PHARMACY_READY_REMINDER'
  | 'PHARMACY_OUT_OF_STOCK'
  | 'PHARMACY_REFILL_REMINDER'
  | 'INVOICE';

export interface PatientNotificationDelivery {
  id: string;
  patientId: string;
  patientName: string;
  eventType: DeliveryEvent;
  channel: 'SMS' | 'EMAIL';
  status: DeliveryStatus;
  attempts: number;
  /** A fixed code (PROVIDER_UNREACHABLE, …), never a transport message. */
  errorCode?: string | null;
  retryable: boolean;
  manualRetries: number;
  createdAt: string;
  lastAttemptAt?: string | null;
  nextAttemptAt?: string | null;
  sentAt?: string | null;
  lastRetriedAt?: string | null;
  /** QA D2402: the invoice number or the lab order number the notice was about. */
  subjectReference?: string | null;
  /** QA D2402: the appointment's day (YYYY-MM-DD) and start time (HH:mm[:ss]). */
  subjectDate?: string | null;
  subjectTime?: string | null;
}

export interface PatientNotificationDeliveryPage {
  content: PatientNotificationDelivery[];
  page: number;
  size: number;
  totalElements: number;
  countsByStatus: Record<DeliveryStatus, number>;
}

interface ApiWrapper<T> {
  data: T;
}

@Injectable({ providedIn: 'root' })
export class PatientNotificationDeliveryService {
  private readonly http = inject(HttpClient);

  search(
    status: DeliveryStatusFilter,
    page = 0,
    size = 25,
  ): Observable<PatientNotificationDeliveryPage> {
    const params = new HttpParams().set('status', status).set('page', page).set('size', size);
    return this.http
      .get<ApiWrapper<PatientNotificationDeliveryPage>>('/patient-notification-deliveries', {
        params,
      })
      .pipe(map((res) => res.data));
  }

  retry(id: string): Observable<PatientNotificationDelivery> {
    return this.http
      .post<ApiWrapper<PatientNotificationDelivery>>(
        `/patient-notification-deliveries/${id}/retry`,
        {},
      )
      .pipe(map((res) => res.data));
  }
}

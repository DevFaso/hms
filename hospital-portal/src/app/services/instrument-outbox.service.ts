import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

/**
 * Outbound HL7 instrument-outbox monitor (P2 #17).
 *
 * The queue and its delivery tracking (attempts / last_error / last_attempt_at,
 * V119) existed backend-side with no reader anywhere — an interface could fail
 * permanently and the only evidence was a DB row nobody could see.
 */

export type OutboxStatus = 'PENDING' | 'SENT' | 'ACK' | 'ERROR';

export interface OutboxMessage {
  id: string;
  labOrderId?: string;
  messageType: string;
  /** Full HL7 text. Present only on single-message reads — list rows elide it. */
  payload?: string;
  status: OutboxStatus;
  createdAt: string;
  sentAt?: string;
  attempts: number;
  lastError?: string;
  lastAttemptAt?: string;
}

export interface OutboxPage {
  content: OutboxMessage[];
  page: number;
  size: number;
  totalElements: number;
  pendingCount: number;
  errorCount: number;
  ackCount: number;
}

export interface OutboxTransport {
  enabled: boolean;
  host: string;
  port: number;
  maxAttempts: number;
  retryAfterSeconds: number;
  batchSize: number;
}

interface ApiWrapper<T> {
  data: T;
}

@Injectable({ providedIn: 'root' })
export class InstrumentOutboxService {
  private readonly http = inject(HttpClient);

  search(status?: OutboxStatus | '', page = 0, size = 25): Observable<OutboxPage> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) {
      params = params.set('status', status);
    }
    return this.http
      .get<ApiWrapper<OutboxPage>>('/lab-instrument-outbox', { params })
      .pipe(map((res) => res.data));
  }

  getMessage(id: string): Observable<OutboxMessage> {
    return this.http
      .get<ApiWrapper<OutboxMessage>>(`/lab-instrument-outbox/${id}`)
      .pipe(map((res) => res.data));
  }

  getMessagesForOrder(labOrderId: string): Observable<OutboxMessage[]> {
    return this.http
      .get<ApiWrapper<OutboxMessage[]>>(`/lab-instrument-outbox/orders/${labOrderId}`)
      .pipe(map((res) => res.data));
  }

  retry(id: string): Observable<OutboxMessage> {
    return this.http
      .post<ApiWrapper<OutboxMessage>>(`/lab-instrument-outbox/${id}/retry`, {})
      .pipe(map((res) => res.data));
  }

  getTransport(): Observable<OutboxTransport> {
    return this.http
      .get<ApiWrapper<OutboxTransport>>('/lab-instrument-outbox/transport')
      .pipe(map((res) => res.data));
  }
}

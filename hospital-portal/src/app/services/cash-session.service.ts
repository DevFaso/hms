import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Billing-money plan PR 10: the till a movement is taken at. */
export type CashTill = 'BILLING' | 'RECEPTION' | 'PHARMACY';
/** OPEN takes money; CLOSED is counted and locked; REVIEWED is checked by a supervisor. */
export type CashSessionStatus = 'OPEN' | 'CLOSED' | 'REVIEWED';

/** One method (and mobile-money operator) of a session: money in, money out, expected in the till. */
export interface CashMethodLine {
  method: string;
  operator?: string | null;
  inCount: number;
  inAmount: number;
  outCount: number;
  outAmount: number;
  expected: number;
}

/** One counted line of one close round. */
export interface CashCount {
  closeRound: number;
  method: string;
  operator?: string | null;
  expected: number;
  counted: number;
  difference: number;
  explanation?: string | null;
}

/** One money movement of a session. */
export interface CashMovement {
  id: string;
  kind: 'PAYMENT' | 'COPAY' | 'PHARMACY_PAYMENT' | 'COUNTER_SALE' | 'REFUND' | 'REVERSAL';
  direction: 'IN' | 'OUT';
  at?: string | null;
  document?: string | null;
  patientName?: string | null;
  method?: string | null;
  operator?: string | null;
  reference?: string | null;
  amount: number;
  status?: string | null;
}

/** Billing-money plan PR 10 (QA D2325, D2714, D2672): one cashier's till for one day (CashSessionDTO). */
export interface CashSession {
  id: string;
  till: CashTill;
  cashierId: string;
  cashierName?: string | null;
  businessDate: string;
  status: CashSessionStatus;
  openedAt?: string | null;
  closeRound: number;
  closedAt?: string | null;
  closedByName?: string | null;
  reviewedAt?: string | null;
  reviewedByName?: string | null;
  reviewNote?: string | null;
  reopenedAt?: string | null;
  reopenedByName?: string | null;
  reopenReason?: string | null;
  /** Whether the reader is this session's cashier (only they close it). */
  mine: boolean;
  methods: CashMethodLine[];
  totalIn: number;
  totalOut: number;
  totalExpected: number;
  /** QA D2714: the physical cash expected in the drawer (cash in − cash out). */
  cashToCount: number;
  totalDifference?: number | null;
  counts: CashCount[];
  movements: CashMovement[];
}

export interface CashCountedLine {
  method: string;
  operator?: string | null;
  counted: number | null;
  explanation?: string;
}

/** Billing-money plan PR 10: the tills (« Ma caisse du jour », « Caisses du jour », the day report). */
@Injectable({ providedIn: 'root' })
export class CashSessionService {
  private readonly http = inject(HttpClient);

  /** The signed-in user's sessions of a day, every till they hold. */
  mine(date?: string): Observable<CashSession[]> {
    return this.http.get<CashSession[]>('/cash-sessions/mine', { params: dayParams(date) });
  }

  /** Every session of the hospital on a day (hospital admin, accountant). */
  list(date?: string, till?: CashTill): Observable<CashSession[]> {
    let params = dayParams(date);
    if (till) params = params.set('till', till);
    return this.http.get<CashSession[]>('/cash-sessions', { params });
  }

  get(id: string): Observable<CashSession> {
    return this.http.get<CashSession>(`/cash-sessions/${id}`);
  }

  close(id: string, counts: CashCountedLine[]): Observable<CashSession> {
    return this.http.post<CashSession>(`/cash-sessions/${id}/close`, { counts });
  }

  reopen(id: string, note: string): Observable<CashSession> {
    return this.http.post<CashSession>(`/cash-sessions/${id}/reopen`, { note });
  }

  review(id: string, note: string): Observable<CashSession> {
    return this.http.post<CashSession>(`/cash-sessions/${id}/review`, { note });
  }

  sessionPdf(id: string): Observable<HttpResponse<Blob>> {
    return this.http.get(`/cash-sessions/${id}/pdf`, { responseType: 'blob', observe: 'response' });
  }

  dayReportPdf(date?: string): Observable<HttpResponse<Blob>> {
    return this.http.get('/cash-sessions/day-report.pdf', {
      params: dayParams(date),
      responseType: 'blob',
      observe: 'response',
    });
  }

  journalCsv(date?: string): Observable<HttpResponse<Blob>> {
    return this.http.get('/cash-sessions/day-report.csv', {
      params: dayParams(date),
      responseType: 'blob',
      observe: 'response',
    });
  }
}

function dayParams(date?: string): HttpParams {
  let params = new HttpParams();
  if (date) params = params.set('date', date);
  return params;
}

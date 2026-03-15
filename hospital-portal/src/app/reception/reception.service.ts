import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ReceptionDashboardSummary {
  date: string;
  hospitalId: string;
  scheduledToday: number;
  arrivedCount: number;
  waitingCount: number;
  inProgressCount: number;
  noShowCount: number;
  completedCount: number;
  walkInCount: number;
}

export type QueueStatus =
  | 'SCHEDULED'
  | 'CONFIRMED'
  | 'ARRIVED'
  | 'IN_PROGRESS'
  | 'NO_SHOW'
  | 'COMPLETED'
  | 'WALK_IN'
  | 'CANCELLED'
  | string;

export interface ReceptionQueueItem {
  appointmentId: string | null;
  patientId: string;
  patientName: string;
  mrn: string | null;
  dateOfBirth: string | null;
  appointmentTime: string | null;
  providerName: string | null;
  departmentName: string | null;
  appointmentReason: string | null;
  status: QueueStatus;
  waitMinutes: number;
  encounterId: string | null;
  hasInsuranceIssue: boolean;
  hasOutstandingBalance: boolean;
}

export interface InsuranceSummary {
  hasActiveCoverage: boolean;
  primaryPayer: string | null;
  policyNumber: string | null;
  expiresOn: string | null;
  expired: boolean;
  hasPrimary: boolean;
}

export interface BillingSummary {
  openInvoiceCount: number;
  totalBalanceDue: number;
}

export interface AlertFlags {
  incompleteDemographics: boolean;
  missingInsurance: boolean;
  expiredInsurance: boolean;
  noPrimaryInsurance: boolean;
  outstandingBalance: boolean;
}

export interface FrontDeskPatientSnapshot {
  patientId: string;
  fullName: string;
  mrn: string | null;
  dob: string | null;
  phone: string | null;
  email: string | null;
  address: string | null;
  insurance: InsuranceSummary;
  billing: BillingSummary;
  alerts: AlertFlags;
}

export interface InsuranceIssue {
  appointmentId: string | null;
  patientId: string;
  patientName: string;
  mrn: string | null;
  appointmentTime: string | null;
  issueType: 'MISSING_INSURANCE' | 'EXPIRED_INSURANCE' | 'NO_PRIMARY';
  providerName: string | null;
  departmentName: string | null;
}

export interface FlowBoard {
  scheduled: ReceptionQueueItem[];
  confirmed: ReceptionQueueItem[];
  arrived: ReceptionQueueItem[];
  inProgress: ReceptionQueueItem[];
  noShow: ReceptionQueueItem[];
  completed: ReceptionQueueItem[];
  walkIn: ReceptionQueueItem[];
}

@Injectable({ providedIn: 'root' })
export class ReceptionService {
  private readonly http = inject(HttpClient);
  private readonly base = '/reception';

  getDashboardSummary(date?: string): Observable<ReceptionDashboardSummary> {
    let params = new HttpParams();
    if (date) params = params.set('date', date);
    return this.http.get<ReceptionDashboardSummary>(`${this.base}/dashboard/summary`, { params });
  }

  getQueue(opts?: {
    date?: string;
    status?: string;
    departmentId?: string;
    providerId?: string;
  }): Observable<ReceptionQueueItem[]> {
    let params = new HttpParams();
    if (opts?.date) params = params.set('date', opts.date);
    if (opts?.status) params = params.set('status', opts.status);
    if (opts?.departmentId) params = params.set('departmentId', opts.departmentId);
    if (opts?.providerId) params = params.set('providerId', opts.providerId);
    return this.http.get<ReceptionQueueItem[]>(`${this.base}/queue`, { params });
  }

  getPatientSnapshot(patientId: string): Observable<FrontDeskPatientSnapshot> {
    return this.http.get<FrontDeskPatientSnapshot>(`${this.base}/patients/${patientId}/snapshot`);
  }

  getInsuranceIssues(date?: string): Observable<InsuranceIssue[]> {
    let params = new HttpParams();
    if (date) params = params.set('date', date);
    return this.http.get<InsuranceIssue[]>(`${this.base}/insurance/issues`, { params });
  }

  getPaymentsPending(date?: string): Observable<ReceptionQueueItem[]> {
    let params = new HttpParams();
    if (date) params = params.set('date', date);
    return this.http.get<ReceptionQueueItem[]>(`${this.base}/payments/pending`, { params });
  }

  getFlowBoard(date?: string, departmentId?: string): Observable<FlowBoard> {
    let params = new HttpParams();
    if (date) params = params.set('date', date);
    if (departmentId) params = params.set('departmentId', departmentId);
    return this.http.get<FlowBoard>(`${this.base}/flow-board`, { params });
  }

  recordPayment(invoiceId: string, amount: number, method = 'CASH'): Observable<unknown> {
    return this.http.post(`/billing-invoices/${invoiceId}/payments`, { amount, method });
  }
}

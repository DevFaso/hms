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
  insuranceId: string | null;
  hasActiveCoverage: boolean;
  primaryPayer: string | null;
  policyNumber: string | null;
  expiresOn: string | null;
  expired: boolean;
  hasPrimary: boolean;
  verifiedAt: string | null;
  verifiedBy: string | null;
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

// ── MVP 11 types ────────────────────────────────────────────────────────────

export interface DuplicateCandidate {
  patientId: string;
  fullName: string;
  mrn: string | null;
  dateOfBirth: string | null;
  phone: string | null;
  email: string | null;
  confidenceScore: number;
}

export interface WaitlistEntryRequest {
  patientId: string;
  departmentId: string;
  preferredProviderId?: string | null;
  requestedDateFrom?: string | null;
  requestedDateTo?: string | null;
  priority?: 'ROUTINE' | 'URGENT' | 'STAT';
  reason?: string | null;
}

export interface WaitlistEntryResponse {
  id: string;
  hospitalId: string;
  patientId: string;
  patientName: string;
  mrn: string | null;
  departmentId: string;
  departmentName: string;
  preferredProviderId: string | null;
  preferredProviderName: string | null;
  requestedDateFrom: string | null;
  requestedDateTo: string | null;
  priority: string;
  reason: string | null;
  /** WAITING | OFFERED | CLOSED */
  status: string;
  offeredAppointmentId: string | null;
  createdAt: string;
  createdBy: string | null;
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

  // ── MVP 11: Duplicate candidate detection ──────────────────────────────────

  getDuplicateCandidates(opts: {
    name?: string;
    dob?: string;
    phone?: string;
  }): Observable<DuplicateCandidate[]> {
    let params = new HttpParams();
    if (opts.name) params = params.set('name', opts.name);
    if (opts.dob) params = params.set('dob', opts.dob);
    if (opts.phone) params = params.set('phone', opts.phone);
    return this.http.get<DuplicateCandidate[]>(`${this.base}/patients/duplicate-candidates`, {
      params,
    });
  }

  // ── MVP 11: Waitlist ───────────────────────────────────────────────────────

  addToWaitlist(request: WaitlistEntryRequest): Observable<WaitlistEntryResponse> {
    return this.http.post<WaitlistEntryResponse>(`${this.base}/waitlist`, request);
  }

  getWaitlist(opts?: {
    departmentId?: string;
    status?: string;
  }): Observable<WaitlistEntryResponse[]> {
    let params = new HttpParams();
    if (opts?.departmentId) params = params.set('departmentId', opts.departmentId);
    if (opts?.status) params = params.set('status', opts.status);
    return this.http.get<WaitlistEntryResponse[]>(`${this.base}/waitlist`, { params });
  }

  offerWaitlistSlot(id: string): Observable<WaitlistEntryResponse> {
    return this.http.post<WaitlistEntryResponse>(`${this.base}/waitlist/${id}/offer`, {});
  }

  closeWaitlistEntry(id: string): Observable<void> {
    return this.http.post<void>(`${this.base}/waitlist/${id}/close`, {});
  }

  // ── MVP 11: Eligibility attestation ───────────────────────────────────────

  attestEligibility(insuranceId: string, eligibilityNotes: string): Observable<void> {
    return this.http.post<void>(`${this.base}/insurance/${insuranceId}/attest-eligibility`, {
      eligibilityNotes,
    });
  }

  // ── MVP 11: Flow board encounter status update ─────────────────────────────

  updateEncounterStatus(encounterId: string, status: string): Observable<void> {
    return this.http.patch<void>(`${this.base}/encounters/${encounterId}/status`, { status });
  }
}

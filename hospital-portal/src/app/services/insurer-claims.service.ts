import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/*
 * Insurer claims plan PRs 2 to 4 (QA D3983): bordereaux, the payers' remittances, refusals, write-offs and the
 * receivable reports (InsurerClaimController). Amounts are whole francs CFA.
 */

export type ClaimBatchStatus =
  'DRAFT' | 'SUBMITTED' | 'PARTIALLY_SETTLED' | 'SETTLED' | 'CANCELLED';
export type InsurerPaymentMethod = 'BANK_TRANSFER' | 'CHEQUE' | 'MOBILE_MONEY' | 'CASH';
export type ClaimRefusalClass = 'PATIENT_SIDE' | 'PROVIDER_FAULT' | 'TARIFF_REDUCTION' | 'DISPUTED';
export type ClaimRefusalResolution =
  'REBILL_PATIENT' | 'WRITE_OFF_REQUEST' | 'RESUBMIT' | 'CONTEST';
export type ClaimWriteOffReason =
  'IRRECOVERABLE_AFTER_RECOVERY' | 'COMMERCIAL_WAIVER' | 'CONTRACTUAL_ADJUSTMENT';
export type ClaimWriteOffStatus = 'REQUESTED' | 'APPROVED' | 'REFUSED' | 'CANCELLED';

/** What may follow a refusal of each class (mirrors ClaimRefusalClass on the server, which decides). */
export const REFUSAL_RESOLUTIONS: Record<ClaimRefusalClass, ClaimRefusalResolution[]> = {
  PATIENT_SIDE: ['REBILL_PATIENT', 'WRITE_OFF_REQUEST', 'RESUBMIT'],
  PROVIDER_FAULT: ['RESUBMIT', 'WRITE_OFF_REQUEST'],
  TARIFF_REDUCTION: ['WRITE_OFF_REQUEST'],
  DISPUTED: ['CONTEST', 'RESUBMIT'],
};

export interface ClaimRefusal {
  id: string;
  claimLineId: string;
  amount: number;
  reasonClass: ClaimRefusalClass;
  reason: string;
  resolution: ClaimRefusalResolution;
  writeOffId?: string | null;
  writeOffStatus?: ClaimWriteOffStatus | null;
  recordedByName?: string | null;
  recordedAt?: string | null;
}

export interface ClaimLine {
  id: string;
  batchId: string;
  batchNumber?: string | null;
  submittedOn?: string | null;
  shareId: string;
  invoiceId: string;
  invoiceItemId: string;
  patientName?: string | null;
  patientMrn?: string | null;
  policyNumber?: string | null;
  subscriberName?: string | null;
  subscriberRelationship?: string | null;
  planName?: string | null;
  invoiceNumber?: string | null;
  invoiceDate?: string | null;
  serviceDate?: string | null;
  dischargeDate?: string | null;
  actCode?: string | null;
  actLabel?: string | null;
  category?: string | null;
  quantity?: number | null;
  grossAmount?: number | null;
  patientAmount?: number | null;
  preAuthorisationNumber?: string | null;
  attachedDocuments?: string | null;
  claimedAmount: number;
  paidAmount: number;
  rebilledAmount: number;
  writtenOffAmount: number;
  writeOffPendingAmount: number;
  outstandingAmount: number;
  availableAmount: number;
  warnings?: string[] | null;
  refusals?: ClaimRefusal[] | null;
}

export interface ClaimBatch {
  id: string;
  number?: string | null;
  payerId: string;
  payerName?: string | null;
  payerType?: string | null;
  status: ClaimBatchStatus;
  periodFrom: string;
  periodTo: string;
  submittedOn?: string | null;
  submittedByName?: string | null;
  payerReference?: string | null;
  payerReceivedOn?: string | null;
  createdAt?: string | null;
  createdByName?: string | null;
  lineCount: number;
  fileCount?: number;
  grossTotal?: number | null;
  patientTotal?: number | null;
  claimedTotal: number;
  paidTotal: number;
  rebilledTotal: number;
  writtenOffTotal: number;
  writeOffPendingTotal: number;
  outstandingTotal: number;
  cancelReason?: string | null;
  cancelledAt?: string | null;
  warningCount?: number | null;
  lines?: ClaimLine[] | null;
}

export interface ClaimPage<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface InsurerPaymentAllocation {
  id: string;
  claimLineId: string;
  batchId?: string | null;
  batchNumber?: string | null;
  invoiceNumber?: string | null;
  patientName?: string | null;
  actLabel?: string | null;
  amount: number;
  allocatedAt?: string | null;
  voidedAt?: string | null;
}

export interface InsurerPayment {
  id: string;
  payerId: string;
  payerName?: string | null;
  amount: number;
  receivedDate: string;
  method: InsurerPaymentMethod;
  reference: string;
  note?: string | null;
  status: 'RECORDED' | 'REVERSED';
  allocatedAmount: number;
  unallocatedAmount: number;
  recordedByName?: string | null;
  recordedAt?: string | null;
  recordedByMe: boolean;
  reversedByName?: string | null;
  reversedAt?: string | null;
  reversalReason?: string | null;
  allocations?: InsurerPaymentAllocation[] | null;
}

export interface AllocationRequest {
  claimLineId: string;
  amount: number;
}

export interface InsurerPaymentRequest {
  payerId: string;
  amount: number;
  receivedDate: string;
  method: InsurerPaymentMethod;
  reference: string;
  note?: string | null;
  allocations: AllocationRequest[];
  clientRequestId: string;
}

export interface ClaimRefusalRequest {
  amount: number;
  reasonClass: ClaimRefusalClass;
  reason: string;
  resolution: ClaimRefusalResolution;
  writeOffReason?: ClaimWriteOffReason | null;
  supportingDocument?: string | null;
  clientRequestId: string;
}

export interface ClaimWriteOffRequest {
  amount: number;
  reasonCode: ClaimWriteOffReason;
  reason: string;
  supportingDocument: string;
  clientRequestId: string;
}

export interface ClaimWriteOff {
  id: string;
  claimLineId: string;
  batchId?: string | null;
  batchNumber?: string | null;
  payerName?: string | null;
  invoiceNumber?: string | null;
  patientName?: string | null;
  actLabel?: string | null;
  amount: number;
  reasonCode: ClaimWriteOffReason;
  reason: string;
  supportingDocument: string;
  status: ClaimWriteOffStatus;
  requestedByName?: string | null;
  requestedAt?: string | null;
  requestedByMe: boolean;
  decidedByName?: string | null;
  decidedAt?: string | null;
  decisionReason?: string | null;
}

export interface AgeingRow {
  payerId?: string | null;
  payerName?: string | null;
  notClaimed: number;
  days0To30: number;
  days31To60: number;
  days61To90: number;
  over90Days: number;
  outstanding: number;
  writeOffPending: number;
  unallocated: number;
}

export interface AgeingReport {
  asOf: string;
  rows: AgeingRow[];
  total: AgeingRow;
}

export interface ReconciliationRow {
  payerId?: string | null;
  payerName?: string | null;
  batchCount: number;
  claimed: number;
  paid: number;
  rebilled: number;
  writtenOff: number;
  writeOffPending: number;
  outstanding: number;
  received: number;
  unallocated: number;
}

export interface ReconciliationReport {
  fromDate?: string | null;
  toDate: string;
  rows: ReconciliationRow[];
  total: ReconciliationRow;
}

const BASE = '/billing-invoices/insurer-claims';

@Injectable({ providedIn: 'root' })
export class InsurerClaimsService {
  private readonly http = inject(HttpClient);

  /* ── Bordereaux ── */

  listBatches(filter: {
    statuses?: ClaimBatchStatus[];
    payerId?: string | null;
    page?: number;
    size?: number;
  }): Observable<ClaimPage<ClaimBatch>> {
    let params = new HttpParams();
    for (const status of filter.statuses ?? []) params = params.append('status', status);
    if (filter.payerId) params = params.set('payerId', filter.payerId);
    if (filter.page != null) params = params.set('page', String(filter.page));
    if (filter.size != null) params = params.set('size', String(filter.size));
    return this.http.get<ClaimPage<ClaimBatch>>(`${BASE}/batches`, { params });
  }

  createBatch(body: {
    payerId: string;
    periodFrom: string;
    periodTo: string;
    clientRequestId: string;
  }): Observable<ClaimBatch> {
    return this.http.post<ClaimBatch>(`${BASE}/batches`, body);
  }

  getBatch(id: string): Observable<ClaimBatch> {
    return this.http.get<ClaimBatch>(`${BASE}/batches/${id}`);
  }

  changeShares(id: string, body: { add?: string[]; remove?: string[] }): Observable<ClaimBatch> {
    return this.http.put<ClaimBatch>(`${BASE}/batches/${id}/shares`, body);
  }

  setLineDocuments(id: string, lineId: string, attachedDocuments: string): Observable<ClaimBatch> {
    return this.http.put<ClaimBatch>(`${BASE}/batches/${id}/lines/${lineId}/documents`, {
      attachedDocuments,
    });
  }

  submitBatch(
    id: string,
    body: { submittedOn?: string | null; payerReference?: string | null },
  ): Observable<ClaimBatch> {
    return this.http.post<ClaimBatch>(`${BASE}/batches/${id}/submit`, body);
  }

  recordPayerReceipt(
    id: string,
    body: { receivedOn: string; payerReference?: string | null },
  ): Observable<ClaimBatch> {
    return this.http.put<ClaimBatch>(`${BASE}/batches/${id}/payer-receipt`, body);
  }

  cancelBatch(id: string, reason: string): Observable<ClaimBatch> {
    return this.http.post<ClaimBatch>(`${BASE}/batches/${id}/cancel`, { reason });
  }

  deleteDraft(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/batches/${id}`);
  }

  /** The bordereau as a file for the payer: the same lines and totals in both formats. */
  exportBatch(id: string, format: 'pdf' | 'csv'): Observable<HttpResponse<Blob>> {
    return this.http.get(`${BASE}/batches/${id}/bordereau.${format}`, {
      responseType: 'blob',
      observe: 'response',
    });
  }

  /** The lines of one payer's sent bordereaux that still expect money, the oldest bordereau first. */
  openLines(payerId: string): Observable<ClaimLine[]> {
    return this.http.get<ClaimLine[]>(`${BASE}/open-lines`, {
      params: new HttpParams().set('payerId', payerId),
    });
  }

  /* ── Insurer payments ── */

  listPayments(filter: {
    payerId?: string | null;
    page?: number;
    size?: number;
  }): Observable<ClaimPage<InsurerPayment>> {
    let params = new HttpParams();
    if (filter.payerId) params = params.set('payerId', filter.payerId);
    if (filter.page != null) params = params.set('page', String(filter.page));
    if (filter.size != null) params = params.set('size', String(filter.size));
    return this.http.get<ClaimPage<InsurerPayment>>(`${BASE}/payments`, { params });
  }

  getPayment(id: string): Observable<InsurerPayment> {
    return this.http.get<InsurerPayment>(`${BASE}/payments/${id}`);
  }

  recordPayment(body: InsurerPaymentRequest): Observable<InsurerPayment> {
    return this.http.post<InsurerPayment>(`${BASE}/payments`, body);
  }

  allocate(id: string, allocations: AllocationRequest[]): Observable<InsurerPayment> {
    return this.http.post<InsurerPayment>(`${BASE}/payments/${id}/allocations`, { allocations });
  }

  reversePayment(id: string, reason: string): Observable<InsurerPayment> {
    return this.http.post<InsurerPayment>(`${BASE}/payments/${id}/reverse`, { reason });
  }

  /* ── Refusals and write-offs ── */

  refuseLine(lineId: string, body: ClaimRefusalRequest): Observable<ClaimBatch> {
    return this.http.post<ClaimBatch>(`${BASE}/lines/${lineId}/refusals`, body);
  }

  requestWriteOff(lineId: string, body: ClaimWriteOffRequest): Observable<ClaimWriteOff> {
    return this.http.post<ClaimWriteOff>(`${BASE}/lines/${lineId}/write-offs`, body);
  }

  listWriteOffs(statuses: ClaimWriteOffStatus[] = []): Observable<ClaimWriteOff[]> {
    let params = new HttpParams();
    for (const status of statuses) params = params.append('status', status);
    return this.http.get<ClaimWriteOff[]>(`${BASE}/write-offs`, { params });
  }

  approveWriteOff(id: string): Observable<ClaimWriteOff> {
    return this.http.post<ClaimWriteOff>(`${BASE}/write-offs/${id}/approve`, {});
  }

  refuseWriteOff(id: string, reason: string): Observable<ClaimWriteOff> {
    return this.http.post<ClaimWriteOff>(`${BASE}/write-offs/${id}/refuse`, { reason });
  }

  cancelWriteOff(id: string): Observable<ClaimWriteOff> {
    return this.http.post<ClaimWriteOff>(`${BASE}/write-offs/${id}/cancel`, {});
  }

  /* ── Reports ── */

  ageing(asOf?: string | null): Observable<AgeingReport> {
    let params = new HttpParams();
    if (asOf) params = params.set('asOf', asOf);
    return this.http.get<AgeingReport>(`${BASE}/reports/ageing`, { params });
  }

  reconciliation(filter: {
    payerId?: string | null;
    fromDate?: string | null;
    toDate?: string | null;
  }): Observable<ReconciliationReport> {
    return this.http.get<ReconciliationReport>(`${BASE}/reports/reconciliation`, {
      params: this.reconciliationParams(filter),
    });
  }

  exportAgeing(asOf?: string | null): Observable<HttpResponse<Blob>> {
    let params = new HttpParams();
    if (asOf) params = params.set('asOf', asOf);
    return this.http.get(`${BASE}/reports/ageing.csv`, {
      params,
      responseType: 'blob',
      observe: 'response',
    });
  }

  exportReconciliation(filter: {
    payerId?: string | null;
    fromDate?: string | null;
    toDate?: string | null;
  }): Observable<HttpResponse<Blob>> {
    return this.http.get(`${BASE}/reports/reconciliation.csv`, {
      params: this.reconciliationParams(filter),
      responseType: 'blob',
      observe: 'response',
    });
  }

  private reconciliationParams(filter: {
    payerId?: string | null;
    fromDate?: string | null;
    toDate?: string | null;
  }): HttpParams {
    let params = new HttpParams();
    if (filter.payerId) params = params.set('payerId', filter.payerId);
    if (filter.fromDate) params = params.set('fromDate', filter.fromDate);
    if (filter.toDate) params = params.set('toDate', filter.toDate);
    return params;
  }
}

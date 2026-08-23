import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

/*
 * Billing invoices — /billing-invoices (+ /invoice-items). Bare DTOs and
 * Spring Pages, no wrapper. Search is POST body filters + query-string
 * paging; for non-super-admins the server overwrites any hospitalId filter
 * with the caller's active hospital. DELETE returns text/plain, not JSON.
 * Payment recording forwards only the amount — method/reference/notes are
 * discarded server-side. Email is synchronous and flips status to SENT.
 * (The old RECEPTIONIST-payment 403 is fixed: the POST matcher now includes
 * RECEPTIONIST and the per-endpoint @PreAuthorize stays the precise gate.)
 */

/** Backend InvoiceStatus — the only values the server knows. */
export type InvoiceStatus = 'DRAFT' | 'SENT' | 'PARTIALLY_PAID' | 'PAID' | 'CANCELLED';
export const INVOICE_STATUSES: InvoiceStatus[] = [
  'DRAFT',
  'SENT',
  'PARTIALLY_PAID',
  'PAID',
  'CANCELLED',
];

export interface BillingInvoiceResponse {
  id: string;
  patientFullName: string;
  patientName: string;
  patientEmail: string;
  patientPhone: string;
  hospitalName: string;
  hospitalCode: string;
  hospitalAddress: string;
  encounterDescription: string;
  encounterType: string;
  encounterStatus: string;
  encounterDate: string;
  encounterTime: string;
  createdByName: string;
  invoiceNumber: string;
  invoiceDate: string;
  dueDate: string;
  totalAmount: number;
  amountPaid: number;
  balanceDue: number;
  insuranceCoverageAmount: number;
  patientResponsibilityAmount: number;
  status: InvoiceStatus;
  notes: string;
  createdAt: string;
  updatedAt: string;
}

export interface InvoiceItemResponse {
  id: string;
  billingInvoiceId: string;
  itemDescription: string;
  itemCategory: string;
  quantity: number;
  unitPrice: number;
  totalPrice: number;
}

export interface BillingInvoiceRequest {
  patientEmail: string;
  hospitalName: string;
  encounterReference?: string;
  invoiceNumber: string;
  invoiceDate: string;
  dueDate: string;
  totalAmount: number;
  amountPaid: number;
  status: InvoiceStatus;
  notes?: string;
}

export interface InvoiceSearchFilters {
  patientId?: string;
  statuses?: InvoiceStatus[];
  fromDate?: string;
  toDate?: string;
}

export interface InvoicePage {
  content: BillingInvoiceResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

@Injectable({ providedIn: 'root' })
export class BillingService {
  private readonly http = inject(HttpClient);

  /** Body filters + query-string paging. hospitalId is intentionally not a
   *  filter: the server overwrites it with the caller's active hospital. */
  searchInvoices(filters: InvoiceSearchFilters, page = 0, size = 20): Observable<InvoicePage> {
    const params = new HttpParams().set('page', page).set('size', size);
    const body: Record<string, unknown> = {};
    if (filters.patientId) body['patientId'] = filters.patientId;
    if (filters.statuses?.length) body['statuses'] = filters.statuses;
    if (filters.fromDate) body['fromDate'] = filters.fromDate;
    if (filters.toDate) body['toDate'] = filters.toDate;
    return this.http.post<InvoicePage>('/billing-invoices/search', body, { params });
  }

  getInvoice(id: string): Observable<BillingInvoiceResponse> {
    return this.http.get<BillingInvoiceResponse>(`/billing-invoices/${id}`);
  }

  getInvoicesByPatient(
    patientId: string,
    params?: { page?: number; size?: number },
  ): Observable<BillingInvoiceResponse[]> {
    let httpParams = new HttpParams();
    if (params?.page !== undefined) httpParams = httpParams.set('page', String(params.page));
    if (params?.size !== undefined) httpParams = httpParams.set('size', String(params.size));
    return this.http
      .get<{
        content: BillingInvoiceResponse[];
      }>(`/billing-invoices/patient/${patientId}`, { params: httpParams })
      .pipe(map((res) => res?.content ?? []));
  }

  getInvoicesByHospital(
    hospitalId: string,
    params?: { page?: number; size?: number },
  ): Observable<BillingInvoiceResponse[]> {
    let httpParams = new HttpParams();
    if (params?.page !== undefined) httpParams = httpParams.set('page', String(params.page));
    if (params?.size !== undefined) httpParams = httpParams.set('size', String(params.size));
    return this.http
      .get<{
        content: BillingInvoiceResponse[];
      }>(`/billing-invoices/hospital/${hospitalId}`, { params: httpParams })
      .pipe(map((res) => res?.content ?? []));
  }

  /** Server-side "overdue" = SENT/PARTIALLY_PAID with dueDate before the
   *  reference date (defaults to today), scoped to the active hospital. */
  getOverdue(referenceDate?: string): Observable<BillingInvoiceResponse[]> {
    let params = new HttpParams();
    if (referenceDate) params = params.set('referenceDate', referenceDate);
    return this.http.get<BillingInvoiceResponse[]>('/billing-invoices/overdue', { params });
  }

  getInvoicePdf(id: string): Observable<Blob> {
    return this.http.get(`/billing-invoices/${id}/pdf`, { responseType: 'blob' });
  }

  getInvoiceItems(invoiceId: string): Observable<InvoiceItemResponse[]> {
    return this.http.get<InvoiceItemResponse[]>(`/invoice-items/invoice/${invoiceId}`);
  }

  createInvoice(req: BillingInvoiceRequest): Observable<BillingInvoiceResponse> {
    return this.http.post<BillingInvoiceResponse>('/billing-invoices', req);
  }

  /** Full replace — every required field must be present (backend @NotNull). */
  updateInvoice(id: string, req: BillingInvoiceRequest): Observable<BillingInvoiceResponse> {
    return this.http.put<BillingInvoiceResponse>(`/billing-invoices/${id}`, req);
  }

  /** Hard delete; the backend answers text/plain, so parse as text. */
  deleteInvoice(id: string): Observable<string> {
    return this.http.delete(`/billing-invoices/${id}`, { responseType: 'text' });
  }

  /** Backend rejects payments on DRAFT/CANCELLED/PAID invoices and amounts
   *  above the balance due (400 with a message). Only amount is persisted. */
  recordPayment(invoiceId: string, amount: number): Observable<BillingInvoiceResponse> {
    return this.http.post<BillingInvoiceResponse>(`/billing-invoices/${invoiceId}/payments`, {
      amount,
    });
  }

  /** Synchronous send; flips the invoice to SENT on success. attachPdf
   *  defaults to false server-side, so pass it explicitly. */
  emailInvoice(
    id: string,
    data: {
      to: string[];
      cc?: string[];
      bcc?: string[];
      message?: string;
      locale?: string;
      attachPdf: boolean;
    },
  ): Observable<{ status: string; sentAt?: string; error?: string }> {
    return this.http.post<{ status: string; sentAt?: string; error?: string }>(
      `/billing-invoices/${id}/email`,
      data,
    );
  }
}

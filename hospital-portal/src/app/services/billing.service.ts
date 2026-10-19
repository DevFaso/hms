import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

/*
 * Billing invoices — /billing-invoices (+ /invoice-items). Bare DTOs and
 * Spring Pages, no wrapper. Search is POST body filters + query-string
 * paging; for non-super-admins the server overwrites any hospitalId filter
 * with the caller's active hospital. DELETE returns text/plain, not JSON.
 * Payment recording sends the method and the reference (QA D146); patient
 * declarations arrive PENDING and count only once a cashier confirms them
 * (QA D140); a confirmed payment is reversed with a reason (QA D142). Email is synchronous and flips status to SENT.
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
  patientId?: string;
  patientFullName: string;
  patientName: string;
  patientEmail: string;
  patientPhone: string;
  hospitalName: string;
  hospitalCode: string;
  hospitalAddress: string;
  encounterCode?: string;
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
  staffDisplay?: string;
}

/** Backend ItemCategory. */
export type InvoiceItemCategory = 'SERVICE' | 'MEDICATION' | 'LAB_TEST' | 'GENERAL';
export const INVOICE_ITEM_CATEGORIES: InvoiceItemCategory[] = [
  'SERVICE',
  'MEDICATION',
  'LAB_TEST',
  'GENERAL',
];

export interface InvoiceItemRequest {
  billingInvoiceId: string;
  itemDescription: string;
  itemCategory: InvoiceItemCategory;
  quantity: number;
  unitPrice: number;
}

/**
 * The server numbers the invoice (per hospital), writes it at the caller's
 * acting hospital, and never takes the amount paid from this form: it moves
 * only through recorded payments. With lines, the total is their sum.
 */
export interface BillingInvoiceRequest {
  /** The picked patient (phone-only patients included). */
  patientId: string;
  encounterReference?: string;
  invoiceDate: string;
  dueDate: string;
  /** The amount of an invoice with no lines; ignored once it has lines. */
  totalAmount: number;
  /** DRAFT, SENT or CANCELLED; paid statuses follow payments. */
  status: InvoiceStatus;
  notes?: string;
  /** Idempotency key, one per create form: a double submit is one invoice. */
  clientRequestId?: string;
}

/** Backend PaymentMethod values the staff modal offers. */
export type StaffPaymentMethod =
  'CASH' | 'MOBILE_MONEY' | 'CARD' | 'BANK_TRANSFER' | 'CHECK' | 'INSURANCE' | 'OTHER';
export const STAFF_PAYMENT_METHODS: StaffPaymentMethod[] = [
  'CASH',
  'MOBILE_MONEY',
  'CARD',
  'BANK_TRANSFER',
  'CHECK',
  'INSURANCE',
  'OTHER',
];

export interface StaffPaymentRequest {
  amount: number;
  method: StaffPaymentMethod;
  reference?: string;
  notes?: string;
}

export type PaymentStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED' | 'REVERSED';

/** One ledger row of an invoice (PaymentTransactionResponseDTO). */
export interface PaymentRow {
  id: string;
  amount: number;
  paymentDate: string;
  paymentMethod: string;
  referenceNumber?: string | null;
  notes?: string | null;
  status: PaymentStatus;
  reversalReason?: string | null;
  reversedAt?: string | null;
  createdAt?: string | null;
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

  createInvoiceItem(req: InvoiceItemRequest): Observable<InvoiceItemResponse> {
    return this.http.post<InvoiceItemResponse>('/invoice-items', req);
  }

  /** The backend answers text/plain, so parse as text. */
  deleteInvoiceItem(id: string): Observable<string> {
    return this.http.delete(`/invoice-items/${id}`, { responseType: 'text' });
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

  /** Backend rejects payments on DRAFT/CANCELLED/PAID invoices, amounts
   *  above the balance due and fractional francs (400 with a message). */
  recordPayment(
    invoiceId: string,
    payment: StaffPaymentRequest,
  ): Observable<BillingInvoiceResponse> {
    return this.http.post<BillingInvoiceResponse>(
      `/billing-invoices/${invoiceId}/payments`,
      payment,
    );
  }

  listPayments(invoiceId: string): Observable<PaymentRow[]> {
    return this.http.get<PaymentRow[]>(`/billing-invoices/${invoiceId}/payments`);
  }

  confirmPayment(invoiceId: string, paymentId: string): Observable<BillingInvoiceResponse> {
    return this.http.post<BillingInvoiceResponse>(
      `/billing-invoices/${invoiceId}/payments/${paymentId}/confirm`,
      {},
    );
  }

  rejectPayment(
    invoiceId: string,
    paymentId: string,
    reason: string,
  ): Observable<BillingInvoiceResponse> {
    return this.http.post<BillingInvoiceResponse>(
      `/billing-invoices/${invoiceId}/payments/${paymentId}/reject`,
      { reason },
    );
  }

  reversePayment(
    invoiceId: string,
    paymentId: string,
    reason: string,
  ): Observable<BillingInvoiceResponse> {
    return this.http.post<BillingInvoiceResponse>(
      `/billing-invoices/${invoiceId}/payments/${paymentId}/reverse`,
      { reason },
    );
  }

  getReceiptPdf(invoiceId: string, paymentId: string): Observable<Blob> {
    return this.http.get(`/billing-invoices/${invoiceId}/payments/${paymentId}/receipt`, {
      responseType: 'blob',
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

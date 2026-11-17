import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

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
  /** QA D1466: paid above the total (a copay above the visit total), owed back to the patient. */
  refundDue?: number;
  /** Billing-money plan PR 8 (QA D1900): what refunds already handed back to the patient. */
  amountRefunded?: number;
  insuranceCoverageAmount: number;
  patientResponsibilityAmount: number;
  status: InvoiceStatus;
  notes: string;
  /** QA D1069: why and when (server time) a cancelled invoice was cancelled. */
  cancelReason?: string | null;
  cancelledAt?: string | null;
  createdAt: string;
  updatedAt: string;
  /** QA D428: the visit invoice a folded check-in copay was credited to. */
  creditedToInvoiceNumber?: string | null;
  /** QA D936: a copay taken at check-in; credited to the visit invoice once one is issued. */
  checkInCopay?: boolean;
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
  /** Billing-money plan PR 3: where the unit price came from. */
  priceSource?: InvoiceLinePriceSource | null;
  /** The price list's price for this line ("Prix de la grille"), overridden or not; null when none. */
  tariffUnitPrice?: number | null;
  /** Why the price-list price was overridden, as typed. */
  overrideReason?: string | null;
}

/** Backend InvoiceLinePriceSource. */
export type InvoiceLinePriceSource = 'TARIFF' | 'SERVICE_NAME' | 'MANUAL' | 'UNPRICED' | 'LEGACY';

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
  /** Billing-money plan PR 3 (D-2): required when a price-list price is changed. */
  overrideReason?: string;
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
  /** QA D1069: why an issued invoice is cancelled; required by the server for that move. */
  cancelReason?: string;
  /** Idempotency key, one per create form: a double submit is one invoice. */
  clientRequestId?: string;
  /**
   * QA D529: confirms a deliberate second invoice for a visit or stay that
   * already has one (the server answers 409 otherwise).
   */
  supplementary?: boolean;
  /**
   * QA D1467: the clerk saw the lines at 0 F CFA and issues them as they are
   * (the server refuses on this field otherwise, naming them).
   */
  confirmUnpricedLines?: boolean;
}

/**
 * Backend PaymentMethod values the staff modal offers. QA D1611 (round 44):
 * never INSURANCE: an insurer's share is claimed from the payer, not money
 * received, and no till creates that claim yet (the server refuses it,
 * `billing.payment.insuranceNotAPayment`). Rows recorded before still read
 * through `BILLING.PAYMENT_METHODS.INSURANCE`.
 */
export type StaffPaymentMethod =
  'CASH' | 'MOBILE_MONEY' | 'CARD' | 'BANK_TRANSFER' | 'CHECK' | 'OTHER';
export const STAFF_PAYMENT_METHODS: StaffPaymentMethod[] = [
  'CASH',
  'MOBILE_MONEY',
  'CARD',
  'BANK_TRANSFER',
  'CHECK',
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

/** QA D974: how an issued invoice was lowered. */
export type InvoiceAdjustmentType = 'DISCOUNT' | 'WAIVER' | 'CREDIT_NOTE';
export const INVOICE_ADJUSTMENT_TYPES: InvoiceAdjustmentType[] = [
  'DISCOUNT',
  'WAIVER',
  'CREDIT_NOTE',
];

export interface InvoiceAdjustmentRequest {
  type: InvoiceAdjustmentType;
  /** Whole francs; may be left out for a credit note (the line's remainder). */
  amount?: number | null;
  reason: string;
  invoiceItemId?: string | null;
}

export interface InvoiceAdjustmentRow {
  id: string;
  type: InvoiceAdjustmentType;
  amount: number;
  reason: string;
  invoiceItemId?: string | null;
  invoiceItemDescription?: string | null;
  createdAt?: string | null;
  /** QA D1105: when the adjustment was reversed; null while it stands. */
  reversedAt?: string | null;
  /** QA D1105: why it was reversed (billing staff only). */
  reversalReason?: string | null;
}

export interface InvoiceSearchFilters {
  patientId?: string;
  statuses?: InvoiceStatus[];
  fromDate?: string;
  toDate?: string;
  /** QA D203: matched server-side against the invoice number and the patient's name. */
  query?: string;
}

/** QA D209: the billing KPIs over every invoice in scope, not one page. */
export interface BillingSummary {
  total: number;
  paid: number;
  outstanding: number;
  overdue: number;
  totalRevenue: number;
  balance: number;
}

/** Billing-money plan PR 8: money received at the till (IN) or handed back on a refund (OUT). */
export type CashDirection = 'IN' | 'OUT';

/** QA D1059: one cashier's money received on one day (BillingInvoiceService.cashClosing). */
export interface CashClosing {
  date: string;
  cashierId?: string;
  cashierName?: string | null;
  byMethod: {
    source: 'BILLING' | 'PHARMACY';
    method: string;
    /** Billing-money plan PR 8: absent on an older server, read as IN. */
    direction?: CashDirection;
    count: number;
    amount: number;
  }[];
  entries: {
    id: string;
    source: 'BILLING' | 'PHARMACY';
    receivedAt?: string | null;
    document?: string | null;
    patientName?: string | null;
    method?: string | null;
    reference?: string | null;
    amount: number;
    status: string;
    direction?: CashDirection;
  }[];
  receivedCount: number;
  receivedAmount: number;
  reversedCount: number;
  reversedAmount: number;
  /** Billing-money plan PR 8 (QA D1900): refunds handed back that day, and what should be in the till. */
  refundedCount?: number;
  refundedAmount?: number;
  netAmount?: number;
}

/** Billing-money plan PR 8 (QA D1900): the life of a refund (InvoiceRefundStatus). */
export type InvoiceRefundStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'PAID' | 'CANCELLED';

/** How a refund is handed back: never an insurer's share. */
export const REFUND_METHODS = ['CASH', 'MOBILE_MONEY', 'BANK_TRANSFER', 'CHECK', 'OTHER'] as const;
export type RefundMethod = (typeof REFUND_METHODS)[number];

/** The mobile-money operators (MobileMoneyOperator). */
export const MOBILE_MONEY_OPERATORS = ['ORANGE_MONEY', 'MOOV_MONEY', 'AUTRE'] as const;
export type MobileMoneyOperator = (typeof MOBILE_MONEY_OPERATORS)[number];

/** Billing-money plan PR 8: one refund of an invoice, as billing staff see it (InvoiceRefundDTO). */
export interface InvoiceRefund {
  id: string;
  invoiceId: string;
  invoiceNumber?: string | null;
  patientName?: string | null;
  amount: number;
  method: string;
  mobileMoneyOperator?: string | null;
  reference?: string | null;
  reason: string;
  status: InvoiceRefundStatus;
  requestedBy?: string | null;
  requestedByName?: string | null;
  requestedAt?: string | null;
  /** The reader asked for it, so cannot approve or reject it (owner decision D-3). */
  requestedByMe?: boolean;
  decidedByName?: string | null;
  decidedAt?: string | null;
  decisionReason?: string | null;
  paidByName?: string | null;
  paidAt?: string | null;
  cancelledByName?: string | null;
  cancelledAt?: string | null;
  cancelReason?: string | null;
}

export interface InvoiceRefundRequest {
  amount: number | null;
  method: string;
  mobileMoneyOperator?: string | null;
  reference?: string | null;
  reason: string;
  /** The same key on a retried submit returns the refund already asked for. */
  clientRequestId?: string;
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
    const query = filters.query?.trim();
    if (query) body['query'] = query;
    return this.http.post<InvoicePage>('/billing-invoices/search', body, { params });
  }

  /** QA D1059: the KPIs over the invoices dated in the list's date filter (either bound optional). */
  getSummary(range?: { fromDate?: string; toDate?: string }): Observable<BillingSummary> {
    let params = new HttpParams();
    if (range?.fromDate) params = params.set('fromDate', range.fromDate);
    if (range?.toDate) params = params.set('toDate', range.toDate);
    return this.http.get<BillingSummary>('/billing-invoices/summary', { params });
  }

  /** QA D1059: the signed-in cashier's daily cash closing (today when no date). */
  getCashClosing(date?: string): Observable<CashClosing> {
    let params = new HttpParams();
    if (date) params = params.set('date', date);
    return this.http.get<CashClosing>('/billing-invoices/cash-closing', { params });
  }

  getInvoice(id: string): Observable<BillingInvoiceResponse> {
    return this.http.get<BillingInvoiceResponse>(`/billing-invoices/${id}`);
  }

  /** Server-side "overdue" = SENT/PARTIALLY_PAID with dueDate before the
   *  reference date (defaults to today), scoped to the active hospital. */
  getOverdue(referenceDate?: string): Observable<BillingInvoiceResponse[]> {
    let params = new HttpParams();
    if (referenceDate) params = params.set('referenceDate', referenceDate);
    return this.http.get<BillingInvoiceResponse[]>('/billing-invoices/overdue', { params });
  }

  /** QA D1009: the whole response, so the file keeps the name the server gives it. */
  getInvoicePdf(id: string): Observable<HttpResponse<Blob>> {
    return this.http.get(`/billing-invoices/${id}/pdf`, {
      responseType: 'blob',
      observe: 'response',
    });
  }

  getInvoiceItems(invoiceId: string): Observable<InvoiceItemResponse[]> {
    return this.http.get<InvoiceItemResponse[]>(`/invoice-items/invoice/${invoiceId}`);
  }

  createInvoiceItem(req: InvoiceItemRequest): Observable<InvoiceItemResponse> {
    return this.http.post<InvoiceItemResponse>('/invoice-items', req);
  }

  /** The backend answers text/plain, so parse as text. */
  /** QA D1088: a draft line is corrected in place (its price above all); it keeps its id and its links. */
  updateInvoiceItem(id: string, req: InvoiceItemRequest): Observable<InvoiceItemResponse> {
    return this.http.put<InvoiceItemResponse>(`/invoice-items/${id}`, req);
  }

  /** Billing-money plan PR 3 (review #1197 M2): a price-list line is deleted only with a reason. */
  deleteInvoiceItem(id: string, reason?: string): Observable<string> {
    const params = reason ? { reason } : undefined;
    return this.http.delete(`/invoice-items/${id}`, { responseType: 'text', params });
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

  /**
   * QA D1105: undo a mistaken discount, waiver or credit note, with a reason;
   * the money already received (a folded check-in copay) stays recorded.
   */
  reverseAdjustment(
    invoiceId: string,
    adjustmentId: string,
    reason: string,
  ): Observable<BillingInvoiceResponse> {
    return this.http.post<BillingInvoiceResponse>(
      `/billing-invoices/${invoiceId}/adjustments/${adjustmentId}/reverse`,
      { reason },
    );
  }

  /** QA D974: discount, waive or credit part of an issued invoice, with a reason. */
  addAdjustment(
    invoiceId: string,
    req: InvoiceAdjustmentRequest,
  ): Observable<BillingInvoiceResponse> {
    return this.http.post<BillingInvoiceResponse>(
      `/billing-invoices/${invoiceId}/adjustments`,
      req,
    );
  }

  listAdjustments(invoiceId: string): Observable<InvoiceAdjustmentRow[]> {
    return this.http.get<InvoiceAdjustmentRow[]>(`/billing-invoices/${invoiceId}/adjustments`);
  }

  /** QA D1009: each receipt has its own number ("…-R2"), carried in Content-Disposition. */
  getReceiptPdf(invoiceId: string, paymentId: string): Observable<HttpResponse<Blob>> {
    return this.http.get(`/billing-invoices/${invoiceId}/payments/${paymentId}/receipt`, {
      responseType: 'blob',
      observe: 'response',
    });
  }

  /* ── Refunds (billing-money plan PR 8, QA D1900) ── */

  listRefunds(invoiceId: string): Observable<InvoiceRefund[]> {
    return this.http.get<InvoiceRefund[]>(`/billing-invoices/${invoiceId}/refunds`);
  }

  /** The hospital's refunds awaiting a decision (REQUESTED) or a hand-over (APPROVED). */
  getRefundQueue(
    statuses: InvoiceRefundStatus[] = ['REQUESTED', 'APPROVED'],
  ): Observable<InvoiceRefund[]> {
    let params = new HttpParams();
    for (const status of statuses) params = params.append('status', status);
    return this.http.get<InvoiceRefund[]>('/billing-invoices/refunds', { params });
  }

  requestRefund(invoiceId: string, req: InvoiceRefundRequest): Observable<InvoiceRefund> {
    return this.http.post<InvoiceRefund>(`/billing-invoices/${invoiceId}/refunds`, req);
  }

  approveRefund(invoiceId: string, refundId: string): Observable<InvoiceRefund> {
    return this.http.post<InvoiceRefund>(
      `/billing-invoices/${invoiceId}/refunds/${refundId}/approve`,
      {},
    );
  }

  rejectRefund(invoiceId: string, refundId: string, reason: string): Observable<InvoiceRefund> {
    return this.http.post<InvoiceRefund>(
      `/billing-invoices/${invoiceId}/refunds/${refundId}/reject`,
      { reason },
    );
  }

  payRefund(invoiceId: string, refundId: string): Observable<InvoiceRefund> {
    return this.http.post<InvoiceRefund>(
      `/billing-invoices/${invoiceId}/refunds/${refundId}/pay`,
      {},
    );
  }

  cancelRefund(invoiceId: string, refundId: string, reason: string): Observable<InvoiceRefund> {
    return this.http.post<InvoiceRefund>(
      `/billing-invoices/${invoiceId}/refunds/${refundId}/cancel`,
      { reason },
    );
  }

  /** "Reçu de remboursement", named by the server ("…-RB1"). */
  getRefundReceiptPdf(invoiceId: string, refundId: string): Observable<HttpResponse<Blob>> {
    return this.http.get(`/billing-invoices/${invoiceId}/refunds/${refundId}/receipt`, {
      responseType: 'blob',
      observe: 'response',
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

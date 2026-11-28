import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { MobileMoneyOperator } from '../shared/mobile-money-operator';

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
  /** Billing-money plan PR 7: what the payers owe on the invoice ("Part assureur"); 0 when none covers it. */
  insuranceCoverageAmount: number;
  /** Billing-money plan PR 7: the patient's share of the bill before payments ("Part patient"). */
  patientResponsibilityAmount: number;
  /** QA D2437: on a draft's detail, what the coverage would pay if it were issued now (an estimate). */
  estimatedInsuranceCoverageAmount?: number | null;
  /** Billing-money plan PR 7: the payers' shares (on GET by id), a rejected one with the payer's reason. */
  payerShares?: InvoicePayerShare[];
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

/** Billing-money plan PR 7: the life of a payer's share of an invoice. */
export type PayerShareStatus =
  'OPEN' | 'CLAIMED' | 'PARTIALLY_PAID' | 'PAID' | 'WRITTEN_OFF' | 'REJECTED' | 'CANCELLED';

/** Billing-money plan PR 7: the decisions recorded on a payer's share (the path segment of each). */
/**
 * Insurer claims PR 3: a share is claimed by sending its bordereau and paid by an insurer payment (amount, date,
 * reference); the only decision left on the share itself is the payer's refusal before any claim.
 */
export type PayerShareDecision = 'reject';

/** Billing-money plan PR 7: what one payer plan owes on an invoice (InvoicePayerShareDTO). */
export interface InvoicePayerShare {
  id: string;
  payerName?: string | null;
  planName?: string | null;
  amount: number;
  coverageYear: number;
  status: PayerShareStatus;
  rejectionReason?: string | null;
  decidedAt?: string | null;
  createdAt?: string | null;
  /** Insurer claims PR 1 (QA D2712): the policy and subscriber of the coverage the share was computed under. */
  policyNumber?: string | null;
  subscriberName?: string | null;
  subscriberRelationship?: string | null;
  /** Insurer claims PR 3: what the payer paid, refused (re-billed to the patient) and what was written off. */
  paidAmount?: number | null;
  rejectedAmount?: number | null;
  writtenOffAmount?: number | null;
}

/** Insurer claims PR 1 (G1): one act a payer pays part of, as a claim lists it (PayerShareLineDTO). */
export interface PayerShareLine {
  itemId: string;
  description?: string | null;
  /** The hospital's act code from its price list; null for an act not priced from it. */
  actCode?: string | null;
  /** The price-list category a plan reads (IMAGING, PROCEDURE, ...), else the line's own category. */
  category?: string | null;
  quantity?: number | null;
  grossAmount: number;
  payerShare: number;
  patientShare: number;
  preAuthorisationNumber?: string | null;
}

/** Insurer claims PR 1 (G2): one page of « Parts assureurs », with the count and total of every page. */
export interface PayerSharePage {
  content: OpenPayerShare[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  totalAmount: number;
}

/** Insurer claims PR 1 (G2): the filters of « Parts assureurs »; each one left out is not applied. */
export interface PayerShareFilter {
  statuses?: PayerShareStatus[];
  payerId?: string | null;
  planId?: string | null;
  /** Service date (the invoice date), inclusive, yyyy-MM-dd. */
  fromDate?: string | null;
  toDate?: string | null;
  /** Zero-based. */
  page?: number;
  size?: number;
}

/** Insurer claims PR 1 (QA D3984): a line's pre-authorisation number as recorded. */
export interface InvoiceLinePreAuthorisation {
  itemId: string;
  invoiceId: string;
  preAuthorisationNumber: string;
  recordedAt?: string | null;
  orderUpdated: boolean;
}

/** Insurer claims PR 1 (QA D3984): an imaging order's number recorded from the desk (no clinical field). */
export interface ImagingPreAuthorisationDesk {
  orderId: string;
  authorizationNumber: string;
  authorizationPending: boolean;
  invoiceLinesUpdated: number;
}

/** QA D2706 (r70): one payer share on the hospital-wide « Parts assureurs » list (OpenPayerShareDTO). */
export interface OpenPayerShare {
  id: string;
  invoiceId: string;
  invoiceNumber?: string | null;
  invoiceDate?: string | null;
  patientName?: string | null;
  patientMrn?: string | null;
  payerName?: string | null;
  planName?: string | null;
  amount: number;
  status: PayerShareStatus;
  createdAt?: string | null;
  /* Insurer claims PR 1 (G1): what a claim needs. */
  payerId?: string | null;
  planId?: string | null;
  payerType?: string | null;
  policyNumber?: string | null;
  subscriberName?: string | null;
  subscriberRelationship?: string | null;
  coverageVerifiedAt?: string | null;
  lines?: PayerShareLine[];
  /* Insurer claims PRs 2 and 3: how far the share is settled, and the bordereau it is on. */
  paidAmount?: number | null;
  rejectedAmount?: number | null;
  writtenOffAmount?: number | null;
  openAmount?: number | null;
  rejectionReason?: string | null;
  decidedAt?: string | null;
  claimBatchId?: string | null;
  claimBatchNumber?: string | null;
}

export interface InvoiceItemResponse {
  id: string;
  billingInvoiceId: string;
  itemDescription: string;
  itemCategory: string;
  /** Stored tariff category, used only as the line's display label. */
  coverageCategory?: string | null;
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
  /** QA D2434: the payer's part of this line ("Part assureur"); 0 when no payer covers it. */
  payerShareAmount?: number;
  /** QA D2434: the line total less the payer's part ("Part patient"). */
  patientShareAmount?: number;
  /** QA D2437: on a draft, the payer's part is an estimate, fixed when the invoice is issued. */
  payerShareEstimated?: boolean;
  /** Insurer claims PR 1 (QA D3984): the insurer's pre-authorisation number of the act. */
  preAuthorisationNumber?: string | null;
  preAuthorisationRecordedAt?: string | null;
  /** The kind of order the line bills (IMAGING_ORDER, PROCEDURE_ORDER), when posted at checkout. */
  sourceOrderType?: string | null;
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

/** Review of #1338: a price-list item's grid price on a draft invoice's service date. */
export interface InvoiceLineGridPrice {
  priceListItemId: string;
  serviceDate: string;
  unitPrice?: number | null;
}

export interface InvoiceItemRequest {
  billingInvoiceId: string;
  itemDescription: string;
  itemCategory: InvoiceItemCategory;
  quantity: number;
  unitPrice: number;
  /**
   * Billing-money plan PR 3 (D-2): required when a price-list price is changed. PR 10 review
   * (QA D2703): also required to add a line by hand to a visit's invoice.
   */
  overrideReason?: string;
  /**
   * QA D3980 (r77), create only: the price-list item (act) the line bills. The server names the line after it,
   * prices it at the grid and lets the patient's plan apply its rules; a line without one is never claimed from
   * the insurer.
   */
  priceListItemId?: string;
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
  /**
   * PR 10 review (QA D2703): why the lines at 0 F CFA go out free. Required with
   * `confirmUnpricedLines`; kept on each such line and listed in the day report.
   */
  unpricedLinesReason?: string;
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
  /** Billing plan PR 9: required by the server when the method is MOBILE_MONEY. */
  mobileMoneyOperator?: MobileMoneyOperator;
  /** Billing plan PR 11 (QA D2430): one key per payment dialog; a retry with it records nothing twice. */
  clientRequestId?: string;
  /** QA D2704: the cashier confirms a second same payment just recorded on this invoice (another tab). */
  confirmDuplicate?: boolean;
}

export type PaymentStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED' | 'REVERSED';

/** One ledger row of an invoice (PaymentTransactionResponseDTO). */
export interface PaymentRow {
  id: string;
  amount: number;
  paymentDate: string;
  paymentMethod: string;
  referenceNumber?: string | null;
  /** Billing plan PR 9: ORANGE_MONEY / MOOV_MONEY / AUTRE; null when not stated. */
  mobileMoneyOperator?: MobileMoneyOperator | null;
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
  /** QA D3981 (r77): net revenue: received less the refunds paid out. */
  totalRevenue: number;
  balance: number;
  /** QA D3981: received before refunds, and the refunds paid out. */
  grossReceived?: number | null;
  refunded?: number | null;
}

/** Billing-money plan PR 8: money received at the till (IN) or handed back on a refund (OUT). */
export type CashDirection = 'IN' | 'OUT';

/** QA D1059: one cashier's money received on one day (BillingInvoiceService.cashClosing). */
export interface CashClosing {
  date: string;
  cashierId?: string;
  cashierName?: string | null;
  /** Billing plan PR 9: one row per source, method and Mobile Money operator (and, PR 8, direction). */
  byMethod: {
    source: 'BILLING' | 'PHARMACY';
    method: string;
    operator?: MobileMoneyOperator | null;
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
    operator?: MobileMoneyOperator | null;
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
  /** PR 8 review: the receipt number given at hand-over (-RB<n>). */
  receiptNumber?: string | null;
  cancelledByName?: string | null;
  cancelledAt?: string | null;
  cancelReason?: string | null;
  /** QA D2702: set when this refund reverses that payment (asked, approved by another person, paid out). */
  reversedPaymentId?: string | null;
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

/** QA D4222 / D4223: what the invoice e-mail dialog offers before anything is sent. */
export interface InvoiceMailRecipientOptions {
  /** The patient's own address on file; null when there is none. */
  patient: {
    address: string;
    /** False when a send to this address would be refused. */
    usable: boolean;
    /** The delivery ledger's code (OPTED_OUT, RECIPIENT_UNVERIFIED); null when usable. */
    refusalCode: string | null;
    refusalMessage: string | null;
  } | null;
  guarantors: {
    guarantorId: string;
    fullName: string;
    relationship: string | null;
    address: string;
    primary: boolean;
  }[];
  maxRecipients: number;
  reasonMaxLength: number;
  messageMaxLength: number;
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

  /**
   * Review of #1338 (QA D3980): the grid price a line typed on this draft would take for a price-list item, on the
   * invoice's own service date (the price the server checks the line against). Null when none is in force then.
   */
  getGridPrice(invoiceId: string, priceListItemId: string): Observable<InvoiceLineGridPrice> {
    const params = new HttpParams()
      .set('billingInvoiceId', invoiceId)
      .set('priceListItemId', priceListItemId);
    return this.http.get<InvoiceLineGridPrice>('/invoice-items/grid-price', { params });
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

  /* ── Payer shares (billing-money plan PR 7) ── */

  listPayerShares(invoiceId: string): Observable<InvoicePayerShare[]> {
    return this.http.get<InvoicePayerShare[]>(`/billing-invoices/${invoiceId}/payer-shares`);
  }

  /**
   * QA D2706 (r70), insurer claims PR 1 (G1, G2): one page of the hospital-wide « Parts assureurs » list (OPEN and
   * CLAIMED by default), filtered by payer, plan and service date, with each share's policy and claimed acts.
   */
  searchHospitalPayerShares(filter: PayerShareFilter = {}): Observable<PayerSharePage> {
    let params = new HttpParams();
    for (const status of filter.statuses ?? []) params = params.append('status', status);
    if (filter.payerId) params = params.set('payerId', filter.payerId);
    if (filter.planId) params = params.set('planId', filter.planId);
    if (filter.fromDate) params = params.set('fromDate', filter.fromDate);
    if (filter.toDate) params = params.set('toDate', filter.toDate);
    if (filter.page != null) params = params.set('page', String(filter.page));
    if (filter.size != null) params = params.set('size', String(filter.size));
    return this.http.get<PayerSharePage>('/billing-invoices/payer-shares', { params });
  }

  /** Insurer claims PR 1 (QA D3984): record or correct an invoice line's pre-authorisation number. */
  recordLinePreAuthorisation(
    invoiceId: string,
    itemId: string,
    authorizationNumber: string,
  ): Observable<InvoiceLinePreAuthorisation> {
    return this.http.put<InvoiceLinePreAuthorisation>(
      `/billing-invoices/${invoiceId}/items/${itemId}/pre-authorisation`,
      { authorizationNumber },
    );
  }

  /** Insurer claims PR 1 (QA D3984): the desk records an imaging order's pre-authorisation number. */
  recordImagingPreAuthorisation(
    orderId: string,
    authorizationNumber: string,
  ): Observable<ImagingPreAuthorisationDesk> {
    return this.http.put<ImagingPreAuthorisationDesk>(
      `/billing-invoices/imaging-orders/${orderId}/pre-authorisation`,
      { authorizationNumber },
    );
  }

  /** The payer refused, with its reason, a share not yet on a sent bordereau. */
  decidePayerShare(
    invoiceId: string,
    shareId: string,
    decision: PayerShareDecision,
    reason?: string,
  ): Observable<InvoicePayerShare[]> {
    return this.http.post<InvoicePayerShare[]>(
      `/billing-invoices/${invoiceId}/payer-shares/${shareId}/${decision}`,
      { reason },
    );
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

  /** PR 8 review: mobile money and bank transfer carry their reference (and operator) at hand-over. */
  payRefund(
    invoiceId: string,
    refundId: string,
    handover: { reference?: string | null; mobileMoneyOperator?: string | null } = {},
  ): Observable<InvoiceRefund> {
    return this.http.post<InvoiceRefund>(
      `/billing-invoices/${invoiceId}/refunds/${refundId}/pay`,
      handover,
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

  /**
   * QA D4222 / D4223 (round 81): the addresses on file this invoice may be
   * mailed to (the patient's own, the active guarantors'), and whether a send
   * to the patient's would be refused and why. Nothing is sent.
   */
  emailRecipients(id: string): Observable<InvoiceMailRecipientOptions> {
    return this.http.get<InvoiceMailRecipientOptions>(`/billing-invoices/${id}/email-recipients`);
  }

  /** Synchronous send; the invoice keeps its status (QA D758). attachPdf
   *  defaults to false server-side, so pass it explicitly. `reason` is
   *  required by the server for an address that is on no file (QA D4223). */
  emailInvoice(
    id: string,
    data: {
      to: string[];
      cc?: string[];
      bcc?: string[];
      message?: string;
      locale?: string;
      attachPdf: boolean;
      reason?: string;
    },
  ): Observable<{ status: string; sentAt?: string; error?: string }> {
    return this.http.post<{ status: string; sentAt?: string; error?: string }>(
      `/billing-invoices/${id}/email`,
      data,
    );
  }
}

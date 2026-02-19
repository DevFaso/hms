import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

export type PaymentStatus =
  | 'DRAFT'
  | 'PENDING'
  | 'PAID'
  | 'PARTIAL'
  | 'OVERDUE'
  | 'CANCELLED'
  | 'REFUNDED';

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
  status: PaymentStatus;
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
  status: PaymentStatus;
  notes?: string;
}

@Injectable({ providedIn: 'root' })
export class BillingService {
  private readonly http = inject(HttpClient);

  listInvoices(params?: {
    hospitalId?: string;
    status?: string;
    page?: number;
    size?: number;
  }): Observable<BillingInvoiceResponse[]> {
    let httpParams = new HttpParams();
    if (params?.page !== undefined) httpParams = httpParams.set('page', String(params.page));
    if (params?.size !== undefined) httpParams = httpParams.set('size', String(params.size));
    const body: Record<string, unknown> = {};
    if (params?.hospitalId) body['hospitalId'] = params.hospitalId;
    if (params?.status) body['statuses'] = [params.status];
    return this.http
      .post<{
        content: BillingInvoiceResponse[];
      }>('/billing-invoices/search', body, { params: httpParams })
      .pipe(map((res) => res?.content ?? []));
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

  getOverdue(): Observable<BillingInvoiceResponse[]> {
    return this.http.get<BillingInvoiceResponse[]>('/billing-invoices/overdue');
  }

  getInvoicePdf(id: string): Observable<Blob> {
    return this.http.get(`/billing-invoices/${id}/pdf`, { responseType: 'blob' });
  }

  getInvoiceItems(invoiceId: string): Observable<InvoiceItemResponse[]> {
    return this.http.get<InvoiceItemResponse[]>(`/invoice-items/invoice/${invoiceId}`);
  }

  updateInvoice(
    id: string,
    data: Partial<BillingInvoiceResponse>,
  ): Observable<BillingInvoiceResponse> {
    return this.http.put<BillingInvoiceResponse>(`/billing-invoices/${id}`, data);
  }

  emailInvoice(
    id: string,
    data: { to: string[]; cc?: string[]; bcc?: string[]; message?: string; attachPdf?: boolean },
  ): Observable<{ status: string; sentAt: string }> {
    return this.http.post<{ status: string; sentAt: string }>(
      `/billing-invoices/${id}/email`,
      data,
    );
  }

  createInvoice(req: BillingInvoiceRequest): Observable<BillingInvoiceResponse> {
    return this.http.post<BillingInvoiceResponse>('/billing-invoices', req);
  }

  deleteInvoice(id: string): Observable<void> {
    return this.http.delete<void>(`/billing-invoices/${id}`);
  }
}

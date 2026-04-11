import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

/* ── Models ── */

export interface InBasketItem {
  id: string;
  itemType: string;
  priority: string;
  status: string;
  title: string;
  body: string;
  referenceId: string;
  referenceType: string;
  encounterId: string;
  patientId: string;
  patientName: string;
  orderingProviderName: string;
  createdAt: string;
  readAt: string | null;
  acknowledgedAt: string | null;
}

export interface InBasketSummary {
  totalUnread: number;
  resultUnread: number;
  orderUnread: number;
  messageUnread: number;
  taskUnread: number;
}

export interface InBasketPage {
  content: InBasketItem[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/* ── Service ── */

@Injectable({ providedIn: 'root' })
export class InBasketService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/in-basket';

  getItems(
    hospitalId?: string,
    type?: string,
    status?: string,
    page = 0,
    size = 20,
  ): Observable<InBasketPage> {
    let params = new HttpParams().set('page', page.toString()).set('size', size.toString());
    if (hospitalId) params = params.set('hospitalId', hospitalId);
    if (type) params = params.set('type', type);
    if (status) params = params.set('status', status);
    return this.http.get<InBasketPage>(this.baseUrl, { params });
  }

  getSummary(hospitalId?: string): Observable<InBasketSummary> {
    let params = new HttpParams();
    if (hospitalId) params = params.set('hospitalId', hospitalId);
    return this.http.get<InBasketSummary>(`${this.baseUrl}/summary`, { params });
  }

  markAsRead(id: string): Observable<InBasketItem> {
    return this.http.put<InBasketItem>(`${this.baseUrl}/${id}/read`, {});
  }

  acknowledge(id: string): Observable<InBasketItem> {
    return this.http.put<InBasketItem>(`${this.baseUrl}/${id}/acknowledge`, {});
  }
}

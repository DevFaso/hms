import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export type ApiKeyStatus = 'ACTIVE' | 'REVOKED';
export type WebhookEndpointStatus = 'ACTIVE' | 'PAUSED' | 'DISABLED_FAILURES' | 'REVOKED';
export type WebhookDeliveryStatus = 'PENDING' | 'SENT' | 'ERROR';
export type WebhookEventType =
  'PING' | 'APPOINTMENT_BOOKED' | 'APPOINTMENT_CANCELLED' | 'APPOINTMENT_RESCHEDULED';

export interface ApiKey {
  id: string;
  label: string;
  keyPrefix: string;
  status: ApiKeyStatus;
  expiresOn?: string;
  lastUsedAt?: string;
  revokedAt?: string;
  createdAt?: string;
}

/** The issue/rotate response — the ONLY time the raw key exists client-side. */
export interface ApiKeyIssued {
  key: ApiKey;
  rawKey: string;
}

export interface WebhookEndpoint {
  id: string;
  url: string;
  description?: string;
  status: WebhookEndpointStatus;
  events: WebhookEventType[];
  consecutiveFailures: number;
  createdAt?: string;
}

/** The register/rotate-secret response — the ONLY time the secret exists client-side. */
export interface WebhookEndpointRegistered {
  endpoint: WebhookEndpoint;
  secret: string;
}

export interface WebhookDelivery {
  id: string;
  endpointId: string;
  endpointUrl?: string;
  eventType: WebhookEventType;
  status: WebhookDeliveryStatus;
  attempts: number;
  responseStatus?: number;
  lastError?: string;
  lastAttemptAt?: string;
  sentAt?: string;
  createdAt?: string;
  payload?: string;
}

export interface WebhookDeliveryPage {
  content: WebhookDelivery[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface WebhookEndpointRequest {
  url: string;
  description?: string;
  events: WebhookEventType[];
}

/** API keys + outbound webhooks (Tier 2 item 45). */
@Injectable({ providedIn: 'root' })
export class IntegrationKeysService {
  private readonly http = inject(HttpClient);

  /* ── API keys ── */

  listKeys(): Observable<ApiKey[]> {
    return this.http.get<ApiKey[]>('/api-keys');
  }

  issueKey(label: string, expiresOn?: string): Observable<ApiKeyIssued> {
    return this.http.post<ApiKeyIssued>('/api-keys', {
      label,
      expiresOn: expiresOn || null,
    });
  }

  rotateKey(id: string): Observable<ApiKeyIssued> {
    return this.http.post<ApiKeyIssued>(`/api-keys/${id}/rotate`, {});
  }

  revokeKey(id: string): Observable<ApiKey> {
    return this.http.put<ApiKey>(`/api-keys/${id}/revoke`, {});
  }

  /* ── Webhook endpoints ── */

  listEndpoints(): Observable<WebhookEndpoint[]> {
    return this.http.get<WebhookEndpoint[]>('/webhook-endpoints');
  }

  registerEndpoint(req: WebhookEndpointRequest): Observable<WebhookEndpointRegistered> {
    return this.http.post<WebhookEndpointRegistered>('/webhook-endpoints', req);
  }

  updateEndpoint(id: string, req: WebhookEndpointRequest): Observable<WebhookEndpoint> {
    return this.http.put<WebhookEndpoint>(`/webhook-endpoints/${id}`, req);
  }

  setEndpointActive(id: string, active: boolean): Observable<WebhookEndpoint> {
    const params = new HttpParams().set('active', String(active));
    return this.http.put<WebhookEndpoint>(`/webhook-endpoints/${id}/active`, {}, { params });
  }

  revokeEndpoint(id: string): Observable<WebhookEndpoint> {
    return this.http.put<WebhookEndpoint>(`/webhook-endpoints/${id}/revoke`, {});
  }

  rotateEndpointSecret(id: string): Observable<WebhookEndpointRegistered> {
    return this.http.post<WebhookEndpointRegistered>(`/webhook-endpoints/${id}/rotate-secret`, {});
  }

  pingEndpoint(id: string): Observable<WebhookDelivery> {
    return this.http.post<WebhookDelivery>(`/webhook-endpoints/${id}/ping`, {});
  }

  deliveries(endpointId: string, page = 0, size = 25): Observable<WebhookDeliveryPage> {
    const params = new HttpParams().set('page', String(page)).set('size', String(size));
    return this.http.get<WebhookDeliveryPage>(`/webhook-endpoints/${endpointId}/deliveries`, {
      params,
    });
  }
}

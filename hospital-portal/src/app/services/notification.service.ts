import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, Subject } from 'rxjs';

export interface Notification {
  id: string;
  message: string;
  type: string;
  read: boolean;
  recipientUsername: string;
  createdAt: string;
}

export interface NotificationPage {
  content: Notification[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private ws: WebSocket | null = null;
  private readonly notificationSubject = new Subject<Notification>();
  private readonly http = inject(HttpClient);

  getNotifications(params?: {
    read?: boolean;
    page?: number;
    size?: number;
  }): Observable<NotificationPage> {
    let httpParams = new HttpParams();
    if (params?.read !== undefined) httpParams = httpParams.set('read', String(params.read));
    if (params?.page !== undefined) httpParams = httpParams.set('page', String(params.page));
    if (params?.size !== undefined) httpParams = httpParams.set('size', String(params.size));
    return this.http.get<NotificationPage>('/notifications', { params: httpParams });
  }

  markAsRead(id: string): Observable<void> {
    return this.http.put<void>(`/notifications/${id}/read`, {});
  }

  connectWebSocket(username: string): void {
    const WS =
      typeof globalThis !== 'undefined' && globalThis.WebSocket ? globalThis.WebSocket : undefined;
    if (!WS) return;

    // Derive the WebSocket URL from the current page origin so it works in
    // every environment (local dev, staging, production) without hardcoding.
    // Use wss:// when the page is served over HTTPS, ws:// otherwise.
    const loc =
      typeof globalThis !== 'undefined' && globalThis.location ? globalThis.location : null;
    const wsProto = loc?.protocol === 'https:' ? 'wss' : 'ws';
    const wsHost = loc?.host ?? 'localhost';
    const wsUrl = `${wsProto}://${wsHost}/ws/notifications?user=${username}`;

    this.ws = new WS(wsUrl);
    this.ws.onmessage = (event) => {
      this.notificationSubject.next(JSON.parse(event.data as string) as Notification);
    };
  }

  getNotificationStream(): Observable<Notification> {
    return this.notificationSubject.asObservable();
  }

  disconnectWebSocket(): void {
    this.ws?.close();
    this.ws = null;
  }
}

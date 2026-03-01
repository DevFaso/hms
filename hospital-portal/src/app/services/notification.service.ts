import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { Client, IMessage } from '@stomp/stompjs';
// sockjs-client is a CommonJS module. Importing it as a namespace (`import * as`)
// does NOT give the constructor when esModuleInterop is off (Angular default).
// We use require() inside the factory so the bundler wraps it correctly at runtime.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const SockJS = require('sockjs-client') as new (url: string) => WebSocket;

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
  private stompClient: Client | null = null;
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

  /**
   * Opens a STOMP-over-SockJS connection to /ws-chat and subscribes to
   * /topic/notifications, filtering messages for the given username.
   *
   * The backend uses Spring WebSocket (STOMP broker) which requires the
   * SockJS handshake — a raw WebSocket to an arbitrary path does not work.
   */
  connectWebSocket(username: string): void {
    if (typeof globalThis === 'undefined' || !globalThis.WebSocket) return;

    // Build the SockJS URL pointing at /api/ws-chat (context path included).
    // Using a relative URL so it works in every environment automatically.
    const sockUrl = '/api/ws-chat';

    this.stompClient = new Client({
      // SockJS factory — required because the backend endpoint uses .withSockJS()
      webSocketFactory: () => new SockJS(sockUrl),
      // Reconnect automatically every 5 s if the connection drops
      reconnectDelay: 5000,
      onConnect: () => {
        this.stompClient?.subscribe('/topic/notifications', (frame: IMessage) => {
          try {
            const notification = JSON.parse(frame.body) as Notification;
            // Only emit notifications addressed to the current user
            if (!notification.recipientUsername || notification.recipientUsername === username) {
              this.notificationSubject.next(notification);
            }
          } catch {
            // Malformed frame — ignore
          }
        });
      },
    });

    this.stompClient.activate();
  }

  getNotificationStream(): Observable<Notification> {
    return this.notificationSubject.asObservable();
  }

  disconnectWebSocket(): void {
    this.stompClient?.deactivate().catch(() => undefined);
    this.stompClient = null;
  }
}

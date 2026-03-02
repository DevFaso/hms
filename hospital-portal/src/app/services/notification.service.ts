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

  /** Exponential-backoff state for WebSocket reconnection */
  private reconnectAttempts = 0;
  private readonly maxReconnectDelay = 60_000; // cap at 60 s
  private readonly baseReconnectDelay = 5_000; // start at 5 s

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
   * Uses exponential backoff for reconnection (5 s → 10 s → 20 s → … → 60 s)
   * to avoid hammering the backend when it is down or restarting.
   */
  connectWebSocket(username: string): void {
    if (typeof globalThis === 'undefined' || !globalThis.WebSocket) return;

    // Build the SockJS URL pointing at /api/ws-chat (context path included).
    const sockUrl = '/api/ws-chat';

    this.stompClient = new Client({
      // SockJS factory — required because the backend endpoint uses .withSockJS()
      webSocketFactory: () => new SockJS(sockUrl),

      // Start with baseReconnectDelay; double on each failure up to the cap.
      // STOMP calls reconnectDelay as a property each time it needs the value,
      // so returning a getter function gives us live-updated backoff.
      reconnectDelay: this.baseReconnectDelay,

      onConnect: () => {
        // Reset backoff on successful connection
        this.reconnectAttempts = 0;
        if (this.stompClient) {
          this.stompClient.reconnectDelay = this.baseReconnectDelay;
        }

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

      onDisconnect: () => {
        // Increase backoff for next reconnect attempt
        this.reconnectAttempts++;
        const nextDelay = Math.min(
          this.baseReconnectDelay * Math.pow(2, this.reconnectAttempts),
          this.maxReconnectDelay,
        );
        if (this.stompClient) {
          this.stompClient.reconnectDelay = nextDelay;
        }
      },

      onStompError: () => {
        // STOMP-level error — apply same backoff escalation
        this.reconnectAttempts++;
        const nextDelay = Math.min(
          this.baseReconnectDelay * Math.pow(2, this.reconnectAttempts),
          this.maxReconnectDelay,
        );
        if (this.stompClient) {
          this.stompClient.reconnectDelay = nextDelay;
        }
      },

      onWebSocketError: () => {
        // Transport-level error (e.g. backend is down) — same backoff
        this.reconnectAttempts++;
        const nextDelay = Math.min(
          this.baseReconnectDelay * Math.pow(2, this.reconnectAttempts),
          this.maxReconnectDelay,
        );
        if (this.stompClient) {
          this.stompClient.reconnectDelay = nextDelay;
        }
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
    this.reconnectAttempts = 0;
  }
}

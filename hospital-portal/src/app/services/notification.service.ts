import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { Client, IMessage } from '@stomp/stompjs';

import { AuthService } from '../auth/auth.service';

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
  private readonly auth = inject(AuthService);

  /** Exponential-backoff state for WebSocket reconnection */
  private reconnectAttempts = 0;
  private readonly maxReconnectDelay = 60_000; // cap at 60 s
  private readonly baseReconnectDelay = 5_000; // start at 5 s

  /**
   * Monotonically increasing generation counter. Incremented on every
   * connectWebSocket() and disconnectWebSocket() call. The async .then()
   * callback captures the generation at call-time and bails out if it has
   * become stale — preventing a reconnect after logout / component destroy.
   */
  private connectGeneration = 0;

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
   * /user/topic/notifications (Spring's user-destination prefix ensures
   * only messages targeted at the authenticated user are received).
   *
   * Uses exponential backoff for reconnection (5 s → 10 s → 20 s → … → 60 s)
   * to avoid hammering the backend when it is down or restarting.
   *
   * sockjs-client is a CommonJS module; it is loaded lazily via dynamic import
   * so that Vite's ESM bundler does not crash at module evaluation time.
   */
  connectWebSocket(): void {
    if (typeof globalThis === 'undefined' || !globalThis.WebSocket) return;

    // Build the SockJS URL pointing at /api/ws-chat (context path included).
    // SockJS initiates plain browser GETs (/info, transport negotiation) that
    // bypass Angular's HttpClient interceptor — the Authorization header is
    // never attached. Pass the JWT as a query parameter so the backend's
    // JwtAuthenticationFilter can authenticate the handshake.
    const token = this.auth.getToken();
    const sockUrl = token ? `/api/ws-chat?token=${encodeURIComponent(token)}` : '/api/ws-chat';

    // Capture the generation *before* the async gap. If disconnectWebSocket()
    // is called while the import() is in flight, the generation increments and
    // the stale .then() callback bails out without creating a new client.
    const generation = ++this.connectGeneration;

    // Lazily import sockjs-client to avoid "Dynamic require is not supported"
    // crashes in Vite/ESM at module evaluation time.
    void import('sockjs-client')
      .then((mod) => {
        // Bail out if this connect attempt has been superseded by a disconnect
        // (or a subsequent connect) that occurred while the import was resolving.
        if (generation !== this.connectGeneration) return;

        // The CJS default export may be wrapped under `.default` by ESM interop.
        const SockJSCtor = (mod.default ?? mod) as new (url: string) => WebSocket;

        this.stompClient = new Client({
          webSocketFactory: () => new SockJSCtor(sockUrl),

          reconnectDelay: this.baseReconnectDelay,

          onConnect: () => {
            this.reconnectAttempts = 0;
            if (this.stompClient) {
              this.stompClient.reconnectDelay = this.baseReconnectDelay;
            }

            this.stompClient?.subscribe('/user/topic/notifications', (frame: IMessage) => {
              try {
                const notification = JSON.parse(frame.body) as Notification;
                this.notificationSubject.next(notification);
              } catch {
                // Malformed frame — ignore
              }
            });
          },

          onDisconnect: () => {
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
      })
      .catch(() => {
        // SockJS unavailable (e.g. bundler environment without CJS support) —
        // real-time notifications will be disabled; the rest of the app is unaffected.
      });
  }

  getNotificationStream(): Observable<Notification> {
    return this.notificationSubject.asObservable();
  }

  disconnectWebSocket(): void {
    // Invalidate any in-flight connectWebSocket() async chain before tearing down.
    this.connectGeneration++;
    this.stompClient?.deactivate().catch(() => undefined);
    this.stompClient = null;
    this.reconnectAttempts = 0;
  }
}

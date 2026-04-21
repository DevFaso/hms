import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { Client, IMessage } from '@stomp/stompjs';

import { AuthService } from '../auth/auth.service';

export interface Notification {
  id: string;
  title?: string;
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

export interface NotificationPreference {
  id?: string;
  notificationType: string;
  channel: string;
  enabled: boolean;
}

export interface NotificationPreferenceUpdate {
  notificationType: string;
  channel: string;
  enabled: boolean;
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private stompClient: Client | null = null;
  private readonly notificationSubject = new Subject<Notification>();
  private readonly readSubject = new Subject<string>(); // emits id when marked read
  private readonly allReadSubject = new Subject<void>();
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  /** Exponential-backoff state for WebSocket reconnection */
  private reconnectAttempts = 0;
  private readonly maxReconnectAttempts = 5;
  private readonly maxReconnectDelay = 60_000; // cap at 60 s
  private readonly baseReconnectDelay = 5_000; // start at 5 s

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

  markAsReadAndNotify(id: string): void {
    this.markAsRead(id).subscribe({
      next: () => this.readSubject.next(id),
    });
  }

  markAllReadAndNotify(): Observable<void> {
    const obs = this.http.patch<void>('/notifications/read-all', {});
    obs.subscribe({ next: () => this.allReadSubject.next() });
    return obs;
  }

  getReadStream(): Observable<string> {
    return this.readSubject.asObservable();
  }

  getAllReadStream(): Observable<void> {
    return this.allReadSubject.asObservable();
  }

  connectWebSocket(): void {
    if (typeof globalThis === 'undefined' || !globalThis.WebSocket) return;

    // Don't attempt WebSocket connection if the token is expired
    const token = this.auth.getToken();
    if (!token || this.auth.isExpired(token)) return;

    const generation = ++this.connectGeneration;

    // T-38: exchange JWT for a single-use WebSocket ticket, then hand it to SockJS.
    this.http.post<{ ticket: string }>('/auth/ws-ticket', {}).subscribe({
      next: (res) => {
        if (generation !== this.connectGeneration) return;
        const ticket = res?.ticket;
        if (!ticket) return;
        this.activateStompWithTicket(ticket, generation);
      },
      error: () => {
        // Ticket issuance failed — skip WebSocket silently; REST flows still work.
      },
    });
  }

  private activateStompWithTicket(ticket: string, generation: number): void {
    const sockUrl = `/api/ws-chat?ticket=${encodeURIComponent(ticket)}`;

    void import('sockjs-client')
      .then((mod) => {
        if (generation !== this.connectGeneration) return;

        const SockJSCtor = (mod.default ?? mod) as new (url: string) => WebSocket;

        this.stompClient = new Client({
          webSocketFactory: () => new SockJSCtor(sockUrl),

          reconnectDelay: this.baseReconnectDelay,

          // Abort reconnection if the token has expired or max retries exceeded
          beforeConnect: () => {
            if (this.auth.isExpired() || this.reconnectAttempts >= this.maxReconnectAttempts) {
              this.disconnectWebSocket();
              throw new Error('Token expired or max reconnect attempts reached');
            }
          },

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
            // If token has expired or max retries exceeded, stop reconnecting
            if (this.auth.isExpired() || this.reconnectAttempts >= this.maxReconnectAttempts) {
              this.disconnectWebSocket();
              return;
            }
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
            // STOMP-level auth failures — stop reconnecting with stale token
            if (this.auth.isExpired() || this.reconnectAttempts >= this.maxReconnectAttempts) {
              this.disconnectWebSocket();
              return;
            }
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
            // Transport-level failure (401 from SockJS /info) — stop if token expired
            if (this.auth.isExpired() || this.reconnectAttempts >= this.maxReconnectAttempts) {
              this.disconnectWebSocket();
              return;
            }
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
      .catch((_: unknown) => {
        // Token expired or unavailable — skip WebSocket connection silently
      });
  }

  getNotificationStream(): Observable<Notification> {
    return this.notificationSubject.asObservable();
  }

  disconnectWebSocket(): void {
    this.connectGeneration++;
    this.stompClient?.deactivate().catch(() => undefined);
    this.stompClient = null;
    this.reconnectAttempts = 0;
  }

  // ── Notification Preferences ──────────────────────────────────────

  getPreferences(): Observable<NotificationPreference[]> {
    return this.http.get<NotificationPreference[]>('/notifications/preferences');
  }

  updatePreferences(updates: NotificationPreferenceUpdate[]): Observable<NotificationPreference[]> {
    return this.http.put<NotificationPreference[]>('/notifications/preferences', updates);
  }
}

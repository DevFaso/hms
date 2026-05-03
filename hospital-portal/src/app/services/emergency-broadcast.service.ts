import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';

import { AuthService } from '../auth/auth.service';

export interface EmergencyBroadcastFrame {
  type?: string;
  severity?: 'INFO' | 'WARN' | 'CRITICAL' | string;
  message?: string;
  issuedBy?: string;
  issuedAt?: string;
}

const TOPIC = '/topic/emergency-broadcast';
const RECONNECT_BASE_MS = 5_000;
const RECONNECT_MAX_MS = 60_000;
const MAX_RECONNECT_ATTEMPTS = 5;

/**
 * MVP-7b — STOMP consumer for the emergency-broadcast topic the
 * super-admin Emergency Controls publish to (see
 * {@code EmergencyControlServiceImpl.broadcast}). Holds the latest
 * frame in a signal so a single banner mounted in the shell can
 * render across every authenticated route.
 *
 * <p>Auth: same {@code /auth/ws-ticket} short-lived ticket flow as
 * {@code PatientTrackerWsService} so a stale JWT can't be replayed
 * across reconnects.
 */
@Injectable({ providedIn: 'root' })
export class EmergencyBroadcastService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  /** Latest broadcast frame; null when nothing is active or banner was dismissed. */
  readonly latest = signal<EmergencyBroadcastFrame | null>(null);

  private stompClient: Client | null = null;
  private subscription: StompSubscription | null = null;
  private connectGeneration = 0;
  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  connect(): void {
    if (typeof globalThis === 'undefined' || !globalThis.WebSocket) return;
    const token = this.auth.getToken();
    if (!token || this.auth.isExpired(token)) return;
    if (this.stompClient?.active) return;

    const generation = ++this.connectGeneration;
    this.http.post<{ ticket: string }>('/auth/ws-ticket', {}).subscribe({
      next: (res) => {
        if (generation !== this.connectGeneration) return;
        const ticket = res?.ticket;
        if (!ticket) return;
        this.activate(ticket, generation);
      },
      error: () => this.scheduleReconnect(generation),
    });
  }

  disconnect(): void {
    this.connectGeneration++;
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.subscription?.unsubscribe();
    this.subscription = null;
    this.stompClient?.deactivate().catch(() => undefined);
    this.stompClient = null;
    this.reconnectAttempts = 0;
  }

  /** Operator-driven dismiss — clears the banner without affecting the underlying topic. */
  dismiss(): void {
    this.latest.set(null);
  }

  private activate(ticket: string, generation: number): void {
    const sockUrl = `/api/ws-chat?ticket=${encodeURIComponent(ticket)}`;

    void import('sockjs-client')
      .then((mod) => {
        if (generation !== this.connectGeneration) return;
        const SockJSCtor = (mod.default ?? mod) as new (url: string) => WebSocket;

        this.stompClient = new Client({
          webSocketFactory: () => new SockJSCtor(sockUrl),
          reconnectDelay: 0,

          beforeConnect: () => {
            if (this.auth.isExpired()) {
              this.disconnect();
              throw new Error('Token expired');
            }
          },

          onConnect: () => {
            this.reconnectAttempts = 0;
            this.subscription = this.stompClient!.subscribe(TOPIC, (frame: IMessage) => {
              try {
                const parsed = JSON.parse(frame.body) as EmergencyBroadcastFrame;
                this.latest.set(parsed);
              } catch {
                /* malformed frame — drop */
              }
            });
          },

          onDisconnect: () => this.scheduleReconnect(generation),
          onStompError: () => this.scheduleReconnect(generation),
          onWebSocketError: () => this.scheduleReconnect(generation),
        });
        this.stompClient.activate();
      })
      .catch(() => {
        /* sockjs-client failed to load — banner stays empty until next connect attempt */
      });
  }

  private scheduleReconnect(generation: number): void {
    if (generation !== this.connectGeneration) return;
    if (this.auth.isExpired() || this.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      this.disconnect();
      return;
    }
    this.reconnectAttempts++;
    const delay = Math.min(
      RECONNECT_BASE_MS * Math.pow(2, this.reconnectAttempts - 1),
      RECONNECT_MAX_MS,
    );
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
    }
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.stompClient?.deactivate().catch(() => undefined);
      this.stompClient = null;
      this.connect();
    }, delay);
  }
}

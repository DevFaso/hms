import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { Client, IFrame, IMessage, StompConfig, StompSubscription } from '@stomp/stompjs';

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
 * A subscription the server lets stand this long was accepted (a refusal is an
 * immediate ERROR): the backoff then forgets earlier failures.
 */
const SUBSCRIPTION_GRACE_MS = 2_000;
/**
 * The ERROR frame's `message` header for an authorization refusal
 * (`StompRefusalErrorHandler.ACCESS_DENIED` on the server).
 */
export const STOMP_ACCESS_DENIED = 'access-denied';
/**
 * The ERROR frame's `message` header when the server could not resolve the
 * caller just now (`StompRefusalErrorHandler.UNAVAILABLE`): retried with backoff.
 */
export const STOMP_UNAVAILABLE = 'unavailable';

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
 *
 * <p>Refusal: a user the server refuses the topic (a provider user) gets an
 * ERROR frame saying {@link STOMP_ACCESS_DENIED}; the service then stops for
 * that token instead of reconnecting, and a NEW token (a refresh, another
 * sign-in) re-arms it. {@link STOMP_UNAVAILABLE} (the server could not
 * resolve the caller just now) is retried with backoff, as is any other
 * failure; once the backoff gives up, a new token re-arms it too. The backoff counter is reset once a
 * subscription has stood {@link SUBSCRIPTION_GRACE_MS} without a refusal, so a
 * flaky link keeps reconnecting, but never by a connect whose subscription was
 * then refused.
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
  private stableTimer: ReturnType<typeof setTimeout> | null = null;
  /** The token the server refused the topic to: no further attempt with it. */
  private refusedToken: string | null = null;
  /** The shell asked for the socket (connect) and has not released it (disconnect). */
  private wanted = false;
  /** Nothing is running: no client, no pending ticket, no reconnect timer. */
  private idle = true;

  constructor() {
    // A new token re-arms a socket the shell still wants but that stopped (a
    // refusal for the previous token, or a backoff that gave up).
    effect(() => {
      this.auth.tokenVersion();
      untracked(() => {
        if (this.wanted && this.idle) this.start();
      });
    });
  }

  connect(): void {
    this.wanted = true;
    this.start();
  }

  disconnect(): void {
    this.wanted = false;
    this.teardown();
  }

  private start(): void {
    if (typeof globalThis === 'undefined' || !globalThis.WebSocket) return;
    const token = this.auth.getToken();
    if (!token || this.auth.isExpired(token)) return;
    if (token === this.refusedToken) return;
    if (this.stompClient?.active) return;

    this.idle = false;
    const generation = ++this.connectGeneration;
    this.http.post<{ ticket: string }>('/auth/ws-ticket', {}).subscribe({
      next: (res) => {
        if (generation !== this.connectGeneration) return;
        const ticket = res?.ticket;
        if (!ticket) {
          this.scheduleReconnect(generation);
          return;
        }
        this.activate(ticket, generation);
      },
      error: () => this.scheduleReconnect(generation),
    });
  }

  /** Stop everything; whether the shell still wants the socket is untouched. */
  private teardown(): void {
    this.connectGeneration++;
    this.idle = true;
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.clearStableTimer();
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

    void this.loadSockJs()
      .then((mod) => {
        if (generation !== this.connectGeneration) return;
        const SockJSCtor = (mod.default ?? mod) as new (url: string) => WebSocket;

        this.stompClient = this.createClient({
          webSocketFactory: () => new SockJSCtor(sockUrl),
          reconnectDelay: 0,

          beforeConnect: () => {
            if (this.auth.isExpired()) {
              this.teardown();
              throw new Error('Token expired');
            }
          },

          onConnect: () => {
            this.markStableLater(generation);
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
          // access-denied: refused for good for this token. unavailable (and
          // any other failure): retried with backoff.
          onStompError: (frame: IFrame) =>
            frame.headers?.['message'] === STOMP_ACCESS_DENIED
              ? this.stopRefused(generation)
              : this.scheduleReconnect(generation),
          onWebSocketError: () => this.scheduleReconnect(generation),
        });
        this.stompClient.activate();
      })
      .catch(() => {
        // sockjs-client failed to load: retried with backoff like any failure.
        this.scheduleReconnect(generation);
      });
  }

  /** The STOMP client; a seam for the specs. */
  protected createClient(config: StompConfig): Client {
    return new Client(config);
  }

  /** The SockJS module, loaded on demand; a seam for the specs. */
  protected loadSockJs(): Promise<{ default?: unknown }> {
    return import('sockjs-client') as Promise<{ default?: unknown }>;
  }

  /** The server refused this token the topic: stop, and do not try again with it. */
  private stopRefused(generation: number): void {
    if (generation !== this.connectGeneration) return;
    this.refusedToken = this.auth.getToken();
    this.teardown();
  }

  /** Forget earlier failures once this subscription has stood the grace without a refusal. */
  private markStableLater(generation: number): void {
    this.clearStableTimer();
    this.stableTimer = setTimeout(() => {
      this.stableTimer = null;
      if (generation === this.connectGeneration) this.reconnectAttempts = 0;
    }, SUBSCRIPTION_GRACE_MS);
  }

  private clearStableTimer(): void {
    if (this.stableTimer !== null) {
      clearTimeout(this.stableTimer);
      this.stableTimer = null;
    }
  }

  private scheduleReconnect(generation: number): void {
    if (generation !== this.connectGeneration) return;
    this.clearStableTimer();
    if (this.auth.isExpired() || this.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      this.teardown();
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
      this.start();
    }, delay);
  }
}

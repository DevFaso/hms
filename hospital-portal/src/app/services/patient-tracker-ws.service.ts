import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';

import { AuthService } from '../auth/auth.service';

export interface PatientTrackerEvent {
  hospitalId: string;
  departmentId: string | null;
  encounterId: string;
  patientId: string;
  previousStatus: string | null;
  newStatus: string;
  emittedAt: string;
}

/**
 * STOMP subscription to {@code /topic/patient-tracker/{hospitalId}}.
 * Replaces the 30 s board poll on metered links — the component still keeps
 * a longer-interval poll as a fallback when the socket is offline.
 *
 * <p>Auth uses the same {@code /auth/ws-ticket} short-lived ticket flow as
 * {@link NotificationService} so we don't reuse a stale JWT in reconnects.
 */
@Injectable({ providedIn: 'root' })
export class PatientTrackerWsService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  private stompClient: Client | null = null;
  private subscription: StompSubscription | null = null;
  private currentHospitalId: string | null = null;
  private connectGeneration = 0;
  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  private readonly maxReconnectAttempts = 5;
  private readonly maxReconnectDelay = 60_000;
  private readonly baseReconnectDelay = 5_000;

  private readonly events$ = new Subject<PatientTrackerEvent>();
  private readonly connection$ = new Subject<boolean>();

  getEvents(): Observable<PatientTrackerEvent> {
    return this.events$.asObservable();
  }

  /** True (connected) | false (disconnected). Used by the component to gate poll cadence. */
  getConnectionState(): Observable<boolean> {
    return this.connection$.asObservable();
  }

  connect(hospitalId: string): void {
    if (typeof globalThis === 'undefined' || !globalThis.WebSocket) return;
    const token = this.auth.getToken();
    if (!token || this.auth.isExpired(token)) return;

    if (this.currentHospitalId === hospitalId && this.stompClient?.active) {
      return;
    }

    this.disconnect();
    this.currentHospitalId = hospitalId;
    const generation = ++this.connectGeneration;

    this.http.post<{ ticket: string }>('/auth/ws-ticket', {}).subscribe({
      next: (res) => {
        if (generation !== this.connectGeneration) return;
        const ticket = res?.ticket;
        if (!ticket) return;
        this.activateStomp(hospitalId, ticket, generation);
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
    this.currentHospitalId = null;
    this.reconnectAttempts = 0;
    this.connection$.next(false);
  }

  private activateStomp(hospitalId: string, ticket: string, generation: number): void {
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
            this.connection$.next(true);
            this.subscription = this.stompClient!.subscribe(
              `/topic/patient-tracker/${hospitalId}`,
              (frame: IMessage) => {
                try {
                  const event = JSON.parse(frame.body) as PatientTrackerEvent;
                  this.events$.next(event);
                } catch {
                  /* malformed frame — drop */
                }
              },
            );
          },

          onDisconnect: () => {
            this.connection$.next(false);
            this.scheduleReconnect(generation);
          },
          onStompError: () => {
            this.connection$.next(false);
            this.scheduleReconnect(generation);
          },
          onWebSocketError: () => {
            this.connection$.next(false);
            this.scheduleReconnect(generation);
          },
        });
        this.stompClient.activate();
      })
      .catch(() => {
        /* sockjs-client failed to load — caller falls back to poll */
      });
  }

  private scheduleReconnect(generation: number): void {
    if (generation !== this.connectGeneration) return;
    if (this.auth.isExpired() || this.reconnectAttempts >= this.maxReconnectAttempts) {
      this.disconnect();
      return;
    }
    this.reconnectAttempts++;
    const delay = Math.min(
      this.baseReconnectDelay * Math.pow(2, this.reconnectAttempts - 1),
      this.maxReconnectDelay,
    );
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
    }
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      const hospitalId = this.currentHospitalId;
      if (!hospitalId) return;
      this.stompClient?.deactivate().catch(() => undefined);
      this.stompClient = null;
      this.connect(hospitalId);
    }, delay);
  }
}

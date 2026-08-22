import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';

/** Mirrors DowntimeController.DowntimeStatusResponse. */
export interface DowntimeStatus {
  readOnly: boolean;
  message: string | null;
  activatedAt: string | null;
}

/**
 * Downtime read-only state (P3 #23a). Unlike the emergency broadcast
 * (push-only STOMP a post-send login never sees), this is a PERSISTED
 * state polled over plain HTTP, so the banner survives login and refresh.
 * The shell starts/stops the 60 s poll with the session.
 */
@Injectable({ providedIn: 'root' })
export class DowntimeService {
  private readonly http = inject(HttpClient);

  readonly status = signal<DowntimeStatus | null>(null);

  private pollHandle: ReturnType<typeof setInterval> | null = null;

  load(): void {
    this.http.get<DowntimeStatus>('/downtime/status').subscribe({
      next: (status) => this.status.set(status),
      error: () => {
        /* keep the last known state — a failed poll is not "downtime over" */
      },
    });
  }

  startPolling(): void {
    if (this.pollHandle) return;
    this.load();
    this.pollHandle = setInterval(() => this.load(), 60_000);
  }

  stopPolling(): void {
    if (this.pollHandle) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
    }
    this.status.set(null);
  }

  /** Called by the error interceptor when a write bounces off the
   *  read-only filter, so the banner appears without waiting for a poll. */
  markReadOnly(message: string | null): void {
    this.status.set({ readOnly: true, message, activatedAt: null });
  }

  toggle(readOnly: boolean, message?: string): Observable<DowntimeStatus> {
    return this.http.put<DowntimeStatus>('/super-admin/downtime', { readOnly, message });
  }
}

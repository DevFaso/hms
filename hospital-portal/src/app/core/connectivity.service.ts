import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';

/**
 * - `online`: the last request reached the server (or nothing failed yet);
 * - `offline`: the device says it has no network;
 * - `unreachable`: the device says it is online, but a request never reached
 *   the server (a phone with signal and no data, a dropped Wi-Fi).
 */
export type ConnectivityState = 'online' | 'offline' | 'unreachable';

/**
 * QA D2352 (r63): when the connection dropped, a page failed with nothing on
 * screen, or a read quietly showed "nothing to show". The state here is fed
 * by the browser's online/offline events and by every request's outcome
 * (connectivityInterceptor), and the banner in AppComponent says it in the
 * reader's language with a retry.
 */
@Injectable({ providedIn: 'root' })
export class ConnectivityService {
  private readonly win = inject(DOCUMENT).defaultView;
  readonly state = signal<ConnectivityState>(
    this.win?.navigator.onLine === false ? 'offline' : 'online',
  );
  /** Set when the connection comes back, cleared by the banner after it has said so. */
  readonly restored = signal(false);

  constructor() {
    const win = this.win;
    if (!win) return;
    const offline = (): void => this.state.set('offline');
    const online = (): void => this.markOnline();
    win.addEventListener('offline', offline);
    win.addEventListener('online', online);
    inject(DestroyRef).onDestroy(() => {
      win.removeEventListener('offline', offline);
      win.removeEventListener('online', online);
    });
  }

  /** A request never reached the server (HTTP status 0). */
  noteNetworkFailure(): void {
    this.state.set(this.win?.navigator.onLine === false ? 'offline' : 'unreachable');
  }

  /** A request got an answer from the server, whatever its status. */
  noteResponse(): void {
    this.markOnline();
  }

  private markOnline(): void {
    if (this.state() === 'online') return;
    this.state.set('online');
    this.restored.set(true);
  }
}

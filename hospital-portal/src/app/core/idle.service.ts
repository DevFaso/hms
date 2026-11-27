import {
  Injectable,
  Injector,
  inject,
  NgZone,
  OnDestroy,
  PLATFORM_ID,
  signal,
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';

import { ssoTabState, tabBinding } from './browser-storage';

/** Reloads the page. An object so a spec can stand in for it. */
export const PAGE_RELOAD = { reload: (): void => globalThis.location.reload() };

interface TabMessage {
  /** The tab's binding: only tabs of the same account listen to each other. */
  b: string | null;
  /** `a` = activity, `l` = the screen was locked. */
  t: 'a' | 'l';
}

/**
 * Tracks user activity (mouse, keyboard, scroll, touch, visibility change).
 * After the idle timeout the screen locks: `locked` shows the lock screen and
 * `contentHidden` takes the page behind it out of the document.
 *
 * The lock is the session's, kept by the server (`POST /auth/session/lock`):
 * while it holds, the server refuses every request of the session except the
 * ones that unlock it, read who it is or end it. What this tab's storage says
 * is only a hint for the first paint after a reload. The lock is lifted by the
 * lock screen alone, after the server accepted the password (or a new sign-in
 * at the identity provider).
 *
 * Tabs of the same account share their activity, so a tab left in the
 * background does not lock the session while its neighbour is being used;
 * when one locks, they all show the lock screen.
 */
@Injectable({ providedIn: 'root' })
export class IdleService implements OnDestroy {
  private static readonly IDLE_KEY = 'hms_idle_locked';
  private static readonly LOCK_TS_KEY = 'hms_lock_ts';
  private static readonly CHANNEL = 'hms.idle';

  /** Default idle timeout: 10 minutes (in ms) */
  private static readonly DEFAULT_TIMEOUT_MS = 10 * 60 * 1000;

  /** Activity is told to the other tabs at most this often. */
  private static readonly SHARE_EVERY_MS = 5_000;

  private readonly zone = inject(NgZone);
  private readonly injector = inject(Injector);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  /** Reactive signal — `true` when screen should be locked */
  readonly locked = signal(false);

  /**
   * `true` while the page behind the lock screen must be out of the document.
   * Set with the lock; cleared only by a verified unlock (or the next shell).
   */
  readonly contentHidden = signal(false);

  private timeoutMs = IdleService.DEFAULT_TIMEOUT_MS;
  private idleTimer: ReturnType<typeof setTimeout> | null = null;
  private lastActivity = Date.now();
  private lastShared = 0;
  private started = false;
  private channel: BroadcastChannel | null = null;
  /** The server knows about the current lock. */
  private lockReported = false;
  /**
   * The page behind the lock was loaded (or kept asking) while the session was
   * locked, so its requests were refused: a verified unlock reloads it.
   */
  private reloadOnUnlock = false;

  private readonly eventNames = [
    'mousemove',
    'mousedown',
    'keydown',
    'scroll',
    'touchstart',
    'click',
  ] as const;

  private readonly onActivity = (): void => this.noteActivity(true);
  private readonly onVisibility = (): void => this.handleVisibility();

  /** Start monitoring idle activity. Call once when the shell initialises. */
  start(timeoutMs?: number): void {
    if (!this.isBrowser || this.started) return;
    if (timeoutMs) this.timeoutMs = timeoutMs;
    this.started = true;
    // A new shell has a new page: whatever a previous one hid is gone with it.
    this.contentHidden.set(this.locked());

    // A lock from before a reload: shown at once, and the page loads again
    // after the unlock (the server refuses its requests until then).
    try {
      if (sessionStorage.getItem(IdleService.IDLE_KEY) === 'true') {
        this.reloadOnUnlock = true;
        this.enterLock();
        // A tab signed in at the identity provider may be coming back from the
        // sign-in its lock screen asked for: the lock screen decides (a second
        // report would move the lock past that sign-in).
        if (ssoTabState() === null) this.reportLock();
      }
    } catch {
      /* no storage */
    }

    // Register activity listeners outside Angular zone for performance
    this.zone.runOutsideAngular(() => {
      for (const evt of this.eventNames) {
        document.addEventListener(evt, this.onActivity, { passive: true });
      }
      document.addEventListener('visibilitychange', this.onVisibility);
    });
    this.openChannel();

    // Only start the idle timer if not already locked
    if (!this.locked()) {
      this.lastActivity = Date.now();
      this.arm(this.timeoutMs);
    }
  }

  /** Stop monitoring. Call in ngOnDestroy of the shell. */
  stop(): void {
    if (!this.isBrowser) return;
    this.clearTimer();
    for (const evt of this.eventNames) {
      document.removeEventListener(evt, this.onActivity);
    }
    document.removeEventListener('visibilitychange', this.onVisibility);
    this.channel?.close();
    this.channel = null;
    this.started = false;
  }

  /**
   * Called by the lock screen once the server has accepted the password (or
   * the new sign-in at the identity provider) and lifted the session's lock.
   * Nothing else may call it: it puts the page back in the document.
   */
  unlock(): void {
    this.persistLock(false);
    this.lockReported = false;
    if (this.reloadOnUnlock) {
      // The page behind the lock never loaded: it stays hidden until the reload.
      this.reloadOnUnlock = false;
      PAGE_RELOAD.reload();
      return;
    }
    this.locked.set(false);
    this.contentHidden.set(false);
    this.noteActivity(true);
  }

  /**
   * The lock screen is left by signing out (or the session ended): the lock
   * state is forgotten, and the page behind it stays out of the document
   * until the sign-in page replaces it.
   */
  release(): void {
    this.clearTimer();
    this.persistLock(false);
    this.lockReported = false;
    this.reloadOnUnlock = false;
    this.locked.set(false);
  }

  /** Programmatically lock the screen (e.g. from a "Lock" button). */
  lock(): void {
    this.triggerLock();
  }

  /**
   * The server said the session is locked (a refresh answer, or a refused
   * request): the lock screen is shown whatever this tab remembered.
   */
  lockFromServer(): void {
    // Already showing the lock screen: the page behind it keeps its state.
    if (!this.isBrowser || this.locked()) return;
    this.lockReported = true;
    this.reloadOnUnlock = true;
    this.zone.run(() => this.enterLock());
  }

  /** Tells the server about the lock again when the first attempt did not get through. */
  ensureServerLock(): void {
    if (this.locked() && !this.lockReported) this.reportLock();
  }

  ngOnDestroy(): void {
    this.stop();
  }

  // ── private ────────────────────────────────────────────────────────

  private noteActivity(share: boolean): void {
    // Don't reset if already locked
    if (this.locked() || !this.started) return;
    this.lastActivity = Date.now();
    this.arm(this.timeoutMs);
    if (share && this.lastActivity - this.lastShared >= IdleService.SHARE_EVERY_MS) {
      this.lastShared = this.lastActivity;
      this.tell('a');
    }
  }

  private arm(delayMs: number): void {
    this.clearTimer();
    this.idleTimer = setTimeout(() => this.onTimer(), Math.max(0, delayMs));
  }

  /** The timer fired: lock, unless this tab or a neighbour was active meanwhile. */
  private onTimer(): void {
    this.idleTimer = null;
    if (this.locked()) return;
    const idleFor = Date.now() - this.lastActivity;
    if (idleFor >= this.timeoutMs) {
      this.zone.run(() => this.triggerLock());
    } else {
      this.arm(this.timeoutMs - idleFor);
    }
  }

  private clearTimer(): void {
    if (this.idleTimer) {
      clearTimeout(this.idleTimer);
      this.idleTimer = null;
    }
  }

  private triggerLock(): void {
    this.enterLock();
    this.reportLock();
    this.tell('l');
  }

  private enterLock(): void {
    this.clearTimer();
    this.contentHidden.set(true);
    this.locked.set(true);
    this.persistLock(true);
  }

  private reportLock(): void {
    // Looked up here: the HTTP client's interceptors use this service.
    const http = this.injector.get(HttpClient, null);
    if (!http) return;
    this.lockReported = true;
    http.post('/auth/session/lock', {}).subscribe({
      error: () => (this.lockReported = false),
    });
  }

  private handleVisibility(): void {
    // Back in view (lid opened, tab selected): timers are throttled while
    // hidden, so the idle time is measured again here.
    if (document.hidden || this.locked() || !this.started) return;
    this.onTimer();
  }

  private openChannel(): void {
    if (typeof BroadcastChannel === 'undefined') return;
    try {
      this.channel = new BroadcastChannel(IdleService.CHANNEL);
      this.channel.onmessage = (event: MessageEvent<TabMessage>) => this.heard(event.data);
    } catch {
      this.channel = null;
    }
  }

  private tell(type: TabMessage['t']): void {
    try {
      this.channel?.postMessage({ b: tabBinding(), t: type } satisfies TabMessage);
    } catch {
      /* a closed channel: the tabs simply do not hear each other */
    }
  }

  /** Another tab of this browser: only one bound to the same account is listened to. */
  private heard(message: TabMessage | null): void {
    const mine = tabBinding();
    if (!message || !mine || message.b !== mine) return;
    if (message.t === 'a') {
      this.noteActivity(false);
    } else if (message.t === 'l' && !this.locked()) {
      // The session is locked on the server already: this tab only follows.
      this.lockReported = true;
      this.zone.run(() => this.enterLock());
    }
  }

  private persistLock(locked: boolean): void {
    try {
      if (locked) {
        sessionStorage.setItem(IdleService.IDLE_KEY, 'true');
        sessionStorage.setItem(IdleService.LOCK_TS_KEY, Date.now().toString());
      } else {
        sessionStorage.removeItem(IdleService.IDLE_KEY);
        sessionStorage.removeItem(IdleService.LOCK_TS_KEY);
      }
    } catch {
      /* storage not available */
    }
  }
}

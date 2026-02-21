import { Injectable, inject, NgZone, OnDestroy, PLATFORM_ID, signal } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';

/**
 * Tracks user activity (mouse, keyboard, scroll, touch, visibility change).
 * When the user is idle for longer than the configured timeout, the `locked`
 * signal becomes `true`.  The service also detects the Page Visibility API
 * ("visibilitychange") so closing a laptop lid or switching tabs starts the
 * idle countdown immediately.
 *
 * Usage:
 *   inject(IdleService) → call start() once on shell init, stop() on destroy.
 *   Read locked() signal to decide whether to show the lock screen.
 */
@Injectable({ providedIn: 'root' })
export class IdleService implements OnDestroy {
  private static readonly IDLE_KEY = 'hms_idle_locked';
  private static readonly LOCK_TS_KEY = 'hms_lock_ts';

  /** Default idle timeout: 10 minutes (in ms) */
  private static readonly DEFAULT_TIMEOUT_MS = 10 * 60 * 1000;

  /** Grace period after visibility returns before locking (5 sec) */
  private static readonly VISIBILITY_GRACE_MS = 5_000;

  private readonly zone = inject(NgZone);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  /** Reactive signal — `true` when screen should be locked */
  readonly locked = signal(false);

  private timeoutMs = IdleService.DEFAULT_TIMEOUT_MS;
  private idleTimer: ReturnType<typeof setTimeout> | null = null;
  private hiddenSince: number | null = null;
  private started = false;

  private readonly eventNames = [
    'mousemove',
    'mousedown',
    'keydown',
    'scroll',
    'touchstart',
    'click',
  ] as const;

  private readonly onActivity = (): void => this.resetTimer();
  private readonly onVisibility = (): void => this.handleVisibility();

  /** Start monitoring idle activity. Call once when the shell initialises. */
  start(timeoutMs?: number): void {
    if (!this.isBrowser || this.started) return;
    if (timeoutMs) this.timeoutMs = timeoutMs;
    this.started = true;

    // Restore locked state from sessionStorage (survives soft refresh)
    try {
      if (sessionStorage.getItem(IdleService.IDLE_KEY) === 'true') {
        this.locked.set(true);
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

    // Only start the idle timer if not already locked
    if (!this.locked()) {
      this.resetTimer();
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
    this.started = false;
  }

  /** Called by the lock-screen after successful password verification. */
  unlock(): void {
    this.locked.set(false);
    this.persistLock(false);
    this.hiddenSince = null;
    this.resetTimer();
  }

  /** Programmatically lock the screen (e.g. from a "Lock" button). */
  lock(): void {
    this.triggerLock();
  }

  ngOnDestroy(): void {
    this.stop();
  }

  // ── private ────────────────────────────────────────────────────────

  private resetTimer(): void {
    // Don't reset if already locked
    if (this.locked()) return;

    this.clearTimer();
    this.idleTimer = setTimeout(() => {
      this.zone.run(() => this.triggerLock());
    }, this.timeoutMs);
  }

  private clearTimer(): void {
    if (this.idleTimer) {
      clearTimeout(this.idleTimer);
      this.idleTimer = null;
    }
  }

  private triggerLock(): void {
    this.clearTimer();
    this.locked.set(true);
    this.persistLock(true);
  }

  private handleVisibility(): void {
    if (document.hidden) {
      // Page became hidden (lid closed, tab switch)
      this.hiddenSince = Date.now();
    } else if (this.hiddenSince) {
      // Page became visible again — if hidden longer than timeout, lock
      const elapsed = Date.now() - this.hiddenSince;
      if (elapsed >= this.timeoutMs) {
        this.zone.run(() => this.triggerLock());
      } else {
        // Restart timer with remaining time
        const remaining = this.timeoutMs - elapsed;
        this.clearTimer();
        this.idleTimer = setTimeout(() => {
          this.zone.run(() => this.triggerLock());
        }, remaining);
      }
      this.hiddenSince = null;
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

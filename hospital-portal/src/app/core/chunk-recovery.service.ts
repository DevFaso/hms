import { Injectable, InjectionToken, inject, signal } from '@angular/core';
import { NavigationEnd, NavigationError, Router, Routes } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

const GUARD = 'hms-chunk-reload';
const RELOAD_WINDOW_MS = 60_000;
interface ReloadAttempt {
  route: string;
  at: number;
}

export const CHUNK_RECOVERY_BROWSER = new InjectionToken('CHUNK_RECOVERY_BROWSER', {
  providedIn: 'root',
  factory: () => ({
    read: () => window.sessionStorage.getItem(GUARD),
    write: (value: string) => window.sessionStorage.setItem(GUARD, value),
    clear: () => window.sessionStorage.removeItem(GUARD),
    navigate: (url: string) => window.location.assign(url),
  }),
});

/** One automatic document navigation, then an explicit recovery UI. Never retries API writes. */
@Injectable({ providedIn: 'root' })
export class ChunkRecoveryService {
  private readonly browser = inject(CHUNK_RECOVERY_BROWSER);
  private readonly router = inject(Router);
  readonly failedUrl = signal<string | null>(null);

  constructor() {
    this.router.events.pipe(takeUntilDestroyed()).subscribe((event) => {
      if (!(event instanceof NavigationEnd)) return;
      try {
        const succeeded = this.routeKey(event.urlAfterRedirects);
        const remaining = this.attempts().filter((attempt) => attempt.route !== succeeded);
        if (remaining.length) this.browser.write(JSON.stringify(remaining));
        else this.browser.clear();
      } catch {
        /* Storage may be unavailable in privacy-restricted browsers. */
      }
      this.failedUrl.set(null);
    });
  }

  handle(error: NavigationError): void {
    const message = String((error.error as Error | undefined)?.message ?? error.error ?? '');
    if (
      !/Failed to fetch dynamically imported module|Importing a module script failed|ChunkLoadError|Loading chunk [\w-]+ failed|error loading dynamically imported module/i.test(
        message,
      )
    )
      return;
    // Router URLs are internal; reject protocol-relative or external destinations defensively.
    if (!error.url.startsWith('/') || error.url.startsWith('//') || error.url.includes('\\'))
      return;
    this.failedUrl.set(error.url);
    try {
      const route = this.routeKey(error.url);
      const attempts = this.attempts();
      if (attempts.some((attempt) => attempt.route === route)) return;
      this.browser.write(JSON.stringify([...attempts, { route, at: Date.now() }]));
    } catch {
      // Without a persistent guard, automatic navigation could create a reload loop.
      return;
    }
    this.browser.navigate(error.url);
  }

  /** Persist configured route patterns only: never actual patient IDs, query strings or URLs. */
  private routeKey(url: string): string {
    const segments = url.split(/[?#]/)[0].split('/').filter(Boolean);
    let best = 'unknown';
    let depth = -1;
    const visit = (routes: Routes, prefix: string[] = []): void => {
      for (const route of routes) {
        const pattern = [...prefix, ...(route.path ?? '').split('/').filter(Boolean)];
        if (pattern.includes('**')) continue;
        if (
          pattern.length <= segments.length &&
          pattern.every((part, i) => part.startsWith(':') || part === segments[i])
        ) {
          if (pattern.length > depth) {
            best = pattern.join('/');
            depth = pattern.length;
          }
          if (route.children) visit(route.children, pattern);
        }
      }
    };
    visit(this.router.config ?? []);
    return best;
  }

  private attempts(): ReloadAttempt[] {
    const raw = this.browser.read();
    if (!raw) return [];
    try {
      const value: unknown = JSON.parse(raw);
      if (!Array.isArray(value)) return [];
      const now = Date.now();
      return value.filter(
        (attempt): attempt is ReloadAttempt =>
          !!attempt &&
          typeof attempt.route === 'string' &&
          typeof attempt.at === 'number' &&
          Number.isFinite(attempt.at) &&
          attempt.at <= now &&
          now - attempt.at < RELOAD_WINDOW_MS,
      );
    } catch {
      return [];
    } // Migrate legacy '1', raw URLs and corrupt entries.
  }

  reload(): void {
    const url = this.failedUrl();
    if (url) this.browser.navigate(url);
  }
}

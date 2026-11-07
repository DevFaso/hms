import { Injectable, InjectionToken, inject, signal } from '@angular/core';
import { NavigationEnd, NavigationError, Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

const GUARD = 'hms-chunk-reload';

export const CHUNK_RECOVERY_BROWSER = new InjectionToken('CHUNK_RECOVERY_BROWSER', {
  providedIn: 'root',
  factory: () => ({
    read: () => window.sessionStorage.getItem(GUARD),
    write: (url: string) => window.sessionStorage.setItem(GUARD, url),
    clear: () => window.sessionStorage.removeItem(GUARD),
    navigate: (url: string) => window.location.assign(url),
  }),
});

/** One automatic document navigation, then an explicit recovery UI. Never retries API writes. */
@Injectable({ providedIn: 'root' })
export class ChunkRecoveryService {
  private readonly browser = inject(CHUNK_RECOVERY_BROWSER);
  readonly failedUrl = signal<string | null>(null);

  constructor() {
    inject(Router)
      .events.pipe(takeUntilDestroyed())
      .subscribe((event) => {
        if (!(event instanceof NavigationEnd)) return;
        try {
          // An unrelated successful route must not reset a failed route's reload budget.
          if ([event.url, event.urlAfterRedirects].includes(this.browser.read() ?? '')) {
            this.browser.clear();
          }
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
    if (!error.url.startsWith('/') || error.url.startsWith('//')) return;
    this.failedUrl.set(error.url);
    try {
      if (this.browser.read()) return;
      this.browser.write(error.url);
    } catch {
      // Without a persistent guard, automatic navigation could create a reload loop.
      return;
    }
    this.browser.navigate(error.url);
  }

  reload(): void {
    const url = this.failedUrl();
    if (url) this.browser.navigate(url);
  }
}

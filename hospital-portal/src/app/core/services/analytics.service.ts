import { inject, Injectable } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs/operators';
import { environment } from '../../../environments/environment';

declare global {
  interface Window {
    dataLayer?: unknown[];
    gtag?: (...args: unknown[]) => void;
  }
}

@Injectable({ providedIn: 'root' })
export class AnalyticsService {
  private readonly router = inject(Router);
  private loaded = false;

  /** True only when a real measurement ID is configured for this environment. */
  private get trackingId(): string | null {
    const id = environment.gaTrackingId;
    return id && /^G-[A-Z0-9]+$/.test(id) && !id.includes('XXXX') ? id : null;
  }

  init(): void {
    const id = this.trackingId;
    if (!id || this.loaded) return;
    this.loaded = true;

    // Bootstrap gtag.js at runtime so the measurement ID comes from the
    // environment instead of being baked into index.html.
    window.dataLayer = window.dataLayer || [];
    window.gtag = function gtag() {
      // GA processes the Arguments object itself — pushing a plain array breaks it.
      // eslint-disable-next-line prefer-rest-params
      window.dataLayer?.push(arguments);
    };
    const script = document.createElement('script');
    script.async = true;
    script.src = `https://www.googletagmanager.com/gtag/js?id=${id}`;
    document.head.appendChild(script);
    window.gtag('js', new Date());
    window.gtag('config', id);

    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe((event) => {
        window.gtag?.('config', id, { page_path: event.urlAfterRedirects });
      });
  }

  event(action: string, category: string, label?: string, value?: number): void {
    if (!this.trackingId || !this.loaded) return;
    window.gtag?.('event', action, {
      event_category: category,
      event_label: label,
      value: value,
    });
  }
}

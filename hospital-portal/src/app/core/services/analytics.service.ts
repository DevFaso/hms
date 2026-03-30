import { inject, Injectable } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs/operators';
import { environment } from '../../../environments/environment';

declare let gtag: (...args: unknown[]) => void;

@Injectable({ providedIn: 'root' })
export class AnalyticsService {
  private readonly router = inject(Router);

  init(): void {
    if (!environment.gaTrackingId) return;

    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe((event) => {
        gtag('config', environment.gaTrackingId, { page_path: event.urlAfterRedirects });
      });
  }

  event(action: string, category: string, label?: string, value?: number): void {
    if (!environment.gaTrackingId) return;
    gtag('event', action, {
      event_category: category,
      event_label: label,
      value: value,
    });
  }
}

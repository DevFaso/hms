import { Component, ChangeDetectionStrategy, inject, signal, DestroyRef } from '@angular/core';
import {
  Router,
  NavigationStart,
  NavigationEnd,
  NavigationCancel,
  NavigationError,
  NavigationSkipped,
} from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslateModule } from '@ngx-translate/core';

/** Keeps the initial router outlet informative while lazy bundles or session guards are pending. */
@Component({
  selector: 'app-route-loading',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (loading()) {
    <section
      class="route-loading"
      [class.initial]="initial()"
      role="status"
      aria-live="polite"
      data-testid="route-loading"
    >
      <strong>e-Keneya</strong>
      <div class="skeleton" aria-hidden="true"></div>
      <p>{{ (slow() ? 'LOADING_SHELL.SLOW' : 'COMMON.LOADING') | translate }}</p>
    </section>
  }`,
  styles: [
    `
      .route-loading {
        position: fixed;
        top: 4rem;
        right: 1rem;
        z-index: 80;
        max-width: min(30rem, calc(100vw - 2rem));
        padding: 1rem;
        background: var(--color-surface, white);
        color: var(--color-text, #1e293b);
        border: 1px solid var(--color-border, #cbd5e1);
        border-radius: 0.75rem;
      }
      .route-loading.initial {
        position: relative;
        top: auto;
        right: auto;
        max-width: none;
        min-height: 80vh;
        display: grid;
        align-content: center;
        justify-items: center;
        text-align: center;
        border: 0;
      }
      .skeleton {
        width: min(18rem, 75vw);
        height: 0.75rem;
        margin-top: 1rem;
        border-radius: 0.5rem;
        background: var(--color-surface-alt, #e2e8f0);
      }
    `,
  ],
})
export class RouteLoadingComponent {
  private readonly router = inject(Router);
  readonly loading = signal(!this.router.navigated);
  readonly initial = signal(!this.router.navigated);
  readonly slow = signal(false);
  private timer?: ReturnType<typeof setTimeout>;
  private navigationId = 0;
  constructor() {
    const destroy = inject(DestroyRef);
    destroy.onDestroy(() => clearTimeout(this.timer));
    if (this.loading()) this.startTimer();
    this.router.events.pipe(takeUntilDestroyed()).subscribe((event) => {
      if (event instanceof NavigationStart) {
        this.navigationId = event.id;
        this.loading.set(true);
        this.slow.set(false);
        this.startTimer();
      } else if (
        (event instanceof NavigationEnd ||
          event instanceof NavigationCancel ||
          event instanceof NavigationError ||
          event instanceof NavigationSkipped) &&
        event.id >= this.navigationId
      ) {
        clearTimeout(this.timer);
        this.loading.set(false);
        this.slow.set(false);
        if (event instanceof NavigationEnd) this.initial.set(false);
      }
    });
  }
  private startTimer(): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.slow.set(true), 20000);
  }
}

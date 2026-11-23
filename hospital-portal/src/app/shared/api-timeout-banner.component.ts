import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { ApiTimeoutService } from '../core/api-timeout.service';

@Component({
  selector: 'app-api-timeout-banner',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (timeout.failure()) {
      <section class="timeout-banner" role="alert" data-testid="api-timeout-banner">
        <strong>{{ 'API_TIMEOUT.TITLE' | translate }}</strong>
        <p>{{ 'API_TIMEOUT.MESSAGE' | translate }}</p>
        @if (timeout.failure() === 'write') {
          <p data-testid="api-timeout-write">{{ 'API_TIMEOUT.WRITE_UNKNOWN' | translate }}</p>
        }
        <p>{{ 'API_TIMEOUT.KEEP_INPUT' | translate }}</p>
        <button type="button" (click)="reload()" data-testid="api-timeout-retry">
          {{ 'API_TIMEOUT.RELOAD' | translate }}
        </button>
        <button type="button" (click)="timeout.failure.set(null)">
          {{ 'COMMON.CLOSE' | translate }}
        </button>
      </section>
    }
  `,
  styles: [
    `
      .timeout-banner {
        position: fixed;
        z-index: 1050;
        top: 1rem;
        left: 1rem;
        right: 1rem;
        max-width: 48rem;
        max-height: 60dvh;
        overflow-y: auto;
        margin: 0 auto;
        padding: 1rem;
        border: 1px solid var(--border, #cbd5e1);
        border-radius: 0.5rem;
        background: var(--surface, #fff);
        color: var(--text-primary, #0f172a);
        overflow-wrap: anywhere;
      }
      p {
        margin: 0.5rem 0;
      }
      button {
        min-height: 44px;
        margin: 0.25rem 0.5rem 0.25rem 0;
        padding: 0.5rem 1rem;
        border: 1px solid var(--border, #cbd5e1);
        border-radius: 0.5rem;
        background: var(--surface, #fff);
        color: var(--text-primary, #0f172a);
        font: inherit;
        cursor: pointer;
      }
      button:first-of-type {
        background: var(--primary, #0e7c6b);
        border-color: var(--primary, #0e7c6b);
        color: white;
      }
      @media print {
        :host {
          display: none;
        }
      }
    `,
  ],
})
export class ApiTimeoutBannerComponent {
  readonly timeout = inject(ApiTimeoutService);
  private readonly document = inject(DOCUMENT);

  /** Explicit reload only: never replays a stored request or a form submit. */
  reload(): void {
    this.document.defaultView?.location.reload();
  }
}

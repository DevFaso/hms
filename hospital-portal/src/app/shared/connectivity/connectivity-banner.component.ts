import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { TranslateModule } from '@ngx-translate/core';

import { ConnectivityService } from '../../core/connectivity.service';
import { ChunkRecoveryService } from '../../core/chunk-recovery.service';
import { environment } from '../../../environments/environment';

/** How long "connection restored" stays on screen. */
export const RESTORED_NOTICE_MS = 4000;

/**
 * QA D2352 (r63): a dropped connection is said, in the reader's language,
 * with a retry; nothing on the page is reloaded, so whatever was typed stays
 * where it was. A page that could not open is AppComponent's chunk-recovery
 * notice instead (it says the same and retries that page), so this banner
 * stays out of its way.
 */
@Component({
  selector: 'app-connectivity-banner',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (shown()) {
      <div
        class="connectivity-banner"
        role="alert"
        data-testid="connectivity-banner"
        [attr.data-state]="connectivity.state()"
      >
        <p class="cb-text">
          <strong>{{
            (connectivity.state() === 'offline'
              ? 'CONNECTIVITY.OFFLINE_TITLE'
              : 'CONNECTIVITY.UNREACHABLE_TITLE'
            ) | translate
          }}</strong>
          {{ 'CONNECTIVITY.KEEP_INPUT' | translate }}
          @if (stillDown()) {
            <span class="cb-still" data-testid="connectivity-still-down">{{
              'CONNECTIVITY.STILL_DOWN' | translate
            }}</span>
          }
        </p>
        <button
          type="button"
          class="cb-retry"
          data-testid="connectivity-retry"
          [disabled]="retrying()"
          (click)="retry()"
        >
          {{ (retrying() ? 'CONNECTIVITY.RETRYING' : 'CONNECTIVITY.RETRY') | translate }}
        </button>
      </div>
    } @else if (connectivity.restored()) {
      <div
        class="connectivity-banner cb-restored"
        role="status"
        data-testid="connectivity-restored"
      >
        <p class="cb-text">{{ 'CONNECTIVITY.RESTORED' | translate }}</p>
      </div>
    }
  `,
  styles: [
    `
      .connectivity-banner {
        position: fixed;
        left: 16px;
        right: 16px;
        bottom: 16px;
        z-index: 950;
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 10px 14px;
        max-width: 720px;
        margin: 0 auto;
        padding: 12px 14px;
        border: 1px solid #e3bd7b;
        border-radius: 10px;
        background: #fff9ed;
        color: #593a10;
        box-shadow: 0 6px 18px rgba(15, 23, 42, 0.18);
      }
      .cb-restored {
        border-color: #86c5a4;
        background: #ecfdf3;
        color: #14532d;
      }
      .cb-text {
        flex: 1 1 220px;
        min-width: 0;
        margin: 0;
        line-height: 1.45;
      }
      .cb-text strong {
        display: block;
      }
      .cb-still {
        display: block;
        margin-top: 4px;
        font-weight: 600;
      }
      .cb-retry {
        min-height: 44px;
        padding: 10px 16px;
        border: 0;
        border-radius: 7px;
        background: #0b7061;
        color: #fff;
        font: inherit;
        font-weight: 600;
        cursor: pointer;
      }
      .cb-retry:disabled {
        opacity: 0.7;
        cursor: progress;
      }
      .cb-retry:focus-visible {
        outline: 3px solid #087269;
        outline-offset: 3px;
      }
      @media print {
        .connectivity-banner {
          display: none;
        }
      }
    `,
  ],
})
export class ConnectivityBannerComponent {
  readonly connectivity = inject(ConnectivityService);
  private readonly chunkRecovery = inject(ChunkRecoveryService);
  private readonly http = inject(HttpClient);

  readonly retrying = signal(false);
  /** The last retry found the connection still down. */
  readonly stillDown = signal(false);
  readonly shown = computed(
    () => this.connectivity.state() !== 'online' && !this.chunkRecovery.failedUrl(),
  );

  constructor() {
    effect((onCleanup) => {
      if (!this.connectivity.restored()) return;
      this.stillDown.set(false);
      const timer = setTimeout(() => this.connectivity.restored.set(false), RESTORED_NOTICE_MS);
      onCleanup(() => clearTimeout(timer));
    });
  }

  /** Ask the server once; the interceptor turns the answer into the new state. */
  retry(): void {
    this.retrying.set(true);
    this.http
      .get<void>(`${environment.apiBase}/auth/csrf-token`, { observe: 'response' })
      .subscribe({
        next: () => this.retrying.set(false),
        error: () => {
          this.retrying.set(false);
          this.stillDown.set(this.connectivity.state() !== 'online');
        },
      });
  }
}

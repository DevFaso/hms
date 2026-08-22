import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';

import { DowntimeService } from '../services/downtime.service';

/**
 * Persistent read-only banner (P3 #23a). Not dismissible by design — the
 * mode ends when a super admin turns it off, not when a user closes a
 * banner and then wonders why every save fails.
 */
@Component({
  selector: 'app-downtime-banner',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  template: `
    @if (visible()) {
      <div class="downtime-banner" role="alert" data-testid="downtime-banner">
        <span class="material-symbols-outlined">construction</span>
        <span class="banner-text">
          <strong>{{ 'DOWNTIME.BANNER_TITLE' | translate }}</strong>
          {{ message() || ('DOWNTIME.BANNER_DEFAULT' | translate) }}
        </span>
      </div>
    }
  `,
  styles: `
    .downtime-banner {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.5rem 1rem;
      background: #fef3c7;
      color: #92400e;
      border-bottom: 1px solid #fcd34d;
      font-size: 0.9rem;

      .banner-text strong {
        margin-right: 0.35rem;
      }
    }
  `,
})
export class DowntimeBannerComponent {
  private readonly downtime = inject(DowntimeService);

  readonly visible = computed(() => this.downtime.status()?.readOnly === true);
  readonly message = computed(() => this.downtime.status()?.message ?? null);
}

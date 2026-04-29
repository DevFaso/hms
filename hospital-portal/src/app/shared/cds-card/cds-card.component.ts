import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';

import { CdsCard, CdsIndicator } from './cds-card.model';

/**
 * Renders a stack of CDS Hooks cards returned by the HMS backend rule
 * engine. The component is presentation-only: it does not call the API,
 * does not block submission, and never mutates its inputs. Hosts decide
 * what to do with critical advisories (e.g. require a forceOverride
 * checkbox before re-submitting).
 *
 * <p>Indicator → CSS class mapping:
 * <ul>
 *   <li>{@code critical} → red banner, "alert" iconography</li>
 *   <li>{@code warning}  → amber banner</li>
 *   <li>{@code info}     → neutral banner</li>
 * </ul>
 */
@Component({
  selector: 'app-cds-card-list',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ul *ngIf="hasCards()" class="cds-card-list" data-testid="cds-card-list">
      <li
        *ngFor="let card of cards(); trackBy: trackByCard"
        class="cds-card"
        [class.cds-card--critical]="card.indicator === 'critical'"
        [class.cds-card--warning]="card.indicator === 'warning'"
        [class.cds-card--info]="card.indicator === 'info'"
        [attr.data-indicator]="card.indicator"
      >
        <div class="cds-card__header">
          <span class="cds-card__indicator">{{ indicatorLabel(card.indicator) | translate }}</span>
          <span class="cds-card__source" *ngIf="card.source?.label">{{ card.source.label }}</span>
        </div>
        <p class="cds-card__summary">{{ card.summary }}</p>
        <p *ngIf="card.detail" class="cds-card__detail">{{ card.detail }}</p>
      </li>
    </ul>
  `,
  styleUrl: './cds-card.component.scss',
})
export class CdsCardListComponent {
  readonly cards = input<CdsCard[]>([]);

  protected readonly hasCards = computed(() => (this.cards() ?? []).length > 0);

  protected indicatorLabel(indicator: CdsIndicator): string {
    return `CDS.INDICATOR.${indicator.toUpperCase()}`;
  }

  /**
   * Public so unit tests can assert the keying contract directly.
   * `uuid` wins when present (unique per backend response); summary
   * is the fallback so two cards with the same uuid-less summary
   * still trackBy stably across re-renders.
   */
  trackByCard(_index: number, card: CdsCard): string {
    return card.uuid ?? card.summary;
  }
}

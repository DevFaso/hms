import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * QA D2791 (r65): THE page header. The portal had five header layouts and 20
 * style combinations because every page wrote its own; `.page-header` was
 * redefined in 40 stylesheets and `.page-title` in 18.
 *
 *   <app-page-header
 *     [title]="'NOTIFICATIONS.SETTINGS_TITLE' | translate"
 *     [subtitle]="'NOTIFICATIONS.SETTINGS_DESC' | translate"
 *     icon="tune"
 *     backLink="/notifications"
 *     [backLabel]="'NOTIFICATIONS.SETTINGS_BACK' | translate"
 *   >
 *     <button class="btn-primary">…</button>   <!-- the page's actions -->
 *   </app-page-header>
 *
 * One `h1` (its size and weight come from the global layer, styles.scss), an
 * optional eyebrow above it and subtitle below it, a way back, and the page's
 * actions on the right, wrapping under the title on a narrow screen. The
 * global h1 rule already gives every existing page the one title style; a new
 * page, and any page that is touched, uses this component instead of writing
 * a header.
 */
@Component({
  selector: 'app-page-header',
  standalone: true,
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="aph">
      @if (backLink(); as back) {
        <a class="aph-back" [routerLink]="back">
          <span class="material-symbols-outlined" aria-hidden="true">arrow_back</span>
          {{ backLabel() }}
        </a>
      }
      <div class="aph-row">
        <div class="aph-text">
          @if (eyebrow(); as over) {
            <p class="aph-eyebrow">{{ over }}</p>
          }
          <h1 class="aph-title">
            @if (icon(); as glyph) {
              <span class="material-symbols-outlined" aria-hidden="true">{{ glyph }}</span>
            }
            {{ title() }}
          </h1>
          @if (subtitle(); as under) {
            <p class="aph-subtitle">{{ under }}</p>
          }
        </div>
        <div class="aph-actions page-actions"><ng-content /></div>
      </div>
    </header>
  `,
  styles: `
    :host {
      display: block;
      margin-bottom: 1.5rem;
    }
    .aph-back {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      margin-bottom: 0.5rem;
      color: var(--primary);
      font-size: 0.875rem;
      font-weight: 600;
      text-decoration: none;
    }
    .aph-back:hover {
      text-decoration: underline;
    }
    .aph-back .material-symbols-outlined {
      font-size: 1.125rem;
    }
    .aph-row {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      justify-content: space-between;
      gap: 0.75rem 1rem;
    }
    .aph-text {
      flex: 1 1 16rem;
      min-width: 0;
    }
    .aph-eyebrow {
      margin: 0 0 0.25rem;
      color: var(--primary-deep);
      font-size: 0.75rem;
      font-weight: 700;
      letter-spacing: 0.06em;
      text-transform: uppercase;
    }
    .aph-title {
      margin: 0;
      color: var(--text-primary);
      overflow-wrap: anywhere;
    }
    .aph-title .material-symbols-outlined {
      margin-right: 0.375rem;
      color: var(--primary);
    }
    .aph-subtitle {
      margin: 0.25rem 0 0;
      color: var(--text-secondary);
      font-size: 0.875rem;
    }
    .aph-actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
    }
    .aph-actions:empty {
      display: none;
    }
  `,
})
export class PageHeaderComponent {
  /** The page's title, already in the reader's language. The one h1 of the page. */
  readonly title = input.required<string>();
  /** One line under the title saying what the page is for. */
  readonly subtitle = input<string>();
  /** A short word above the title naming the workspace (« Espace pharmacie »). */
  readonly eyebrow = input<string>();
  /** A Material symbol shown before the title (decorative, hidden from screen readers). */
  readonly icon = input<string>();
  /** Where « back » leads; no link when absent. */
  readonly backLink = input<string | readonly unknown[]>();
  /** The visible text of the way back. */
  readonly backLabel = input<string>('');
}

import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

/** The four insurer-claims screens, as tabs above each of them. */
export type ClaimsScreen = 'shares' | 'batches' | 'payments' | 'receivables';

/**
 * Insurer claims PRs 2 to 4: the way from one claims screen to the next (insurer shares, bordereaux, insurer
 * payments, receivables), the same on each.
 */
@Component({
  selector: 'app-claims-nav',
  standalone: true,
  imports: [RouterLink, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="claims-nav" [attr.aria-label]="'INSURER_CLAIMS.NAV.LABEL' | translate">
      @for (tab of tabs; track tab.screen) {
        <a
          class="claims-tab"
          [class.claims-tab-on]="current() === tab.screen"
          [attr.aria-current]="current() === tab.screen ? 'page' : null"
          [attr.data-testid]="'claims-nav-' + tab.screen"
          [routerLink]="tab.route"
          >{{ tab.label | translate }}</a
        >
      }
    </nav>
  `,
  styleUrl: './insurer-claims.scss',
})
export class ClaimsNavComponent {
  readonly current = input.required<ClaimsScreen>();

  readonly tabs: { screen: ClaimsScreen; route: string; label: string }[] = [
    { screen: 'shares', route: '/billing/parts-assureurs', label: 'INSURER_CLAIMS.NAV.SHARES' },
    { screen: 'batches', route: '/billing/bordereaux', label: 'INSURER_CLAIMS.NAV.BATCHES' },
    {
      screen: 'payments',
      route: '/billing/reglements-assureurs',
      label: 'INSURER_CLAIMS.NAV.PAYMENTS',
    },
    {
      screen: 'receivables',
      route: '/billing/creances-assureurs',
      label: 'INSURER_CLAIMS.NAV.RECEIVABLES',
    },
  ];
}

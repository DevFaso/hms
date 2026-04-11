import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { InBasketPanelComponent } from '../dashboard/in-basket-panel/in-basket-panel';

@Component({
  selector: 'app-in-basket-page',
  standalone: true,
  imports: [TranslateModule, InBasketPanelComponent],
  template: `
    <div class="in-basket-page">
      <header class="page-header">
        <h1>{{ 'inBasket.pageTitle' | translate }}</h1>
        <p class="page-subtitle">{{ 'inBasket.pageSubtitle' | translate }}</p>
      </header>
      <app-in-basket-panel />
    </div>
  `,
  styles: `
    .in-basket-page {
      max-width: 960px;
      margin: 0 auto;
      padding: 24px 16px;
    }
    .page-header {
      margin-bottom: 20px;
    }
    .page-header h1 {
      font-size: 1.5rem;
      font-weight: 600;
      color: #1e293b;
      margin: 0 0 4px;
    }
    .page-subtitle {
      font-size: 0.875rem;
      color: #64748b;
      margin: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InBasketPageComponent {}

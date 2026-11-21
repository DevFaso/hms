import { Component, ChangeDetectionStrategy } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

/**
 * QA D2802 / G1 (r65): a mistyped or stale link landed on the dashboard with
 * no message (the catch-all route sent every unknown address to /login, which
 * bounced a signed-in user to the dashboard). An unknown address now opens
 * this page inside the shell, in the reader's language, styled like the 403
 * page: the address was wrong, and here is the way back.
 */
@Component({
  selector: 'app-error-404',
  standalone: true,
  imports: [RouterLink, TranslateModule],
  template: `
    <div class="error-page">
      <span class="material-symbols-outlined error-icon" aria-hidden="true">search_off</span>
      <p class="error-code" aria-hidden="true">404</p>
      <h1>{{ 'ERROR_404.TITLE' | translate }}</h1>
      <p class="error-body">{{ 'ERROR_404.BODY' | translate }}</p>
      <a routerLink="/dashboard" class="btn-primary">
        <span class="material-symbols-outlined" aria-hidden="true">arrow_back</span>
        {{ 'ERROR_404.BACK' | translate }}
      </a>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .error-page {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      min-height: 60vh;
      text-align: center;
      padding: 2rem;
    }
    .error-icon {
      font-size: 64px;
      color: var(--warning-text);
      margin-bottom: 1rem;
    }
    .error-code {
      font-size: 4rem;
      font-weight: 800;
      line-height: 1;
      color: var(--text-primary);
      margin: 0 0 0.5rem;
    }
    h1 {
      color: var(--text-primary);
      margin: 0 0 0.5rem;
    }
    .error-body {
      max-width: 34rem;
      color: var(--text-secondary);
      margin: 0 0 2rem;
    }
  `,
})
export class Error404Component {}

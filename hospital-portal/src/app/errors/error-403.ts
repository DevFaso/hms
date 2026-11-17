import { Component, ChangeDetectionStrategy, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { RoleLabelPipe } from '../shared/pipes/role-label.pipe';

@Component({
  selector: 'app-error-403',
  standalone: true,
  imports: [RouterLink, TranslateModule, RoleLabelPipe],
  template: `
    <div class="error-page">
      <span class="material-symbols-outlined error-icon" aria-hidden="true">lock</span>
      <h1>403</h1>
      <h2>{{ 'ERROR_403.TITLE' | translate }}</h2>
      <p>{{ 'ERROR_403.BODY' | translate }}</p>
      @if (betterRole; as better) {
        <p class="role-hint" role="note">
          {{
            'ERROR_403.ROLE_HINT'
              | translate: { active: activeRole | roleLabel, better: better | roleLabel }
          }}
        </p>
        <button type="button" class="relogin-btn" (click)="signInAgain()">
          {{ 'ERROR_403.SIGN_IN_AGAIN' | translate }}
        </button>
      }
      <a routerLink="/dashboard" class="back-link">
        <span class="material-symbols-outlined" aria-hidden="true">arrow_back</span>
        {{ 'ERROR_403.BACK' | translate }}
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
      color: #ef4444;
      margin-bottom: 1rem;
    }
    h1 {
      font-size: 4rem;
      font-weight: 800;
      color: #1e293b;
      margin: 0;
    }
    h2 {
      font-size: 1.25rem;
      color: #475569;
      margin: 0.5rem 0;
    }
    p {
      color: #64748b;
      margin: 0 0 2rem;
    }
    .back-link {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.625rem 1.25rem;
      background: #0e7c6b;
      color: #fff;
      border-radius: 8px;
      text-decoration: none;
      font-weight: 500;
    }
    .back-link:hover {
      background: #0a5f52;
    }
    .role-hint {
      max-width: 36rem;
      color: #1e293b;
      background: var(--primary-soft);
      border-radius: 8px;
      padding: 0.75rem 1rem;
      margin: 0 0 1rem;
    }
    .relogin-btn {
      margin-bottom: 1rem;
      padding: 0.625rem 1.25rem;
      border: 1px solid var(--primary);
      border-radius: 8px;
      background: transparent;
      color: var(--primary-dark);
      font-weight: 500;
      cursor: pointer;
    }
  `,
})
export class Error403Component {
  private readonly roleContext = inject(RoleContextService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly activeRole = this.roleContext.activeRole ?? '';
  /** Another role this account holds that would open the refused page, if any. */
  readonly betterRole = this.roleContext.roleThatWouldAllow();

  signInAgain(): void {
    this.auth.logout();
    void this.router.navigateByUrl('/login');
  }
}

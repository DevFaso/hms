import { Component, inject, OnInit } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { AuthService } from './auth/auth.service';
import { RoleContextService } from './core/role-context.service';
import { SessionScopeService } from './core/session-scope.service';
import { AnalyticsService } from './core/services/analytics.service';
import { environment } from '../environments/environment';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, TranslateModule],
  template: `<router-outlet />`,
  styles: [
    `
      :host {
        display: block;
        height: 100%;
      }
    `,
  ],
})
export class AppComponent implements OnInit {
  title = 'hospital-portal';

  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly sessionScope = inject(SessionScopeService);
  private readonly http = inject(HttpClient);
  private readonly translate = inject(TranslateService);
  private readonly analytics = inject(AnalyticsService);

  ngOnInit(): void {
    this.translate.setDefaultLang('fr');
    this.translate.use(localStorage.getItem('lang') || 'fr');
    this.analytics.init();

    // Bootstrap the XSRF-TOKEN cookie from the server so that the custom
    // CSRF interceptor can attach X-XSRF-TOKEN on subsequent mutating requests.
    // This is a fire-and-forget GET; errors are intentionally swallowed because
    // the app can function (degraded CSRF protection) even if the backend is
    // temporarily unreachable during bootstrap.
    this.http
      .get<void>(`${environment.apiBase}/auth/csrf-token`, { observe: 'response' })
      .subscribe({
        error: (_err: unknown) => {
          /* intentionally ignored — degraded CSRF protection on network failure */
        },
      });

    // Re-hydrate the session on every app bootstrap (hard refresh, direct
    // URL, tab re-open). E9 #55b: the roles come from the token synchronously
    // so the route guards can run at once; the hospital scope comes from the
    // live session. The stored profile — the last thing the server said —
    // fills the gap until the server replies, and stays if it cannot.
    const token = this.auth.getToken();
    if (token && !this.auth.isExpired(token)) {
      this.roleContext.setRoles(this.auth.getRoles());
      this.sessionScope.applyStoredProfile();
      this.sessionScope.hydrate().subscribe();
    }
  }
}

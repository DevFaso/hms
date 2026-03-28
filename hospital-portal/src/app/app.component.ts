import { Component, inject, OnInit } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { AuthService } from './auth/auth.service';
import { RoleContextService } from './core/role-context.service';
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
  private readonly http = inject(HttpClient);
  private readonly translate = inject(TranslateService);

  ngOnInit(): void {
    this.translate.setDefaultLang('fr');
    this.translate.use(localStorage.getItem('lang') || 'fr');

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

    // Re-hydrate role context from the stored JWT on every app bootstrap
    // (covers hard refresh, direct URL navigation, and tab re-open).
    const token = this.auth.getToken();
    if (token && !this.auth.isExpired(token)) {
      const roles = this.auth.getRoles();
      this.roleContext.setRoles(roles);

      let permittedIds = this.auth.getPermittedHospitalIds();

      // Fallback: if JWT claims don't contain hospital IDs (e.g. assignment was
      // added after the JWT was issued), try the stored user profile which was
      // populated from the login response body (authoritative, DB-fresh data).
      if (permittedIds.length === 0) {
        const profile = this.auth.getUserProfile();
        if (profile?.hospitalIds?.length) {
          permittedIds = profile.hospitalIds;
        }
      }

      this.roleContext.setPermittedHospitalIds(permittedIds);

      // Non-admin staff always get exactly one permitted hospital (their primary).
      // Admin roles may have multiple; for single-hospital admins we still pre-lock.
      if (permittedIds.length === 1) {
        this.roleContext.activeHospitalId = permittedIds[0];
      } else if (permittedIds.length > 1) {
        // Multi-hospital admin: pre-select the primary from stored profile
        const profile = this.auth.getUserProfile();
        if (profile?.primaryHospitalId) {
          this.roleContext.activeHospitalId = profile.primaryHospitalId;
        }
      }
    }
  }
}

import { ApplicationConfig, provideAppInitializer, inject } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { provideOAuthClient } from 'angular-oauth2-oidc';

import { routes } from './app.routes';
import { OidcAuthService } from './auth/oidc-auth.service';
import { apiPrefixInterceptor } from './interceptors/auth.interceptor';
import { csrfInterceptor } from './interceptors/csrf.interceptor';
import { errorInterceptor } from './interceptors/error.interceptor';
import { offlineDispenseInterceptor } from './interceptors/offline-dispense.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes),
    provideHttpClient(
      withInterceptors([
        apiPrefixInterceptor,
        csrfInterceptor,
        // Roadmap row 4 / T-68 — must run BEFORE errorInterceptor so a queued
        // (synthetic 202) response is not treated as a real error and routed
        // through the auth-refresh / toast path. Order is the chain order.
        offlineDispenseInterceptor,
        errorInterceptor,
      ]),
    ),
    provideTranslateService({
      defaultLanguage: 'fr',
      fallbackLang: 'en',
    }),
    provideTranslateHttpLoader({ prefix: './assets/i18n/', suffix: '.json' }),
    // KC-2b: Keycloak OIDC PKCE login. Bootstraps the OAuth client and
    // attempts to complete a redirect-back code exchange on app start.
    // Becomes a no-op when `environment.oidc.enabled` is false, so legacy
    // form-based login keeps working untouched during the rollout.
    provideOAuthClient(),
    provideAppInitializer(() => inject(OidcAuthService).initialize()),
  ],
};

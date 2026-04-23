import { isPlatformBrowser } from '@angular/common';
import { Injectable, PLATFORM_ID, inject, signal } from '@angular/core';
import { AuthConfig, OAuthService } from 'angular-oauth2-oidc';

import { environment } from '../../environments/environment';
import { AuthService, LoginUserProfile } from './auth.service';

/**
 * KC-2b — Keycloak / OIDC Authorization Code + PKCE driver for the portal.
 *
 * <p>Design intent: this service is purely additive. After Keycloak issues
 * an access token we copy it into the existing {@link AuthService} storage
 * so the rest of the portal (HTTP interceptor, role guards, profile cache,
 * idle lock) keeps working unchanged. The legacy form-based `/auth/login`
 * flow continues to function during the rollout window, gated by
 * `environment.oidc.enabled`.
 *
 * <p>Lifecycle:
 *  - {@link initialize} is called once at app bootstrap.
 *  - On the post-login redirect, {@link initialize} resolves the
 *    `?code=...&state=...` query, exchanges it for tokens, hydrates
 *    {@link AuthService}, and clears the URL.
 *  - If a refresh token is present (offline_access scope), silent refresh
 *    is scheduled automatically by `OAuthService`.
 */
@Injectable({ providedIn: 'root' })
export class OidcAuthService {
  private readonly oauth = inject(OAuthService);
  private readonly auth = inject(AuthService);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  /** True once the OAuth library has finished its initial discovery + login attempt. */
  readonly ready = signal(false);

  /** True when an OIDC session is currently active (valid access token). */
  readonly authenticated = signal(false);

  isEnabled(): boolean {
    return !!environment.oidc?.enabled;
  }

  /**
   * Bootstrap the OAuth client. Safe to call when OIDC is disabled — it
   * becomes a no-op that resolves immediately.
   */
  async initialize(): Promise<void> {
    if (!this.isBrowser || !this.isEnabled()) {
      this.ready.set(true);
      return;
    }

    const cfg = environment.oidc;
    const authConfig: AuthConfig = {
      issuer: cfg.issuer,
      clientId: cfg.clientId,
      redirectUri: cfg.redirectUri,
      postLogoutRedirectUri: cfg.postLogoutRedirectUri,
      responseType: 'code',
      scope: cfg.scope,
      // PKCE is implicit when using Authorization Code with a public client.
      requireHttps: cfg.issuer.startsWith('https://'),
      showDebugInformation: !environment.production,
      // Use the access token's aud claim verbatim against our backend client ID.
      // Keycloak's `aud` for this flow is `hms-portal`; backend resource server
      // validates audience server-side via NimbusJwtDecoder.
      strictDiscoveryDocumentValidation: true,
      // Keycloak rotates refresh tokens by default. Honour the rotation.
      useSilentRefresh: false,
    };

    this.oauth.configure(authConfig);

    try {
      await this.oauth.loadDiscoveryDocumentAndTryLogin();
    } catch (error) {
      // Discovery failure is recoverable — legacy login still works.
      console.warn('[OidcAuthService] discovery / token exchange failed', error);
      this.ready.set(true);
      return;
    }

    if (this.oauth.hasValidAccessToken()) {
      this.hydrateLegacyAuthFromOidc();
      this.oauth.setupAutomaticSilentRefresh();
      this.authenticated.set(true);
    }

    this.ready.set(true);
  }

  /** Kick off Authorization Code + PKCE — browser navigates to Keycloak. */
  login(): void {
    if (!this.isEnabled()) return;
    this.oauth.initCodeFlow();
  }

  /**
   * End the Keycloak session AND clear local state.
   * Falls through to the legacy {@link AuthService.logout} so any remaining
   * legacy session data is also wiped.
   */
  logout(): void {
    this.auth.logout();
    if (!this.isEnabled()) return;
    if (this.oauth.hasValidIdToken() || this.oauth.hasValidAccessToken()) {
      this.oauth.logOut();
    }
    this.authenticated.set(false);
  }

  /**
   * Copies the OIDC access token + identity claims into {@link AuthService}
   * so the existing interceptor + guards continue to work without change.
   */
  private hydrateLegacyAuthFromOidc(): void {
    const accessToken = this.oauth.getAccessToken();
    if (!accessToken) return;

    this.auth.setToken(accessToken, /* remember */ true);

    const claims = this.oauth.getIdentityClaims() as Record<string, unknown> | null;
    if (!claims) return;

    const realmAccess = claims['realm_access'] as { roles?: unknown } | undefined;
    const realmRolesRaw = Array.isArray(realmAccess?.roles) ? realmAccess.roles : [];
    const roles = realmRolesRaw
      .filter((r): r is string => typeof r === 'string')
      .map((r) => (r.startsWith('ROLE_') ? r : `ROLE_${r}`));

    const profile: LoginUserProfile = {
      id: (claims['sub'] as string) ?? '',
      username:
        (claims['preferred_username'] as string) ?? (claims['email'] as string) ?? '',
      email: (claims['email'] as string) ?? '',
      firstName: (claims['given_name'] as string) ?? undefined,
      lastName: (claims['family_name'] as string) ?? undefined,
      roles,
      active: true,
      primaryHospitalId: (claims['hospital_id'] as string) ?? undefined,
    };

    this.auth.setUserProfile(profile);
  }
}

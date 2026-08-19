import type { EnvironmentName } from './environment-name';

export const environment = {
  production: false,
  // Drives the `app.environment` tag on Faro telemetry. Must NOT be derived from
  // the `production` boolean — UAT can ship as a production-mode Angular build
  // (minified, no source maps) while still wanting the `uat` telemetry tag.
  name: 'local' satisfies EnvironmentName,
  apiUrl: '/api',
  apiBase: '/api',
  gaTrackingId: '',
  faroCollectorUrl: '',
  // KC-2b: Keycloak / OIDC PKCE login. When enabled, the portal redirects
  // to `oidc.issuer` instead of POSTing to /api/auth/login. Default OFF so
  // local builds keep using the legacy form-based login until a developer
  // opts in by running `docker compose --profile keycloak up`.
  oidc: {
    enabled: false,
    issuer: 'http://localhost:8081/realms/hms',
    clientId: 'hms-portal',
    redirectUri: 'http://localhost:4200/login',
    postLogoutRedirectUri: 'http://localhost:4200/login',
    // No `offline_access` — refresh tokens are intentionally avoided in the
    // SPA. Silent refresh via the OIDC iframe + short-lived access tokens.
    scope: 'openid profile email roles hms-claims',
    // When false (default), the OIDC access token is mirrored into
    // sessionStorage so it dies with the tab. Set true only on trusted
    // workstations where 'remember me' semantics are desired.
    remember: false,
  },
};

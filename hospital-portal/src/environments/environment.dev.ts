import type { EnvironmentName } from './environment-name';

export const environment = {
  production: false,
  name: 'dev' satisfies EnvironmentName,
  apiUrl: '/api',
  apiBase: '/api',
  faroCollectorUrl: '',
  // Hosted dev tier (Railway, served at https://hms.dev.bitnesttechs.com).
  // Targets the per-env Railway Keycloak provisioned per
  // keycloak/prod/README.md. Local laptop dev (`ng serve`) uses
  // environment.ts which still points at localhost:8081 for the
  // docker-compose Keycloak profile.
  //
  // `enabled` stays false until the hosted-dev cutover (P-2 / Phase 2.8.B
  // in docs/keycloak-implementation-gaps.md). Flip after the realm import
  // includes hms.dev.bitnesttechs.com redirect URIs and a UAT soak window
  // confirms login + token refresh end to end.
  oidc: {
    enabled: false,
    issuer: 'https://hms-keycloak-dev-dev.up.railway.app/realms/hms',
    clientId: 'hms-portal',
    redirectUri: 'https://hms.dev.bitnesttechs.com/login',
    postLogoutRedirectUri: 'https://hms.dev.bitnesttechs.com/login',
    scope: 'openid profile email roles hms-claims',
    remember: false,
  },
};

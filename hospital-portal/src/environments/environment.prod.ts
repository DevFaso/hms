import type { EnvironmentName } from './environment-name';

export const environment = {
  production: true,
  name: 'production' satisfies EnvironmentName,
  apiUrl: '/api',
  apiBase: '/api',
  // Set to a real GA4 measurement ID (G-…) to enable analytics; empty = disabled.
  gaTrackingId: '',
  faroCollectorUrl:
    'https://faro-collector-prod-us-east-2.grafana.net/collect/68020ea38dd231d753b47556676f9b7c',
  // Prod Keycloak issuer is the Railway-hosted Keycloak (Phase 2.8.A,
  // provisioned 2026-05-07). Stays disabled until the dev soak completes
  // and Phase 3 cutover per docs/tasks-keycloak.md §KC-4.
  oidc: {
    enabled: false,
    issuer: 'https://hms-keycloak-prod-prod.up.railway.app/realms/hms',
    clientId: 'hms-portal',
    redirectUri: 'https://e-keneya.com/login',
    postLogoutRedirectUri: 'https://e-keneya.com/login',
    // No `offline_access` — keep refresh tokens out of the browser. With short
    // access-token TTL + silent refresh this gives a balance of UX and safety.
    scope: 'openid profile email roles hms-claims',
    remember: false,
  },
};

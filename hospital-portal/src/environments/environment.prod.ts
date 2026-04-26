import type { EnvironmentName } from './environment-name';

export const environment = {
  production: true,
  name: 'production' satisfies EnvironmentName,
  apiUrl: '/api',
  apiBase: '/api',
  gaTrackingId: 'G-XXXXXXXXXX',
  faroCollectorUrl:
    'https://faro-collector-prod-us-east-2.grafana.net/collect/68020ea38dd231d753b47556676f9b7c',
  // KC-2b: prod Keycloak issuer (P-2). Stays disabled until prod Keycloak
  // is provisioned and a UAT soak completes (see docs/tasks-keycloak.md §KC-4).
  oidc: {
    enabled: false,
    issuer: 'https://keycloak.hms.example.com/realms/hms',
    clientId: 'hms-portal',
    redirectUri: 'https://hms.example.com/login',
    postLogoutRedirectUri: 'https://hms.example.com/login',
    // No `offline_access` — keep refresh tokens out of the browser. With short
    // access-token TTL + silent refresh this gives a balance of UX and safety.
    scope: 'openid profile email roles hms-claims',
    remember: false,
  },
};

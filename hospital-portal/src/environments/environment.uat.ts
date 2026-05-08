import type { EnvironmentName } from './environment-name';

export const environment = {
  production: false,
  name: 'uat' satisfies EnvironmentName,
  apiUrl: '/api',
  apiBase: '/api',
  faroCollectorUrl: '',
  // UAT Keycloak issuer is the Railway-hosted Keycloak (Phase 2.8.A).
  // Stays disabled until cutover per Phase 2.8.B in
  // docs/keycloak-implementation-gaps.md.
  oidc: {
    enabled: false,
    issuer: 'https://hms-keycloak-uat-uat.up.railway.app/realms/hms',
    clientId: 'hms-portal',
    redirectUri: 'https://hms.uat.bitnesttechs.com/login',
    postLogoutRedirectUri: 'https://hms.uat.bitnesttechs.com/login',
    scope: 'openid profile email roles hms-claims',
    remember: false,
  },
};

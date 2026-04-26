import type { EnvironmentName } from './environment-name';

export const environment = {
  production: false,
  name: 'dev' satisfies EnvironmentName,
  apiUrl: '/api',
  apiBase: '/api',
  faroCollectorUrl: '',
  // KC-2b: dev points at the local docker-compose Keycloak (profile `keycloak`).
  // Toggle `enabled` to true once realm-export.json has been imported.
  oidc: {
    enabled: false,
    issuer: 'http://localhost:8081/realms/hms',
    clientId: 'hms-portal',
    redirectUri: 'http://localhost:4200/login',
    postLogoutRedirectUri: 'http://localhost:4200/login',
    scope: 'openid profile email roles hms-claims',
    remember: false,
  },
};

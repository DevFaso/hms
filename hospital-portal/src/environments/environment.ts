export const environment = {
  production: false,
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
    scope: 'openid profile email roles hms-claims offline_access',
  },
};

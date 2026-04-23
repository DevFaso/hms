export const environment = {
  production: false,
  apiUrl: '/api',
  apiBase: '/api',
  faroCollectorUrl:
    'https://faro-collector-prod-us-east-2.grafana.net/collect/68020ea38dd231d753b47556676f9b7c',
  // KC-2b: UAT Keycloak issuer comes from the Railway-hosted Keycloak (P-2)
  // once provisioned. Stays disabled until that infra lands.
  oidc: {
    enabled: false,
    issuer: 'https://keycloak.uat.hms.example.com/realms/hms',
    clientId: 'hms-portal',
    redirectUri: 'https://uat.hms.example.com/login',
    postLogoutRedirectUri: 'https://uat.hms.example.com/login',
    scope: 'openid profile email roles hms-claims',
    remember: false,
  },
};

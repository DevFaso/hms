package com.example.hms.fhir.smart;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * SMART-on-FHIR App Launch v1.0 configuration. The property tree is
 * deliberately small — most deployments inherit endpoints from the
 * existing {@code app.auth.oidc.*} settings, and only override the
 * scopes/capabilities they intend to advertise.
 *
 * <p>When neither this tree nor {@code app.auth.oidc.issuer-uri} is set,
 * the {@code /.well-known/smart-configuration} endpoint falls back to the
 * legacy HMS auth endpoints so SMART discovery still produces a usable
 * document.
 */
@ConfigurationProperties(prefix = "app.fhir.smart")
public class SmartConfigurationProperties {

    /**
     * Fully-qualified base URL of the FHIR server, used when an absolute
     * URL must appear in the SMART configuration. When unset the URL is
     * derived from the inbound request (honouring {@code X-Forwarded-*}).
     */
    private String issuer = "";

    /**
     * Override the {@code authorization_endpoint}. When blank we use the
     * value advertised by {@code app.auth.oidc.issuer-uri}'s discovery
     * document, falling back to the legacy {@code /api/auth/login}.
     */
    private String authorizationEndpoint = "";

    /** Override the {@code token_endpoint}. */
    private String tokenEndpoint = "";

    /** Override the {@code introspection_endpoint}. Optional in SMART 1.0. */
    private String introspectionEndpoint = "";

    /** Override the {@code revocation_endpoint}. Optional in SMART 1.0. */
    private String revocationEndpoint = "";

    /** Scopes this server is willing to issue. SMART 1.0 default below. */
    private List<String> scopesSupported = new ArrayList<>(List.of(
        "openid", "profile", "fhirUser", "offline_access",
        "launch", "launch/patient", "launch/encounter",
        "patient/*.read", "user/*.read"
    ));

    /** OAuth response types supported. */
    private List<String> responseTypesSupported = new ArrayList<>(List.of("code"));

    /** PKCE code challenge methods supported. */
    private List<String> codeChallengeMethodsSupported = new ArrayList<>(List.of("S256"));

    /**
     * Capability strings advertised on the {@code capabilities} key of the
     * SMART configuration. The defaults reflect what HMS actually does —
     * EHR launch context, patient-scoped reads, public client (PKCE).
     */
    private List<String> capabilities = new ArrayList<>(List.of(
        "launch-ehr",
        "client-public",
        "client-confidential-symmetric",
        "context-ehr-patient",
        "context-ehr-encounter",
        "permission-patient",
        "permission-user"
    ));

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }

    public String getAuthorizationEndpoint() { return authorizationEndpoint; }
    public void setAuthorizationEndpoint(String authorizationEndpoint) { this.authorizationEndpoint = authorizationEndpoint; }

    public String getTokenEndpoint() { return tokenEndpoint; }
    public void setTokenEndpoint(String tokenEndpoint) { this.tokenEndpoint = tokenEndpoint; }

    public String getIntrospectionEndpoint() { return introspectionEndpoint; }
    public void setIntrospectionEndpoint(String introspectionEndpoint) { this.introspectionEndpoint = introspectionEndpoint; }

    public String getRevocationEndpoint() { return revocationEndpoint; }
    public void setRevocationEndpoint(String revocationEndpoint) { this.revocationEndpoint = revocationEndpoint; }

    public List<String> getScopesSupported() { return scopesSupported; }
    public void setScopesSupported(List<String> scopesSupported) { this.scopesSupported = scopesSupported; }

    public List<String> getResponseTypesSupported() { return responseTypesSupported; }
    public void setResponseTypesSupported(List<String> responseTypesSupported) { this.responseTypesSupported = responseTypesSupported; }

    public List<String> getCodeChallengeMethodsSupported() { return codeChallengeMethodsSupported; }
    public void setCodeChallengeMethodsSupported(List<String> codeChallengeMethodsSupported) { this.codeChallengeMethodsSupported = codeChallengeMethodsSupported; }

    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
}

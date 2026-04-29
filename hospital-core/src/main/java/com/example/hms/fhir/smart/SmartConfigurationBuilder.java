package com.example.hms.fhir.smart;

import com.example.hms.fhir.smart.SmartConfigurationController.SmartConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the SMART-on-FHIR configuration document from a request, layering:
 * explicit overrides → OIDC issuer → request-derived fallbacks.
 *
 * <p>Decoupled from the HTTP entry point so it can be invoked from a HAPI
 * interceptor as well as from unit tests.
 */
@Component
public class SmartConfigurationBuilder {

    private final SmartConfigurationProperties props;
    private final String oidcIssuerUri;

    public SmartConfigurationBuilder(
        SmartConfigurationProperties props,
        @Value("${app.auth.oidc.issuer-uri:}") String oidcIssuerUri
    ) {
        this.props = props;
        this.oidcIssuerUri = oidcIssuerUri == null ? "" : oidcIssuerUri.trim();
    }

    public SmartConfiguration build(HttpServletRequest request) {
        String requestBase = absoluteBaseUrl(request);
        String authEndpoint = firstNonBlank(
            props.getAuthorizationEndpoint(),
            oidcIssuerUri.isEmpty() ? null : (oidcIssuerUri + "/protocol/openid-connect/auth"),
            requestBase + "/auth/login"
        );
        String tokenEndpoint = firstNonBlank(
            props.getTokenEndpoint(),
            oidcIssuerUri.isEmpty() ? null : (oidcIssuerUri + "/protocol/openid-connect/token"),
            requestBase + "/auth/token/refresh"
        );
        String issuer = firstNonBlank(
            props.getIssuer(),
            oidcIssuerUri.isEmpty() ? null : oidcIssuerUri,
            requestBase + "/fhir"
        );
        return new SmartConfiguration(
            issuer,
            authEndpoint,
            tokenEndpoint,
            nullIfBlank(props.getIntrospectionEndpoint()),
            nullIfBlank(props.getRevocationEndpoint()),
            props.getScopesSupported(),
            props.getResponseTypesSupported(),
            props.getCodeChallengeMethodsSupported(),
            props.getCapabilities()
        );
    }

    static String absoluteBaseUrl(HttpServletRequest req) {
        String scheme = headerOrDefault(req, "X-Forwarded-Proto", req.getScheme());
        String host = headerOrDefault(req, "X-Forwarded-Host", req.getServerName());
        String portHeader = req.getHeader("X-Forwarded-Port");
        int port = parsePort(portHeader, req.getServerPort());
        StringBuilder url = new StringBuilder().append(scheme).append("://").append(host);
        if (port > 0 && !isDefaultPort(scheme, port)) url.append(":").append(port);
        url.append(req.getContextPath() == null ? "" : req.getContextPath());
        return url.toString();
    }

    private static String headerOrDefault(HttpServletRequest req, String header, String fallback) {
        String v = req.getHeader(header);
        if (v == null || v.isBlank()) return fallback;
        return v.split(",")[0].trim();
    }

    private static int parsePort(String header, int fallback) {
        if (header == null || header.isBlank()) return fallback;
        try { return Integer.parseInt(header.trim()); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equalsIgnoreCase(scheme) && port == 80)
            || ("https".equalsIgnoreCase(scheme) && port == 443);
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) if (c != null && !c.isBlank()) return c.trim();
        return null;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}

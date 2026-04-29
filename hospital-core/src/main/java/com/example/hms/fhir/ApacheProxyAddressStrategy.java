package com.example.hms.fhir;

import ca.uhn.fhir.rest.server.IServerAddressStrategy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Address strategy that builds the FHIR base URL honoring
 * {@code X-Forwarded-Proto}, {@code X-Forwarded-Host} and the configured
 * {@code app.fhir.serverBaseUrl} prefix.
 *
 * <p>Used when HMS is deployed behind Railway / nginx where the externally
 * visible scheme/host differ from what the servlet container sees.
 */
final class ApacheProxyAddressStrategy implements IServerAddressStrategy {

    private final String configuredBaseUrl;

    ApacheProxyAddressStrategy(@Nullable String configuredBaseUrl) {
        this.configuredBaseUrl = configuredBaseUrl;
    }

    @Override
    public String determineServerBase(jakarta.servlet.ServletContext servletContext, HttpServletRequest request) {
        if (StringUtils.hasText(configuredBaseUrl) && configuredBaseUrl.startsWith("http")) {
            return stripTrailingSlash(configuredBaseUrl);
        }

        String scheme = headerOrDefault(request, "X-Forwarded-Proto", request.getScheme());
        String host = headerOrDefault(request, "X-Forwarded-Host", request.getServerName());
        String portHeader = request.getHeader("X-Forwarded-Port");
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();

        StringBuilder url = new StringBuilder()
            .append(scheme).append("://").append(host);
        if (StringUtils.hasText(portHeader)) {
            int port = parsePort(portHeader, -1);
            if (port > 0 && !isDefaultPort(scheme, port)) {
                url.append(":").append(port);
            }
        } else {
            int port = request.getServerPort();
            if (port > 0 && !isDefaultPort(scheme, port)) {
                url.append(":").append(port);
            }
        }
        url.append(contextPath).append("/fhir");
        return stripTrailingSlash(url.toString());
    }

    private static String headerOrDefault(HttpServletRequest req, String header, String fallback) {
        String v = req.getHeader(header);
        return StringUtils.hasText(v) ? v.split(",")[0].trim() : fallback;
    }

    private static int parsePort(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equalsIgnoreCase(scheme) && port == 80)
            || ("https".equalsIgnoreCase(scheme) && port == 443);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

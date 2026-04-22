package com.example.hms.security.oidc;

import com.nimbusds.jwt.JWTParser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.util.StringUtils;

import java.text.ParseException;
import java.util.Objects;

/**
 * {@link BearerTokenResolver} that returns a bearer token to the
 * oauth2-resource-server filter <em>only</em> when the token's {@code iss}
 * claim matches the configured Keycloak issuer URI.
 *
 * <p>This lets us run the existing internal {@code JwtAuthenticationFilter}
 * (which authenticates HMAC/RSA tokens minted by {@code JwtTokenProvider})
 * <em>alongside</em> Spring's resource-server stack without one stack
 * trying to authenticate the other's tokens. Internal tokens have no
 * {@code iss} claim, so this resolver returns {@code null} for them and
 * the resource-server filter is a no-op.</p>
 *
 * <p>Parsing is done <strong>without</strong> signature verification — that
 * is the resource server's job once we hand the token off. Reading the
 * {@code iss} claim from an unverified JWT is safe because we only use it
 * to choose which authenticator to invoke; the chosen authenticator then
 * cryptographically verifies the token before granting any authority.</p>
 */
public class IssuerAwareBearerTokenResolver implements BearerTokenResolver {

    private static final Logger log = LoggerFactory.getLogger(IssuerAwareBearerTokenResolver.class);

    private final BearerTokenResolver delegate;
    private final String oidcIssuerUri;

    public IssuerAwareBearerTokenResolver(String oidcIssuerUri) {
        this(new DefaultBearerTokenResolver(), oidcIssuerUri);
    }

    IssuerAwareBearerTokenResolver(BearerTokenResolver delegate, String oidcIssuerUri) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.oidcIssuerUri = Objects.requireNonNull(oidcIssuerUri, "oidcIssuerUri").trim();
    }

    @Override
    public String resolve(HttpServletRequest request) {
        if (!StringUtils.hasText(oidcIssuerUri)) {
            // OIDC is disabled in this environment.
            return null;
        }
        String token;
        try {
            token = delegate.resolve(request);
        } catch (RuntimeException ex) {
            // DefaultBearerTokenResolver throws when both header and form-param are
            // present — that's a malformed request, not our concern. Let the
            // internal filter (or the next step in the chain) handle it.
            log.debug("[OIDC] Delegate bearer-token resolver rejected request: {}", ex.getMessage());
            return null;
        }
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String issuer = readIssuerClaim(token);
        if (oidcIssuerUri.equals(issuer)) {
            return token;
        }
        return null;
    }

    /**
     * Best-effort issuer extraction. Returns {@code null} for unparseable tokens
     * (which the internal filter will reject) or tokens with no {@code iss} claim
     * (which is how all current internal tokens look).
     */
    private static String readIssuerClaim(String token) {
        try {
            return JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException | RuntimeException ex) {
            return null;
        }
    }
}

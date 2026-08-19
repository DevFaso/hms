package com.example.hms.security.oidc;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IssuerAwareBearerTokenResolverTest {

    private static final String OIDC_ISSUER = "https://kc.example.com/realms/hms";

    @Test
    void returnsNullWhenIssuerUriIsBlank() {
        IssuerAwareBearerTokenResolver resolver = new IssuerAwareBearerTokenResolver("");
        MockHttpServletRequest request = bearerRequest(jwtWithIssuer(OIDC_ISSUER));

        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void returnsNullWhenNoBearerTokenPresent() {
        IssuerAwareBearerTokenResolver resolver = new IssuerAwareBearerTokenResolver(OIDC_ISSUER);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void returnsTokenWhenIssuerMatches() {
        IssuerAwareBearerTokenResolver resolver = new IssuerAwareBearerTokenResolver(OIDC_ISSUER);
        String token = jwtWithIssuer(OIDC_ISSUER);
        MockHttpServletRequest request = bearerRequest(token);

        assertThat(resolver.resolve(request)).isEqualTo(token);
    }

    @Test
    void returnsNullWhenIssuerDoesNotMatch() {
        IssuerAwareBearerTokenResolver resolver = new IssuerAwareBearerTokenResolver(OIDC_ISSUER);
        String token = jwtWithIssuer("https://other-issuer.example.com");
        MockHttpServletRequest request = bearerRequest(token);

        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void returnsNullWhenTokenHasNoIssuerClaim() {
        // Internal tokens minted by JwtTokenProvider currently have no `iss` claim.
        IssuerAwareBearerTokenResolver resolver = new IssuerAwareBearerTokenResolver(OIDC_ISSUER);
        String token = jwtWithoutIssuer();
        MockHttpServletRequest request = bearerRequest(token);

        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void returnsNullWhenTokenIsUnparseable() {
        IssuerAwareBearerTokenResolver resolver = new IssuerAwareBearerTokenResolver(OIDC_ISSUER);
        MockHttpServletRequest request = bearerRequest("not-a-jwt");

        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void swallowsDelegateExceptionsAndReturnsNull() {
        BearerTokenResolver throwing = mock(BearerTokenResolver.class);
        when(throwing.resolve(any())).thenThrow(new RuntimeException("malformed request"));
        IssuerAwareBearerTokenResolver resolver = new IssuerAwareBearerTokenResolver(throwing, OIDC_ISSUER);

        assertThat(resolver.resolve(new MockHttpServletRequest())).isNull();
    }

    private static MockHttpServletRequest bearerRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private static String jwtWithIssuer(String issuer) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).subject("u-1").build();
        return new PlainJWT(claims).serialize();
    }

    private static String jwtWithoutIssuer() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("u-1").build();
        return new PlainJWT(claims).serialize();
    }

    @SuppressWarnings("unchecked")
    private static <T> T any() {
        return (T) org.mockito.ArgumentMatchers.any(HttpServletRequest.class);
    }
}

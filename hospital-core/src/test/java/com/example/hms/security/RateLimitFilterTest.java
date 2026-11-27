package com.example.hms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(5, false); // 5 requests per minute for testing
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsRequestsWithinLimit() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setServletPath("/api/auth/login");
        request.setRequestURI("/api/auth/login");
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }
        verify(chain, times(5)).doFilter(any(), any());
    }

    @Test
    void returns429WhenLimitExceeded() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setServletPath("/api/auth/login");
        request.setRequestURI("/api/auth/login");
        request.setRemoteAddr("10.0.0.1");

        // Exhaust the bucket
        for (int i = 0; i < 5; i++) {
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();
        verify(chain, times(5)).doFilter(any(), any()); // only 5, not 6
    }

    @Test
    void usesUsernameKeyWhenAuthenticated() throws ServletException, IOException {
        var auth = new UsernamePasswordAuthenticationToken("doc@hms.com", null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
        request.setServletPath("/api/auth/refresh");
        request.setRequestURI("/api/auth/refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void skipsNonAuthPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/patients");
        request.setServletPath("/api/patients");
        request.setRequestURI("/api/patients");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void rateLimitsTheMfaStepUpEndpoints() {
        for (String path : new String[] {"/api/auth/mfa/enroll", "/api/auth/mfa/verify-enrollment",
                "/api/auth/credentials/mfa"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setRequestURI(path);
            assertThat(filter.shouldNotFilter(request)).as(path).isFalse();
        }
    }

    /** D2000: the recovery-contact routes send a code (a paid SMS for a phone). */
    @Test
    void rateLimitsTheRecoveryContactRoutes() {
        for (String path : new String[] {"/api/auth/credentials/recovery",
                "/api/auth/credentials/recovery/" + java.util.UUID.randomUUID() + "/send-code",
                "/api/auth/credentials/recovery/" + java.util.UUID.randomUUID() + "/verify"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setRequestURI(path);
            assertThat(filter.shouldNotFilter(request)).as(path).isFalse();
        }
    }

    /** QA D787: every anonymous path that sends a reset message, the legacy one included. */
    @Test
    void rateLimitsEveryPasswordResetPath() {
        for (String path : new String[] {"/api/auth/request-reset", "/api/auth/reset-password",
                "/api/auth/password/request", "/api/auth/password/request-reset",
                "/api/auth/password/confirm"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setRequestURI(path);
            assertThat(filter.shouldNotFilter(request)).as(path).isFalse();
        }
    }

    private static MockHttpServletRequest loginFrom(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setServletPath("/api/auth/login");
        request.setRequestURI("/api/auth/login");
        request.setRemoteAddr(peer);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    void behindATrustedProxy_theCallersOwnForwardedEntryDoesNotBuyANewBucket() throws ServletException, IOException {
        RateLimitFilter behindProxy = new RateLimitFilter(5, true);
        // Each attempt claims a different address at the left; our proxy appended the real one.
        for (int i = 0; i < 5; i++) {
            behindProxy.doFilter(loginFrom("10.0.0.5", "203.0.113." + i + ", 41.203.10.7"),
                new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse sixth = new MockHttpServletResponse();
        behindProxy.doFilter(loginFrom("10.0.0.5", "198.51.100.1, 41.203.10.7"), sixth, chain);

        assertThat(sixth.getStatus()).isEqualTo(429);
        // Another real client behind the same proxy has its own bucket.
        MockHttpServletResponse other = new MockHttpServletResponse();
        behindProxy.doFilter(loginFrom("10.0.0.5", "41.203.10.8"), other, chain);
        assertThat(other.getStatus()).isEqualTo(200);
    }

    @Test
    void withoutATrustedProxy_theForwardedHeaderIsIgnored() throws ServletException, IOException {
        for (int i = 0; i < 5; i++) {
            filter.doFilter(loginFrom("10.0.0.9", "41.203.10." + i), new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse sixth = new MockHttpServletResponse();
        filter.doFilter(loginFrom("10.0.0.9", "41.203.10.99"), sixth, chain);

        assertThat(sixth.getStatus()).isEqualTo(429);
    }
}

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
        filter = new RateLimitFilter(5); // 5 requests per minute for testing
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsRequestsWithinLimit() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/patients");
        request.setServletPath("/api/patients");
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }
        verify(chain, times(5)).doFilter(any(), any());
    }

    @Test
    void returns429WhenLimitExceeded() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/patients");
        request.setServletPath("/api/patients");
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

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/patients");
        request.setServletPath("/api/patients");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void skipsActuatorPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/actuator/health");
        request.setServletPath("/api/actuator/health");
        request.setRequestURI("/api/actuator/health");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }
}

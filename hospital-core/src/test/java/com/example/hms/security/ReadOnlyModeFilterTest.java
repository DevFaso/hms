package com.example.hms.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Read-only filter (P3 #23a). Pins two contracts beyond blocking: the 503
 * carries the X-Readonly-Mode discriminator (the offline-dispense
 * interceptor queues bare 503s for replay), and the allowlist uses
 * /api-prefixed paths — servlet filters see the context path, unlike
 * SecurityConfig matchers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReadOnlyModeFilterTest {

    @Mock private DowntimeStateService downtimeStateService;
    @Mock private FilterChain chain;

    private ReadOnlyModeFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ReadOnlyModeFilter(downtimeStateService);
        when(downtimeStateService.snapshot())
            .thenReturn(new DowntimeStateService.DowntimeSnapshot(true, "Maintenance window", null));
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    void mutatingRequestsGet503WithTheDiscriminatorHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "/api/patients"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("X-Readonly-Mode")).isEqualTo("true");
        assertThat(response.getHeader("Retry-After")).isEqualTo("300");
        assertThat(response.getContentAsString()).contains("READ_ONLY_MODE").contains("Maintenance window");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getsPassThroughUnchanged() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/api/patients"), response, chain);

        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void authAndTelemetryAndTheToggleItselfStayOpen() throws Exception {
        for (String uri : new String[] {
            "/api/auth/login", "/api/auth/token/refresh", "/api/frontend-audit",
            "/api/super-admin/downtime"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("POST", uri), response, chain);
            assertThat(response.getStatus()).as(uri).isNotEqualTo(503);
        }
    }

    @Test
    void allowlistIsContextPrefixed_aContextStrippedPathIsStillBlocked() throws Exception {
        // Servlet filters see /api/...; a SecurityConfig-style "/auth/login"
        // does NOT match the allowlist. This pins the convention that has
        // already produced one in-repo near-miss (RateLimitFilter vs
        // SecurityConfig path styles).
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "/auth/login"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void normalModeTouchesNothing() throws Exception {
        when(downtimeStateService.snapshot())
            .thenReturn(DowntimeStateService.DowntimeSnapshot.NORMAL);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("DELETE", "/api/patients/x"), response, chain);

        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}

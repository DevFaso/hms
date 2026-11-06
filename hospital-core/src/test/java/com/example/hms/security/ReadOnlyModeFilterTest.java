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

    @Mock private org.springframework.beans.factory.ObjectProvider<org.springframework.web.servlet.HandlerMapping> mappings;
    @Mock private org.springframework.web.servlet.LocaleResolver localeResolver;
    private final org.springframework.context.support.StaticMessageSource messages =
        new org.springframework.context.support.StaticMessageSource();

    @BeforeEach
    void setUp() {
        messages.addMessage(ReadOnlyModeFilter.DEFAULT_MESSAGE_KEY, java.util.Locale.FRENCH, "Lecture seule : la consultation reste possible.");
        messages.addMessage(ReadOnlyModeFilter.DEFAULT_MESSAGE_KEY, java.util.Locale.of("es"), "Solo lectura: la consulta sigue disponible.");
        filter = new ReadOnlyModeFilter(downtimeStateService, mappings, messages, localeResolver);
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
    void d1407_theDefaultMessageIsInTheReadersLanguageAndNotMarkedCustom() throws Exception {
        when(downtimeStateService.snapshot())
            .thenReturn(new DowntimeStateService.DowntimeSnapshot(true, null, null));
        for (java.util.Map.Entry<java.util.Locale, String> reader : java.util.Map.of(
            java.util.Locale.FRENCH, "Lecture seule", java.util.Locale.of("es"), "Solo lectura").entrySet()) {
            when(localeResolver.resolveLocale(org.mockito.ArgumentMatchers.any())).thenReturn(reader.getKey());
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("POST", "/api/patients"), response, chain);
            assertThat(response.getContentAsString()).contains(reader.getValue()).contains("\"customMessage\":false");
        }
    }

    @Test
    void d1407_anAdministratorsOwnMessageIsKeptAndMarkedCustomAndStaysValidJson() throws Exception {
        when(downtimeStateService.snapshot())
            .thenReturn(new DowntimeStateService.DowntimeSnapshot(true, "Say \"stop\"\nC:\\now", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/patients"), response, chain);
        assertThat(response.getContentAsString()).contains("\"customMessage\":true");
        assertThat(new tools.jackson.databind.ObjectMapper().readTree(response.getContentAsString()).get("message").asString())
            .isEqualTo("Say 'stop' C:\\now");
    }

    @Test
    void d1404_theFhirReadFormsPassAndItsWritesAndExportDoNot() {
        assertThat(ReadOnlyModeFilter.isFhirRead("/api/fhir/Patient/_search")).isTrue();
        assertThat(ReadOnlyModeFilter.isFhirRead("/api/fhir/Patient/123/$everything")).isTrue();
        assertThat(ReadOnlyModeFilter.isFhirRead("/api/fhir/Patient")).isFalse();
        assertThat(ReadOnlyModeFilter.isFhirRead("/api/fhir/$export")).isFalse();
        assertThat(ReadOnlyModeFilter.isFhirRead("/api/patients/_search")).isFalse();
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

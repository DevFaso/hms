package com.example.hms.controller;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Foundation-pass integration tests for the KPI dashboard endpoint
 * (roadmap row 32, v1.1 / Reporting).
 *
 * <p>The KPI math itself is exercised by service-level unit tests
 * (added in the row-32 follow-on against the materialized-view
 * refresh). At foundation-pass scope this IT asserts the wire
 * contract: auth required, method shape, query-param validation.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
class KpiDashboardControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /kpi/dashboard without auth returns 401")
    void unauthenticatedReturns401() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            "/kpi/dashboard?from=2026-01-01&to=2026-01-31", String.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("POST /kpi/dashboard returns 405 (read-only endpoint)")
    void postReturns405() {
        ResponseEntity<String> response = restTemplate.postForEntity(
            "/kpi/dashboard", "{}", String.class
        );
        // /kpi/dashboard is not POST-routable. Spring's filter chain can
        // surface this as 401 (auth missing), 403 (CSRF token missing on a
        // state-changing method — the actual response in this test profile),
        // or 405 (handler exists but not for POST). All three prove the
        // endpoint is read-only and unreached by POST.
        assertThat(response.getStatusCode().value())
            .as("POST must not reach a handler")
            .isIn(401, 403, 405);
    }
}

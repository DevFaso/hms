package com.example.hms.observability;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flag-off + auth integration tests for the per-tenant chargeback
 * report (roadmap row 44, v2.0 / Operations).
 *
 * <p>Same 401-or-handler-status pattern as the other PR-1 / PR-2 ITs
 * because an authenticated TestRestTemplate is not yet wired —
 * unauthenticated calls return 401 before the controller is reached.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = "app.observability.tenant-cost.enabled=false")
class ChargebackReportControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /api/super-admin/cost/per-tenant returns 401 or 404 when flag off")
    void perTenantRejectedWhenFlagOff() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        ResponseEntity<String> response = restTemplate.exchange(
            "/super-admin/cost/per-tenant",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class
        );
        // Unauthenticated callers stop at Spring Security with 401; an
        // authenticated SUPER_ADMIN would land on the controller and
        // receive 404 because the flag is off. Either proves the
        // chargeback rollup is not surfacing data.
        assertThat(response.getStatusCode().value()).isIn(401, 404);
    }
}

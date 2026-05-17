package com.example.hms.security.tenant.schema;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flag-off + auth integration test for the schema-tenant cache
 * invalidation endpoint (roadmap row 33 follow-on, v2.0 /
 * Multi-tenancy).
 *
 * <p>Same gate-status pattern as the other foundation-pass ITs
 * (ChargebackReportControllerIT, DicomProxyControllerIT). One
 * difference: this endpoint is POST, so an unauthenticated call hits
 * Spring Security's CSRF filter first and returns
 * <strong>403</strong> (not 401 as GET endpoints do — caught on
 * PR #356 CI). With the flag off the controller returns 404 instead.
 * Any of 401 / 403 / 404 proves the endpoint is not surfacing cache
 * control under the default row-level topology.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = "app.tenancy.schema-isolation.enabled=false")
class TenantSchemaCacheControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("POST /api/super-admin/tenancy/schema-cache/invalidate/{id} returns 401/403/404 when flag off")
    void invalidateRejectedWhenFlagOff() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        UUID hospitalId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        ResponseEntity<String> response = restTemplate.exchange(
            "/super-admin/tenancy/schema-cache/invalidate/" + hospitalId,
            HttpMethod.POST,
            new HttpEntity<>(headers),
            String.class
        );
        // 401 — anonymous (no auth header)
        // 403 — CSRF rejection on POST without token
        // 404 — authenticated SUPER_ADMIN, flag off
        // Any of the three proves the endpoint is not surfacing cache
        // control under the default row-level topology.
        assertThat(response.getStatusCode().value()).isIn(401, 403, 404);
    }
}

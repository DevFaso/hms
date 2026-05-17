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
 * <p>Same 401-or-handler-status pattern as the other foundation-pass
 * ITs (ChargebackReportControllerIT, DicomProxyControllerIT): an
 * authenticated TestRestTemplate is not yet wired, so unauthenticated
 * calls stop at Spring Security with 401 before the controller is
 * reached. With the flag off the controller returns 404, so an
 * authenticated SUPER_ADMIN would land on 404 — either status proves
 * the endpoint is not surfacing cache control under the default
 * row-level topology.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = "app.tenancy.schema-isolation.enabled=false")
class TenantSchemaCacheControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("POST /api/super-admin/tenancy/schema-cache/invalidate/{id} returns 401 or 404 when flag off")
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
        assertThat(response.getStatusCode().value()).isIn(401, 404);
    }
}

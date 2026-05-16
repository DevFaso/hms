package com.example.hms.fhir;

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
 * Companion to {@link FhirBulkExportIT} — flips
 * {@code app.fhir.operations.bulk-export.enabled=true} and asserts the
 * CapabilityStatement advertises the {@code $export} operation.
 *
 * <p>The wire-level 202 + Content-Location assertions are deferred to
 * the row-21 follow-on once an authenticated TestRestTemplate is wired
 * (today unauthenticated calls return 401 before the operation
 * provider sees them).
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = "app.fhir.operations.bulk-export.enabled=true")
class FhirBulkExportEnabledIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("CapabilityStatement advertises the $export operation when flag on")
    void capabilityStatementAdvertisesExportWhenFlagOn() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/fhir+json");
        ResponseEntity<String> response = restTemplate.exchange(
            "/fhir/metadata",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        assertThat(body).isNotNull();
        String compact = body.replaceAll("\\s+", "");
        // HAPI auto-emits a rest.operation entry with name="export" for
        // the @Operation(name = "$export") method on the registered
        // FhirBulkExportOperationProvider. The leading $ is stripped in
        // the rendered name field.
        assertThat(compact).contains("\"name\":\"export\"");
    }
}

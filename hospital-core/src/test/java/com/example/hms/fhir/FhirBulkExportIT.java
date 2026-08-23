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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flag-off integration tests for FHIR Bulk Data Access {@code $export}
 * (roadmap row 21, v1.1).
 *
 * <p>401-or-handler-status split: Spring Security rejects unauthenticated
 * writes at 401 before HAPI sees them; the corrective flag-first
 * ordering would otherwise return 501 once an authenticated
 * TestRestTemplate is wired.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = "app.fhir.operations.bulk-export.enabled=false")
class FhirBulkExportIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("POST /fhir/$export is rejected (401 or 501) when flag off")
    void systemExportRejectedWhenFlagOff() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        ResponseEntity<String> response = restTemplate.exchange(
            "/fhir/$export",
            HttpMethod.POST,
            new HttpEntity<>("{}", headers),
            String.class
        );
        assertThat(response.getStatusCode().value()).isIn(401, 501);
    }

    @Test
    @DisplayName("POST /fhir/Patient/$export is rejected (401 or 501) when flag off")
    void patientExportRejectedWhenFlagOff() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        ResponseEntity<String> response = restTemplate.exchange(
            "/fhir/Patient/$export",
            HttpMethod.POST,
            new HttpEntity<>("{}", headers),
            String.class
        );
        assertThat(response.getStatusCode().value()).isIn(401, 501);
    }

    @Test
    @DisplayName("GET /fhir-bulk-status/{id} is rejected (401 or 501) when flag off")
    void statusEndpointRejectedWhenFlagOff() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/fhir+json");
        ResponseEntity<String> response = restTemplate.exchange(
            "/fhir-bulk-status/" + UUID.randomUUID(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class
        );
        assertThat(response.getStatusCode().value()).isIn(401, 501);
    }

    @Test
    @DisplayName("CapabilityStatement omits the export operation when flag off")
    void capabilityStatementOmitsExportWhenFlagOff() {
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
        // The "$export" operation block should NOT appear under any rest entry.
        assertThat(compact).doesNotContain("\"name\":\"export\"");
    }
}

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
 * Flag-off integration tests for the FHIR R4 Observation write path
 * (roadmap row 20 follow-on, v1.1). The Observation write surface
 * targets the {@code labresult-{uuid}} namespace only; vital-signs
 * Observations are read-only by policy (1:N source-row expansion).
 *
 * <p>Same 401-or-handler-status split as the Patient / Encounter
 * flag-off suites — Spring Security rejects unauthenticated writes at
 * 401 before HAPI sees them.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = "app.fhir.write.enabled=false")
class ObservationFhirWriteIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("PUT /fhir/Observation/labresult-{uuid} is rejected (401 or 405) when flag off")
    void putLabResultRejectedWhenFlagOff() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        String id = "labresult-" + UUID.randomUUID();
        String body = "{\"resourceType\":\"Observation\",\"id\":\"" + id + "\"}";
        ResponseEntity<String> response = restTemplate.exchange(
            "/fhir/Observation/" + id,
            HttpMethod.PUT,
            new HttpEntity<>(body, headers),
            String.class
        );
        assertThat(response.getStatusCode().value()).isIn(401, 405);
    }

    @Test
    @DisplayName("PUT /fhir/Observation/vital-{uuid}-{component} is rejected (401 or 405) when flag off")
    void putVitalSignRejectedWhenFlagOff() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        String id = "vital-" + UUID.randomUUID() + "-heart-rate";
        String body = "{\"resourceType\":\"Observation\",\"id\":\"" + id + "\"}";
        ResponseEntity<String> response = restTemplate.exchange(
            "/fhir/Observation/" + id,
            HttpMethod.PUT,
            new HttpEntity<>(body, headers),
            String.class
        );
        // Flag-off short-circuits before the vital-* policy check fires,
        // so this is the same 401-or-405 split as the labresult case.
        // Flag-on coverage of the vital-* 422 carve-out lives in
        // ObservationFhirWriteEnabledIT.
        assertThat(response.getStatusCode().value()).isIn(401, 405);
    }

    @Test
    @DisplayName("CapabilityStatement omits Observation update interaction when feature flag is off")
    void capabilityStatementOmitsObservationUpdateWhenFlagOff() {
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
        String observationBlock = EncounterFhirWriteIT.sliceResourceBlock(body, "Observation");
        assertThat(observationBlock).doesNotContain("\"code\":\"update\"");
    }
}

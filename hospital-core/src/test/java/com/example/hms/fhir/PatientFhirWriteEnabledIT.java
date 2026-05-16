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
 * Companion to {@link PatientFhirWriteIT} — flips
 * {@code app.fhir.write.enabled=true} and asserts:
 *
 * <ol>
 *   <li>CapabilityStatement advertises
 *       {@code Patient.conditionalCreate = true}.</li>
 *   <li>POST /Patient without an {@code If-None-Exist} header returns
 *       {@code 422 Unprocessable Entity} with an OperationOutcome
 *       (no auto-provisioning).</li>
 *   <li>POST /Patient with an {@code If-None-Exist} clause whose MRN
 *       does not match any active registration returns {@code 404}
 *       (per empi-identity skill).</li>
 * </ol>
 *
 * <p>Run as a separate {@code @SpringBootTest} class so the feature
 * flag flip is honored by the static Spring context cache.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = "app.fhir.write.enabled=true")
class PatientFhirWriteEnabledIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("CapabilityStatement advertises Patient.conditionalCreate=true when flag on")
    void capabilityStatementAdvertisesConditionalCreate() {
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
        int patientIdx = compact.indexOf("\"type\":\"Patient\"");
        assertThat(patientIdx).isGreaterThan(-1);
        int nextResourceIdx = compact.indexOf("\"type\":\"", patientIdx + 1);
        String patientBlock = nextResourceIdx > patientIdx
            ? compact.substring(patientIdx, nextResourceIdx)
            : compact.substring(patientIdx);
        assertThat(patientBlock).contains("\"conditionalCreate\":true");
    }

    @Test
    @DisplayName("POST /fhir/Patient without If-None-Exist returns 422 (no auto-provisioning)")
    void postWithoutIfNoneExistReturns422() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        String body = "{\"resourceType\":\"Patient\"}";
        ResponseEntity<String> response = restTemplate.exchange(
            "/fhir/Patient",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).contains("OperationOutcome");
    }

    @Test
    @DisplayName("POST /fhir/Patient with unmatched MRN in If-None-Exist returns 404")
    void postWithUnmatchedMrnReturns404() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        UUID hospitalId = UUID.randomUUID();
        String unmatchedMrn = "FHIR-WRITE-NOT-A-REAL-MRN-" + UUID.randomUUID();
        headers.set("If-None-Exist",
            "identifier=urn:hms:hospital:" + hospitalId + ":mrn|" + unmatchedMrn);
        String body = "{\"resourceType\":\"Patient\",\"identifier\":[{"
            + "\"system\":\"urn:hms:hospital:" + hospitalId + ":mrn\","
            + "\"value\":\"" + unmatchedMrn + "\"}]}";
        ResponseEntity<String> response = restTemplate.exchange(
            "/fhir/Patient",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("OperationOutcome");
    }
}

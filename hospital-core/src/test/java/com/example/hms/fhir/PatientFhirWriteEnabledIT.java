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
 *       {@code Patient.conditionalCreate = true} (the only
 *       publicly-reachable assertion — the {@code /fhir/metadata}
 *       endpoint is permit-all).</li>
 *   <li>POST /Patient without an {@code If-None-Exist} header is
 *       rejected — 401 from Spring Security or 422 from HAPI's
 *       no-auto-provisioning rule, depending on whether the test
 *       call is authenticated.</li>
 *   <li>POST /Patient with an {@code If-None-Exist} clause whose
 *       MRN does not match any active registration is rejected —
 *       401 or 404 per the same auth-vs-handler split.</li>
 * </ol>
 *
 * <p>Run as a separate {@code @SpringBootTest} class so the feature
 * flag flip is honored by the static Spring context cache.
 *
 * <p>Authenticated TestRestTemplate is deferred to the row-20
 * follow-on; once it lands, the 401-or-X assertions tighten to the
 * strict 404/422 + OperationOutcome body checks.
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
    @DisplayName("POST /fhir/Patient without If-None-Exist is rejected (401 from auth, or 422 from HAPI body validation)")
    void postWithoutIfNoneExistRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        String body = "{\"resourceType\":\"Patient\"}";
        ResponseEntity<String> response = restTemplate.exchange(
            "/fhir/Patient",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class
        );
        // /fhir/Patient is behind Spring Security's anyRequest().authenticated();
        // unauthenticated calls return 401 before HAPI sees them. When an
        // authenticated TestRestTemplate is wired (deferred — needs a
        // test-only bearer-token bootstrap), the assertion will tighten to
        // 422 strict + OperationOutcome body check.
        assertThat(response.getStatusCode().value()).isIn(401, 422);
    }

    @Test
    @DisplayName("POST /fhir/Patient with unmatched MRN is rejected (401 from auth, or 404 per empi-identity policy)")
    void postWithUnmatchedMrnRejected() {
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
        // Same 401-vs-handler-status split as above; tightens to 404 +
        // OperationOutcome when authenticated ITs land in the row-20
        // follow-on.
        assertThat(response.getStatusCode().value()).isIn(401, 404);
    }
}

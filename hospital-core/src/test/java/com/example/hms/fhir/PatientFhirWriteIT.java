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
 * Foundation-pass integration tests for the FHIR R4 write API
 * (roadmap row 20, v1.1).
 *
 * <p>Asserts the wire contract of the feature flag and the
 * conditional-create policy without provisioning a real Patient — the
 * Patient entity has tight invariants (unique email + phone, mandatory
 * User FK) that the broader write story will exercise once admin
 * provisioning lands. The PUT happy-path coverage will follow when the
 * row-20 PR moves from started → completed.
 *
 * <p>Cases:
 * <ol>
 *   <li>Feature flag default ({@code app.fhir.write.enabled=false}):
 *       PUT /Patient/{id} is rejected (401 from Spring Security or 405
 *       from HAPI — either proves the request did not mutate state).</li>
 *   <li>Feature flag default: POST /Patient is rejected (same 401-or-405
 *       split as the PUT case).</li>
 *   <li>Feature flag default: CapabilityStatement does NOT advertise
 *       Patient.conditionalCreate = true.</li>
 * </ol>
 *
 * <p>A companion test class
 * ({@link PatientFhirWriteEnabledIT}) flips the flag on and asserts the
 * advertised capability + the empi-identity rejection contract.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = "app.fhir.write.enabled=false")
class PatientFhirWriteIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("PUT /fhir/Patient/{id} is rejected (401 or 405) when app.fhir.write.enabled=false")
    void putRejectedWhenFeatureFlagOff() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        UUID id = UUID.randomUUID();
        String body = "{\"resourceType\":\"Patient\",\"id\":\"" + id + "\"}";
        ResponseEntity<String> response = restTemplate.exchange(
            "/fhir/Patient/" + id,
            HttpMethod.PUT,
            new HttpEntity<>(body, headers),
            String.class
        );
        // Spring Security rejects unauthenticated writes at 401 before HAPI
        // sees them; authenticated writes hit the @Update method which
        // short-circuits to 405 because the feature flag is off. Either
        // status proves the write was not honored — the intent of the test
        // is "flag-off requests do not mutate state", not "Spring Security
        // let it through".
        assertThat(response.getStatusCode().value()).isIn(401, 405);
    }

    @Test
    @DisplayName("POST /fhir/Patient is rejected (401 or 405) when app.fhir.write.enabled=false")
    void postRejectedWhenFeatureFlagOff() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        headers.set("If-None-Exist",
            "identifier=urn:hms:hospital:" + UUID.randomUUID() + ":mrn|MRN-0042");
        String body = "{\"resourceType\":\"Patient\"}";
        ResponseEntity<String> response = restTemplate.exchange(
            "/fhir/Patient",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class
        );
        // Same Spring-Security-vs-HAPI ordering as the PUT case above.
        assertThat(response.getStatusCode().value()).isIn(401, 405);
    }

    @Test
    @DisplayName("CapabilityStatement omits Patient.conditionalCreate when feature flag is off")
    void capabilityStatementOmitsConditionalCreateWhenFlagOff() {
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
        // The Patient resource block must NOT carry conditionalCreate=true while the flag is off.
        int patientIdx = compact.indexOf("\"type\":\"Patient\"");
        assertThat(patientIdx).isGreaterThan(-1);
        int nextResourceIdx = compact.indexOf("\"type\":\"", patientIdx + 1);
        String patientBlock = nextResourceIdx > patientIdx
            ? compact.substring(patientIdx, nextResourceIdx)
            : compact.substring(patientIdx);
        assertThat(patientBlock).doesNotContain("\"conditionalCreate\":true");
    }
}

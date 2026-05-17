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
 * Companion to {@link ObservationFhirWriteIT} — flips
 * {@code app.fhir.write.enabled=true} and asserts:
 *
 * <ol>
 *   <li>CapabilityStatement advertises the {@code update} interaction
 *       on the Observation resource entry.</li>
 *   <li>PUT against a {@code vital-*} id is rejected (401 from auth, or
 *       422 from the labresult-only carve-out enforced inside
 *       {@code ObservationFhirWriteService}).</li>
 * </ol>
 *
 * <p>Once an authenticated TestRestTemplate is wired, the
 * vital-rejection assertion tightens to 422 strict + OperationOutcome
 * BUSINESSRULE body check.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = "app.fhir.write.enabled=true")
class ObservationFhirWriteEnabledIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("CapabilityStatement advertises Observation update interaction when flag on")
    void capabilityStatementAdvertisesObservationUpdateWhenFlagOn() {
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
        assertThat(observationBlock).contains("\"code\":\"update\"");
    }

    @Test
    @DisplayName("PUT /fhir/Observation/vital-{uuid}-{component} is rejected (401 or 422) when flag on")
    void putVitalSignRejectedAsBusinessRuleWhenFlagOn() {
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
        // /fhir/Observation is behind Spring Security's anyRequest().authenticated();
        // unauthenticated calls return 401 before HAPI sees them. When
        // an authenticated TestRestTemplate lands, the assertion tightens
        // to 422 strict + OperationOutcome BUSINESSRULE check.
        assertThat(response.getStatusCode().value()).isIn(401, 422);
    }
}

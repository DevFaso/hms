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
 * Companion to {@link EncounterFhirWriteIT} — flips
 * {@code app.fhir.write.enabled=true} and asserts the
 * CapabilityStatement advertises the {@code update} interaction on
 * the Encounter resource entry.
 *
 * <p>The {@code /fhir/metadata} endpoint is the only publicly-reachable
 * assertion until an authenticated TestRestTemplate is wired; the
 * happy-path 200 + actual mutation test is deferred to the row-20
 * follow-on once the auth shim lands.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = "app.fhir.write.enabled=true")
class EncounterFhirWriteEnabledIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("CapabilityStatement advertises Encounter update interaction when flag on")
    void capabilityStatementAdvertisesEncounterUpdateWhenFlagOn() {
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
        String encounterBlock = EncounterFhirWriteIT.sliceResourceBlock(body, "Encounter");
        assertThat(encounterBlock).contains("\"code\":\"update\"");
    }
}

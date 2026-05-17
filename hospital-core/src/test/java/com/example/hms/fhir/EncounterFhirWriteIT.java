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
 * Flag-off integration tests for the FHIR R4 Encounter write path
 * (roadmap row 20 follow-on, v1.1).
 *
 * <p>Asserts the wire contract of the feature flag and the
 * CapabilityStatement match the documented "no Encounter write" stance
 * when {@code app.fhir.write.enabled=false}.
 *
 * <p>The PUT-rejected case mirrors {@link PatientFhirWriteIT}'s
 * 401-or-handler-status split — Spring Security rejects unauthenticated
 * writes at 401 before HAPI sees them; the corrective flag-first
 * ordering inside the provider would otherwise return 405 once an
 * authenticated TestRestTemplate is wired.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = "app.fhir.write.enabled=false")
class EncounterFhirWriteIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("PUT /fhir/Encounter/{id} is rejected (401 or 405) when app.fhir.write.enabled=false")
    void putRejectedWhenFeatureFlagOff() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        UUID id = UUID.randomUUID();
        String body = "{\"resourceType\":\"Encounter\",\"id\":\"" + id + "\"}";
        ResponseEntity<String> response = restTemplate.exchange(
            "/fhir/Encounter/" + id,
            HttpMethod.PUT,
            new HttpEntity<>(body, headers),
            String.class
        );
        assertThat(response.getStatusCode().value()).isIn(401, 405);
    }

    @Test
    @DisplayName("CapabilityStatement omits Encounter update interaction when feature flag is off")
    void capabilityStatementOmitsEncounterUpdateWhenFlagOff() {
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
        String encounterBlock = sliceResourceBlock(body, "Encounter");
        assertThat(encounterBlock).doesNotContain("\"code\":\"update\"");
    }

    /**
     * Return the JSON fragment from {@code "type":"Encounter"} up to the
     * next {@code "type":"…"} so the assertion is scoped to a single
     * resource entry. Reused by the flag-on companion class.
     */
    static String sliceResourceBlock(String capabilityStatementJson, String resourceType) {
        String compact = capabilityStatementJson.replaceAll("\\s+", "");
        int start = compact.indexOf("\"type\":\"" + resourceType + "\"");
        if (start < 0) return "";
        int next = compact.indexOf("\"type\":\"", start + 1);
        return next > start ? compact.substring(start, next) : compact.substring(start);
    }
}

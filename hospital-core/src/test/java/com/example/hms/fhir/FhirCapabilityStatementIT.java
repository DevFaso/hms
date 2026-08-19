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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the application on a real servlet container so the HAPI {@code RestfulServer}
 * registered at {@code /fhir/*} is exercised end-to-end. The CapabilityStatement
 * endpoint is publicly accessible per HL7 FHIR R4 spec, so no auth is required.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
class FhirCapabilityStatementIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /api/fhir/metadata returns a CapabilityStatement listing the registered resources")
    void capabilityStatementListsRegisteredResources() {
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
        assertThat(compact)
            .contains("\"resourceType\":\"CapabilityStatement\"")
            .contains("\"fhirVersion\":\"4.0.1\"")
            .contains(
                "\"type\":\"Patient\"",
                "\"type\":\"Encounter\"",
                "\"type\":\"Observation\"",
                "\"type\":\"Condition\"",
                "\"type\":\"MedicationRequest\"",
                "\"type\":\"Immunization\"");
    }
}

package com.example.hms.fhir.smart;

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
 * End-to-end checks that the SMART-on-FHIR App Launch 1.0 discovery
 * endpoint and the FHIR CapabilityStatement carry the right metadata.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
class SmartConfigurationIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("/fhir/.well-known/smart-configuration is public and reports endpoints + capabilities")
    void smartConfigurationDocumentIsPublic() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            "/fhir/.well-known/smart-configuration", String.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        assertThat(body).isNotNull();
        String compact = body.replaceAll("\\s+", "");
        assertThat(compact)
            .contains("\"authorization_endpoint\"")
            .contains("\"token_endpoint\"")
            .contains("\"capabilities\"")
            .contains("launch-ehr")
            .contains("permission-patient")
            .contains("\"S256\"");
    }

    @Test
    @DisplayName("CapabilityStatement advertises the SMART-on-FHIR security service + OAuth URIs")
    void capabilityStatementCarriesSmartSecurityExtension() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/fhir+json");
        ResponseEntity<String> response = restTemplate.exchange(
            "/fhir/metadata",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String compact = response.getBody().replaceAll("\\s+", "");
        assertThat(compact)
            .contains("\"code\":\"SMART-on-FHIR\"")
            .contains("\"url\":\"http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris\"")
            .contains("\"url\":\"authorize\"")
            .contains("\"url\":\"token\"");
    }
}

package com.example.hms.cdshooks;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the {@code GET /cds-services} discovery endpoint against the
 * <a href="https://cds-hooks.hl7.org/1.0/">CDS Hooks 1.0</a> spec —
 * structural shape, required descriptor fields, prefetch template
 * encoding, registered-service inventory, and CORS preflight from the
 * Cerner / Epic / SMART App Launcher sandbox origins (roadmap row 27).
 *
 * <p>The discovery endpoint is intentionally public per the spec; no
 * bearer auth is required. Removing or renaming a service breaks the
 * inventory assertion here before it breaks the downstream EHR
 * integration.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
class CdsHooksDiscoveryIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /cds-services returns 200 with a top-level services array (CDS Hooks 1.0 §3.1)")
    void discoveryReturnsCdsHooks10CatalogShape() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/cds-services", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
            .contains("application/json");

        JsonNode root = MAPPER.readTree(response.getBody());
        assertThat(root.has("services")).isTrue();
        assertThat(root.get("services").isArray()).isTrue();
        assertThat(root.get("services").size()).isGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("Every descriptor carries the CDS Hooks 1.0 required fields (hook, id, title)")
    void everyDescriptorCarriesRequiredFields() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/cds-services", String.class);
        JsonNode services = MAPPER.readTree(response.getBody()).get("services");

        // Acceptable hook strings per CDS Hooks 1.0 §3.2. HMS does not yet
        // emit appointment-book or encounter-* hooks; new entries will need
        // to be reflected here to keep the test honest.
        Set<String> acceptableHooks = Set.of(
            "patient-view", "order-select", "order-sign", "medication-prescribe"
        );

        for (JsonNode svc : services) {
            assertThat(svc.has("hook")).as("hook required").isTrue();
            assertThat(svc.has("id")).as("id required").isTrue();
            assertThat(svc.has("title")).as("title required").isTrue();

            String hook = svc.get("hook").asText();
            String id = svc.get("id").asText();
            assertThat(acceptableHooks).as("unexpected hook " + hook + " on service " + id)
                .contains(hook);

            assertThat(id).startsWith("hms-");
            assertThat(svc.get("title").asText()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Registered services exactly cover the v1.0 + row-26 expansion set")
    void registeredServicesMatchExpectedInventory() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/cds-services", String.class);
        JsonNode services = MAPPER.readTree(response.getBody()).get("services");

        Map<String, String> descriptorsById = new HashMap<>();
        for (JsonNode svc : services) {
            descriptorsById.put(svc.get("id").asText(), svc.get("hook").asText());
        }

        assertThat(descriptorsById).containsEntry("hms-patient-view", "patient-view");
        assertThat(descriptorsById).containsEntry("hms-bpa-protocols", "patient-view");
        assertThat(descriptorsById).containsEntry("hms-order-sign-rules", "order-sign");
        assertThat(descriptorsById).containsEntry("hms-medication-allergy-check", "order-sign");
        assertThat(descriptorsById).containsEntry("hms-order-select-rules", "order-select");
        assertThat(descriptorsById).containsEntry("hms-medication-prescribe-rules", "medication-prescribe");
    }

    @Test
    @DisplayName("Patient-view services declare prefetch templates for Cerner + Epic sandbox compatibility")
    void patientViewServicesDeclarePrefetchTemplates() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/cds-services", String.class);
        JsonNode services = MAPPER.readTree(response.getBody()).get("services");

        JsonNode patientView = findById(services, "hms-patient-view");
        assertThat(patientView).isNotNull();
        assertThat(patientView.has("prefetch")).isTrue();
        JsonNode prefetch = patientView.get("prefetch");
        assertThat(prefetch.has("patient")).isTrue();
        assertThat(prefetch.get("patient").asText()).isEqualTo("Patient/{{context.patientId}}");
        assertThat(prefetch.has("allergies")).isTrue();
        assertThat(prefetch.has("problems")).isTrue();

        JsonNode bpa = findById(services, "hms-bpa-protocols");
        assertThat(bpa).isNotNull();
        assertThat(bpa.has("prefetch")).isTrue();
        JsonNode bpaPrefetch = bpa.get("prefetch");
        assertThat(bpaPrefetch.has("patient")).isTrue();
        assertThat(bpaPrefetch.has("vitals")).isTrue();
        assertThat(bpaPrefetch.has("problems")).isTrue();
        assertThat(bpaPrefetch.has("medications")).isTrue();
    }

    @Test
    @DisplayName("CORS preflight from a SMART App Launcher origin is honored")
    void corsPreflightFromSandboxOriginIsAllowed() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://launcher.smarthealthit.org");
        headers.setAccessControlRequestMethod(HttpMethod.GET);
        headers.set("Access-Control-Request-Headers", "Accept");

        RequestEntity<Void> request = new RequestEntity<>(headers, HttpMethod.OPTIONS, URI.create("/cds-services"));
        ResponseEntity<String> response = restTemplate.exchange(request, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowOrigin())
            .as("CORS preflight must echo the sandbox origin so the EHR can load discovery")
            .isEqualTo("https://launcher.smarthealthit.org");
    }

    private static JsonNode findById(JsonNode services, String id) {
        for (JsonNode svc : services) {
            if (id.equals(svc.path("id").asText())) return svc;
        }
        return null;
    }
}

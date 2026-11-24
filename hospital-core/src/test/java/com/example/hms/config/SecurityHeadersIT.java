package com.example.hms.config;

import com.example.hms.HmsApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API's HTTP security headers, end to end through the real filter chain, and the API
 * documentation being off in production.
 *
 * <p>Runs on the {@code test} profile (H2) with the {@code springdoc.*} entries of
 * {@code application-prod.yml} applied on top: booting the whole {@code prod} profile needs
 * PostgreSQL and the production secrets, but the documentation switch is exactly those entries,
 * read from the file itself so the test follows whatever production declares.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
class SecurityHeadersIT {

    private static final String PROD_YAML = "src/main/resources/application-prod.yml";

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void productionApiDocsSwitch(DynamicPropertyRegistry registry) {
        springdocEntriesOfProd().forEach((name, value) -> registry.add(name, () -> value));
    }

    private static Map<String, Object> springdocEntriesOfProd() {
        try {
            List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("prod", new FileSystemResource(PROD_YAML));
            Map<String, Object> entries = new LinkedHashMap<>();
            for (PropertySource<?> source : sources) {
                for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                    if (name.startsWith("springdoc.")) {
                        entries.put(name, source.getProperty(name));
                    }
                }
            }
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("production declares both API documentation endpoints off")
    void productionTurnsTheApiDocsOff() {
        Map<String, Object> entries = springdocEntriesOfProd();
        assertThat(String.valueOf(entries.get("springdoc.api-docs.enabled"))).isEqualTo("false");
        assertThat(String.valueOf(entries.get("springdoc.swagger-ui.enabled"))).isEqualTo("false");
    }

    @Test
    @DisplayName("with production's settings, the OpenAPI JSON and Swagger UI are not served")
    void apiDocsAreNotFoundInProduction() {
        // The documentation paths are permitAll (dev needs them), so with the
        // endpoints off they fall through to "no such resource".
        for (String path : List.of("/v3/api-docs", "/api-docs", "/api-docs/swagger-config", "/swagger-ui/index.html")) {
            ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(404);
            assertThat(response.getBody()).as(path).doesNotContain("openapi", "swagger-ui");
        }
        // The Swagger UI entry point is not permitAll on its own, so an anonymous
        // caller is refused before routing; either way nothing is served.
        ResponseEntity<String> entry = restTemplate.getForEntity("/swagger-ui.html", String.class);
        assertThat(entry.getStatusCode().is4xxClientError()).as("/swagger-ui.html").isTrue();
        assertThat(entry.getBody()).as("/swagger-ui.html").doesNotContain("swagger-ui");
    }

    @Test
    @DisplayName("a patient-data endpoint answers with the hardened headers, once each, even refused")
    void patientEndpointCarriesSecurityHeaders() {
        assertHardened(restTemplate.getForEntity("/patients", String.class).getHeaders(), "/patients");
    }

    @Test
    @DisplayName("a public endpoint answers with the same headers, HSTS included over plain HTTP")
    void publicEndpointCarriesSecurityHeaders() {
        ResponseEntity<String> response = restTemplate.getForEntity("/cds-services", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertHardened(response.getHeaders(), "/cds-services");
    }

    private static void assertHardened(HttpHeaders headers, String path) {
        assertThat(headers.get("Strict-Transport-Security")).as(path)
            .containsExactly("max-age=31536000 ; includeSubDomains");
        assertThat(headers.get("X-Content-Type-Options")).as(path).containsExactly("nosniff");
        assertThat(headers.get("X-Frame-Options")).as(path).containsExactly("DENY");
        assertThat(headers.get("Referrer-Policy")).as(path).containsExactly("strict-origin-when-cross-origin");
        assertThat(headers.getCacheControl()).as(path).contains("no-store");
        assertThat(headers.get("Content-Security-Policy")).as(path).singleElement()
            .asString().contains("frame-ancestors 'none'");
    }
}

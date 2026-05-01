package com.example.hms.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.hms.model.integration.Dhis2AuthMode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DhisHttpClientTest {

    private RestClient restClient;
    private MockRestServiceServer server;
    private final Map<String, String> envVars = new HashMap<>();
    private DhisHttpClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        // MockRestServiceServer wires into the builder by capturing the
        // underlying RestTemplate it produces.
        this.restClient = builder.build();
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.restClient = builder.build();
        this.client = new DhisHttpClient(restClient, envVars::get);
        envVars.clear();
    }

    @Test
    @DisplayName("PAT auth mode emits 'ApiToken <secret>' Authorization header")
    void patAuthHeader() {
        envVars.put("DHIS2_TOKEN", "abc123");
        server.expect(requestTo("https://dhis2.example.org/api/dataValueSets"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "ApiToken abc123"))
            .andExpect(header("Content-Type", "application/adx+xml"))
            .andRespond(withSuccess("{\"response\":{\"importCount\":{\"imported\":1,\"ignored\":0}}}",
                MediaType.APPLICATION_JSON));

        var resp = client.postDataValueSet("https://dhis2.example.org",
            Dhis2AuthMode.PAT, "DHIS2_TOKEN", "<adx/>", UUID.randomUUID());

        assertThat(resp.httpStatus()).isEqualTo(200);
        assertThat(resp.importedCount()).isEqualTo(1);
        assertThat(resp.ignoredCount()).isZero();
        server.verify();
    }

    @Test
    @DisplayName("BASIC auth mode emits Base64-encoded user:pass header")
    void basicAuthHeader() {
        envVars.put("DHIS2_BASIC", "user:pass");
        String expected = "Basic " + Base64.getEncoder()
            .encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo("https://dhis2.example.org/api/dataValueSets"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", expected))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        var resp = client.postDataValueSet("https://dhis2.example.org",
            Dhis2AuthMode.BASIC, "DHIS2_BASIC", "<adx/>", UUID.randomUUID());

        assertThat(resp.httpStatus()).isEqualTo(200);
        server.verify();
    }

    @Test
    @DisplayName("4xx response is NOT retried")
    void fourHundredNotRetried() {
        envVars.put("DHIS2_TOKEN", "abc");
        server.expect(requestTo("https://dhis2.example.org/api/dataValueSets"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("nope"));

        var resp = client.postDataValueSet("https://dhis2.example.org",
            Dhis2AuthMode.PAT, "DHIS2_TOKEN", "<adx/>", UUID.randomUUID());

        assertThat(resp.httpStatus()).isEqualTo(401);
        server.verify(); // exactly one request
    }

    @Test
    @DisplayName("missing env var fails fast with IllegalStateException")
    void missingEnvVar() {
        // No env var set.
        assertThatThrownBy(() -> client.postDataValueSet("https://dhis2.example.org",
            Dhis2AuthMode.PAT, "MISSING_TOKEN", "<adx/>", UUID.randomUUID()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MISSING_TOKEN");
    }

    @Test
    @DisplayName("trailing slash in baseUrl is stripped")
    void trailingSlashStripped() {
        envVars.put("DHIS2_TOKEN", "x");
        server.expect(requestTo("https://dhis2.example.org/api/dataValueSets"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        client.postDataValueSet("https://dhis2.example.org/",
            Dhis2AuthMode.PAT, "DHIS2_TOKEN", "<adx/>", UUID.randomUUID());
        server.verify();
    }

    @Test
    @DisplayName("import-summary parses imported/ignored counts when present")
    void parseImportSummary() {
        envVars.put("DHIS2_TOKEN", "x");
        server.expect(requestTo("https://dhis2.example.org/api/dataValueSets"))
            .andRespond(withSuccess(
                "{\"response\":{\"importCount\":{\"imported\":5,\"ignored\":2}}}",
                MediaType.APPLICATION_JSON));

        var resp = client.postDataValueSet("https://dhis2.example.org",
            Dhis2AuthMode.PAT, "DHIS2_TOKEN", "<adx/>", UUID.randomUUID());

        assertThat(resp.importedCount()).isEqualTo(5);
        assertThat(resp.ignoredCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("5xx then 200: retries and eventually succeeds")
    void fiveHundredThenSuccess() {
        envVars.put("DHIS2_TOKEN", "x");
        server.expect(requestTo("https://dhis2.example.org/api/dataValueSets"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("upstream"));
        server.expect(requestTo("https://dhis2.example.org/api/dataValueSets"))
            .andRespond(withSuccess("{\"response\":{\"importCount\":{\"imported\":3,\"ignored\":0}}}",
                MediaType.APPLICATION_JSON));

        var resp = client.postDataValueSet("https://dhis2.example.org",
            Dhis2AuthMode.PAT, "DHIS2_TOKEN", "<adx/>", UUID.randomUUID());

        assertThat(resp.httpStatus()).isEqualTo(200);
        assertThat(resp.importedCount()).isEqualTo(3);
        server.verify();
    }

    @Test
    @DisplayName("5xx exhausts retries: returns the last status without throwing")
    void retryExhausted() {
        envVars.put("DHIS2_TOKEN", "x");
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo("https://dhis2.example.org/api/dataValueSets"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("still bad"));
        }

        var resp = client.postDataValueSet("https://dhis2.example.org",
            Dhis2AuthMode.PAT, "DHIS2_TOKEN", "<adx/>", UUID.randomUUID());

        assertThat(resp.httpStatus()).isEqualTo(502);
        server.verify(); // 3 calls, MAX_ATTEMPTS reached
    }

    @Test
    @DisplayName("non-JSON response body returns zeros without throwing")
    void nonJsonBody() {
        envVars.put("DHIS2_TOKEN", "x");
        server.expect(requestTo("https://dhis2.example.org/api/dataValueSets"))
            .andRespond(withSuccess("not json", MediaType.TEXT_PLAIN));

        var resp = client.postDataValueSet("https://dhis2.example.org",
            Dhis2AuthMode.PAT, "DHIS2_TOKEN", "<adx/>", UUID.randomUUID());

        assertThat(resp.httpStatus()).isEqualTo(200);
        assertThat(resp.importedCount()).isZero();
    }
}

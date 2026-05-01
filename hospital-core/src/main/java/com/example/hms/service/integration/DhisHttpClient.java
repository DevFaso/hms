package com.example.hms.service.integration;

import com.example.hms.model.integration.Dhis2AuthMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Outbound HTTP client for DHIS2's {@code /api/dataValueSets} endpoint.
 *
 * <p>v0 contract:
 * <ul>
 *   <li>Bound to ADX content type ({@code application/adx+xml}).</li>
 *   <li>Auth secret resolved from {@link System#getenv(String)} at call
 *       time. The DB never stores the secret value.</li>
 *   <li>Retry: up to {@link #MAX_ATTEMPTS} attempts on 5xx + IOException.
 *       4xx responses are NOT retried — they imply payload/auth issues
 *       that won't fix themselves.</li>
 *   <li>Timeouts: {@link #CONNECT_TIMEOUT_SECONDS}s connect /
 *       {@link #READ_TIMEOUT_SECONDS}s read.</li>
 * </ul>
 *
 * <p>The bean is on by default but can be turned off
 * ({@code dhis2.export.client.enabled=false}) so unit tests of the
 * orchestrator can substitute a mock.
 */
@Component
@ConditionalOnProperty(name = "dhis2.export.client.enabled",
    havingValue = "true", matchIfMissing = true)
public class DhisHttpClient {

    private static final Logger log = LoggerFactory.getLogger(DhisHttpClient.class);

    static final int MAX_ATTEMPTS = 3;
    static final int CONNECT_TIMEOUT_SECONDS = 30;
    static final int READ_TIMEOUT_SECONDS = 60;
    static final long INITIAL_BACKOFF_MS = 500L;

    private static final String DATA_VALUE_SETS_PATH = "/api/dataValueSets";
    private static final String ADX_CONTENT_TYPE = "application/adx+xml";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EnvVarResolver envVarResolver;

    @Autowired
    public DhisHttpClient(@Value("${dhis2.export.client.connect-timeout-seconds:" + CONNECT_TIMEOUT_SECONDS + "}") int connectTimeoutSeconds,
                          @Value("${dhis2.export.client.read-timeout-seconds:" + READ_TIMEOUT_SECONDS + "}") int readTimeoutSeconds) {
        this(buildDefaultClient(connectTimeoutSeconds, readTimeoutSeconds), System::getenv);
    }

    /** Test-only constructor: injects a custom RestClient + env-var resolver. */
    DhisHttpClient(RestClient restClient, EnvVarResolver envVarResolver) {
        this.restClient = restClient;
        this.envVarResolver = envVarResolver;
    }

    private static RestClient buildDefaultClient(int connectTimeoutSeconds, int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(connectTimeoutSeconds).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(readTimeoutSeconds).toMillis());
        return RestClient.builder()
            .requestFactory(factory)
            .build();
    }

    /**
     * POST an ADX payload to the configured DHIS2 instance.
     *
     * @param baseUrl            DHIS2 base URL (e.g. {@code https://dhis2.example.org})
     * @param authMode           PAT or BASIC
     * @param authSecretEnvVar   env-var name holding the secret value
     * @param adxXml             ADX 1.0 XML payload
     * @param requestId          run-level idempotency hint (echoed in response logs)
     */
    public DhisHttpResponse postDataValueSet(String baseUrl,
                                             Dhis2AuthMode authMode,
                                             String authSecretEnvVar,
                                             String adxXml,
                                             UUID requestId) {
        final String authValue = resolveAuthHeader(authMode, authSecretEnvVar);
        final URI uri = URI.create(stripTrailingSlash(baseUrl) + DATA_VALUE_SETS_PATH);

        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                final var entity = restClient.post()
                    .uri(uri)
                    .header(HttpHeaders.CONTENT_TYPE, ADX_CONTENT_TYPE)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.AUTHORIZATION, authValue)
                    .header("X-Request-Id", requestId.toString())
                    .body(adxXml)
                    .retrieve()
                    .toEntity(String.class);

                return summarise(entity.getStatusCode().value(), entity.getBody());
            } catch (HttpStatusCodeException e) {
                final int status = e.getStatusCode().value();
                if (status >= 400 && status < 500) {
                    log.warn("DHIS2 export rejected by server (status {}); not retrying. requestId={}",
                        status, requestId);
                    return summarise(status, e.getResponseBodyAsString());
                }
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("DHIS2 export failed after {} attempts (status {}); requestId={}",
                        attempt, status, requestId);
                    return summarise(status, e.getResponseBodyAsString());
                }
                log.info("DHIS2 export attempt {} got status {}; retrying in {}ms requestId={}",
                    attempt, status, backoff, requestId);
            } catch (ResourceAccessException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("DHIS2 export failed after {} attempts (network); requestId={} error={}",
                        attempt, requestId, e.getMostSpecificCause().getMessage());
                    return new DhisHttpResponse(0, 0, 0, e.getMostSpecificCause().getMessage());
                }
                log.info("DHIS2 export attempt {} hit network error; retrying in {}ms requestId={}",
                    attempt, backoff, requestId);
            }
            sleep(backoff);
            backoff *= 2;
        }
        // Unreachable — every loop iteration either returns or throws.
        throw new IllegalStateException("DHIS2 retry loop exited without a response");
    }

    private DhisHttpResponse summarise(int status, String body) {
        if (body == null || body.isBlank()) {
            return new DhisHttpResponse(status, 0, 0, body == null ? "" : body);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode summary = root.path("response");
            if (summary.isMissingNode()) {
                summary = root;
            }
            int imported = summary.path("importCount").path("imported").asInt(0);
            int ignored = summary.path("importCount").path("ignored").asInt(0);
            return new DhisHttpResponse(status, imported, ignored, body);
        } catch (IOException e) {
            log.info("DHIS2 import-summary not parseable as JSON (status {}); body length={}",
                status, body.length());
            return new DhisHttpResponse(status, 0, 0, body);
        }
    }

    private String resolveAuthHeader(Dhis2AuthMode mode, String envVar) {
        final String secret = envVarResolver.get(envVar);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "DHIS2 auth secret env var '" + envVar + "' is not set");
        }
        return switch (mode) {
            case PAT -> "ApiToken " + secret;
            case BASIC -> "Basic " + Base64.getEncoder()
                .encodeToString(secret.getBytes(StandardCharsets.UTF_8));
        };
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during DHIS2 retry backoff", e);
        }
    }

    /**
     * Indirection over {@link System#getenv} so unit tests can stub
     * the resolver without touching real environment variables.
     */
    @FunctionalInterface
    public interface EnvVarResolver {
        String get(String name);
    }
}

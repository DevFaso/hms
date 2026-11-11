package com.example.hms.service.webhook;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.service.support.OutboundUrlGuard;
import com.example.hms.service.support.PublicAddressDnsResolver;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;

/**
 * The HTTP leg of a webhook delivery (Tier 2 item 45) — the IKODDI
 * RestClient idiom: explicit connect/read timeouts, one component owning
 * the wire call so the dispatch service stays testable without a server.
 *
 * <p>The URL was checked when it was registered, but a name can answer with
 * a public address then and a private one now (DNS rebinding). So every
 * delivery re-checks the URL, and the client resolves through
 * {@link PublicAddressDnsResolver}: the address it connects to is the one
 * that was checked, in the same lookup. No proxy from the system properties
 * (a proxy would do its own lookup), no redirects (a 30x could point inside),
 * no automatic retries (one attempt per claim).
 */
@Component
@Slf4j
public class WebhookDeliveryTransport {

    /**
     * What one attempt produced: an HTTP status, or a transport error.
     * {@code refused} means nothing was sent because the endpoint is not a
     * public HTTPS address (any more); retrying will not help.
     */
    public record Result(Integer httpStatus, String error, boolean refused) {

        public Result(Integer httpStatus, String error) {
            this(httpStatus, error, false);
        }

        static Result refusedBeforeSending(String reason) {
            return new Result(null, "Refused before sending: " + reason, true);
        }

        public boolean success() {
            return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
        }
    }

    private final RestClient restClient;
    private final CloseableHttpClient httpClient;

    // Two constructors exist (the package-private one is for tests), so
    // Spring needs the pick made explicit.
    @org.springframework.beans.factory.annotation.Autowired
    public WebhookDeliveryTransport(WebhookProperties properties) {
        this(properties, SystemDefaultDnsResolver.INSTANCE);
    }

    /** Package-private for tests: {@code dns} answers the lookups in place of the system resolver. */
    WebhookDeliveryTransport(WebhookProperties properties, DnsResolver dns) {
        Timeout connect = Timeout.ofMilliseconds(properties.getConnectTimeoutMs());
        Timeout read = Timeout.ofMilliseconds(properties.getReadTimeoutMs());
        HttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(new PublicAddressDnsResolver(dns))
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(connect)
                .setSocketTimeout(read)
                .build())
            .build();
        this.httpClient = HttpClients.custom()
            .setConnectionManager(connections)
            .setDefaultRequestConfig(RequestConfig.custom()
                .setResponseTimeout(read)
                .setRedirectsEnabled(false)
                .build())
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .build();
        this.restClient = RestClient.builder()
            .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
            .build();
    }

    @PreDestroy
    void close() throws IOException {
        httpClient.close();
    }

    public Result post(String url, String body, String signature, long timestamp,
                       String eventType, String deliveryId) {
        try {
            // The checks that need no lookup; the address itself is checked
            // by the client's own lookup, below.
            OutboundUrlGuard.requirePublicHttpsShape(url, "url", "webhook.url");
        } catch (FieldBusinessException ex) {
            log.warn("Webhook delivery {} refused: the endpoint URL is not a public HTTPS URL", deliveryId);
            return Result.refusedBeforeSending(ex.getMessage());
        }
        try {
            ResponseEntity<Void> response = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header(WebhookSigner.SIGNATURE_HEADER, signature)
                .header(WebhookSigner.TIMESTAMP_HEADER, String.valueOf(timestamp))
                .header(WebhookSigner.EVENT_HEADER, eventType)
                .header(WebhookSigner.DELIVERY_HEADER, deliveryId)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (req, res) -> {
                    // Status handling happens in the caller - swallowing
                    // here keeps 4xx/5xx from throwing.
                })
                .toBodilessEntity();
            return new Result(response.getStatusCode().value(), null);
        } catch (RuntimeException ex) {
            OutboundUrlGuard.NonPublicAddressException nonPublic = nonPublicCause(ex);
            if (nonPublic != null) {
                log.warn("Webhook delivery {} refused: the endpoint host resolves to a non-public address", deliveryId);
                return Result.refusedBeforeSending(nonPublic.getMessage());
            }
            return new Result(null, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static OutboundUrlGuard.NonPublicAddressException nonPublicCause(Throwable ex) {
        Throwable cause = ex;
        for (int depth = 0; cause != null && depth < 10; depth++) {
            if (cause instanceof OutboundUrlGuard.NonPublicAddressException nonPublic) {
                return nonPublic;
            }
            cause = cause.getCause();
        }
        return null;
    }
}

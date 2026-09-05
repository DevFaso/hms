package com.example.hms.service.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The HTTP leg of a webhook delivery (Tier 2 item 45) — the IKODDI
 * RestClient idiom: explicit connect/read timeouts, one component owning
 * the wire call so the dispatch service stays testable without a server.
 */
@Component
@Slf4j
public class WebhookDeliveryTransport {

    /** What one attempt produced: an HTTP status, or a transport error. */
    public record Result(Integer httpStatus, String error) {
        public boolean success() {
            return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
        }
    }

    private final RestClient restClient;

    // Two constructors exist (the package-private one is for tests), so
    // Spring needs the pick made explicit.
    @org.springframework.beans.factory.annotation.Autowired
    public WebhookDeliveryTransport(WebhookProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** Package-private for tests (the IKODDI/MockRestServiceServer pattern). */
    WebhookDeliveryTransport(RestClient restClient) {
        this.restClient = restClient;
    }

    public Result post(String url, String body, String signature, long timestamp,
                       String eventType, String deliveryId) {
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
            return new Result(null, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }
}

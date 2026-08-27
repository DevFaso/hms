package com.example.hms.imaging.dicom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * HTTP-based {@link DicomWebClient} bridging HMS to an upstream
 * DICOMweb server (Orthanc or dcm4chee). Roadmap row 42 follow-on.
 *
 * <p>Activated by {@code app.imaging.dicom-proxy.enabled=true}.
 * Upstream readiness additionally requires
 * {@code app.imaging.dicom-proxy.base-url} to be non-blank — that
 * second condition is enforced at request time by {@link #isReady()}
 * rather than in the bean activation condition, so a deployment that
 * flips the master flag without configuring an upstream still starts
 * cleanly (the bean is created but every QIDO/WADO call short-circuits
 * to an empty result and {@link DicomProxyService} degrades into the
 * foundation-pass audit-only path).
 *
 * <p>QIDO-RS responses are parsed for the SOPInstanceUID tag (DICOM
 * group/element 0008,0018). Both Orthanc and dcm4chee return the
 * same RFC-3881 DICOMweb JSON shape: an array of resource objects
 * keyed by hex-coded tag with a {@code Value} array inside. We do
 * not pull in a DICOM library — the parse is two field lookups on
 * a Jackson tree.
 *
 * <p>Per-call try/catch: an upstream 5xx or connect timeout returns
 * an empty result rather than throwing, so the calling HMS request
 * surfaces as "no instances available" instead of 500. The error
 * is logged at WARN so an operator can correlate against the
 * upstream's own metrics.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.imaging.dicom-proxy",
    name = "enabled",
    havingValue = "true"
)
public class DicomWebHttpClient implements DicomWebClient {

    private static final Logger log = LoggerFactory.getLogger(DicomWebHttpClient.class);
    /** DICOM tag for SOPInstanceUID — the per-instance UID QIDO-RS returns. */
    private static final String SOP_INSTANCE_UID_TAG = "00080018";
    /** Sentinel returned when the upstream has no payload to deliver. */
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final DicomProxyProperties properties;
    private final RestClient restClient;

    // Two constructors exist (the second is the package-private seam the
    // tests inject a RestClient through), so Spring has no way to pick one
    // and would fail to start this bean. It has never bitten because the
    // whole component is behind @ConditionalOnProperty on the dicom-proxy
    // flag — latent, not harmless.
    @org.springframework.beans.factory.annotation.Autowired
    public DicomWebHttpClient(DicomProxyProperties properties) {
        this(properties, RestClient.builder().requestFactory(buildRequestFactory()).build());
    }

    /**
     * Package-private hook so unit tests can wire in a
     * {@link RestClient} backed by Spring's
     * {@code MockRestServiceServer}. Production code goes through
     * the public constructor and never sees this.
     */
    DicomWebHttpClient(DicomProxyProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    /**
     * Per-request timeouts: 5s connect / 30s read. The upstream PACS
     * can be slow on cold-cache reads of large studies; 30s is the
     * conservative upper bound the clinical UI tolerates before the
     * operator gets a "still loading" toast.
     */
    private static SimpleClientHttpRequestFactory buildRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        return factory;
    }

    @Override
    public List<String> qidoListInstances(String studyUid) {
        if (!isReady() || studyUid == null || studyUid.isBlank()) {
            return Collections.emptyList();
        }
        String url = baseUrl() + "/studies/" + studyUid + "/instances";
        try {
            List<Map<String, Object>> body = restClient.get()
                .uri(url)
                .accept(MediaType.parseMediaType("application/dicom+json"), MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    // 404 is "study has no instances" — surface as empty
                    // rather than throwing. Other 4xx surfaces as the
                    // exception path the catch below renders as warn.
                    if (res.getStatusCode().value() != 404) {
                        throw new HttpClientErrorException(res.getStatusCode(),
                            "upstream DICOMweb rejected QIDO-RS: " + res.getStatusCode());
                    }
                })
                .body(new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {});
            if (body == null) return Collections.emptyList();
            return extractInstanceUids(body);
        } catch (RuntimeException ex) {
            log.warn("DICOMweb QIDO-RS failed for study {}: {}", studyUid, ex.toString());
            return Collections.emptyList();
        }
    }

    @Override
    public byte[] wadoFetchInstance(String studyUid, String instanceUid) {
        if (!isReady() || studyUid == null || studyUid.isBlank()
            || instanceUid == null || instanceUid.isBlank()) {
            return EMPTY_BYTES;
        }
        String url = baseUrl() + "/studies/" + studyUid + "/instances/" + instanceUid;
        try {
            byte[] body = restClient.get()
                .uri(url)
                .accept(MediaType.parseMediaType("application/dicom"))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    if (res.getStatusCode().value() == 404) {
                        // Sentinel — converted to empty below via the
                        // throwing-but-caught path. Cleaner than a
                        // mutable holder.
                        throw new InstanceNotFoundSentinel();
                    }
                    throw new HttpClientErrorException(res.getStatusCode(),
                        "upstream DICOMweb rejected WADO-RS: " + res.getStatusCode());
                })
                .body(byte[].class);
            return body == null ? EMPTY_BYTES : body;
        } catch (InstanceNotFoundSentinel notFound) {
            return EMPTY_BYTES;
        } catch (RuntimeException ex) {
            log.warn("DICOMweb WADO-RS failed for {}/{}: {}", studyUid, instanceUid, ex.toString());
            return EMPTY_BYTES;
        }
    }

    /**
     * Walks the QIDO-RS JSON array and pulls the SOPInstanceUID
     * {@code Value[0]} from each entry. Defensive against missing
     * fields — a malformed upstream entry yields a null which the
     * caller filters out, so the loop has a single early-exit
     * (the implicit end-of-iteration) rather than multiple
     * {@code continue} statements.
     */
    private static List<String> extractInstanceUids(List<Map<String, Object>> body) {
        List<String> out = new ArrayList<>(body.size());
        for (Map<String, Object> entry : body) {
            String uid = extractInstanceUid(entry);
            if (uid != null) {
                out.add(uid);
            }
        }
        return out;
    }

    private static String extractInstanceUid(Map<String, Object> entry) {
        Object tagObj = entry.get(SOP_INSTANCE_UID_TAG);
        if (!(tagObj instanceof Map<?, ?> tag)) return null;
        Object valueArr = tag.get("Value");
        if (!(valueArr instanceof List<?> values) || values.isEmpty()) return null;
        Object first = values.get(0);
        return first == null ? null : first.toString();
    }

    private boolean isReady() {
        return properties.isEnabled()
            && properties.getBaseUrl() != null
            && !properties.getBaseUrl().isBlank();
    }

    private String baseUrl() {
        // Normalise to no-trailing-slash so the path concat below is
        // unambiguous regardless of whether the operator configured the
        // URL with or without the trailing /.
        String raw = properties.getBaseUrl().trim();
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    /** Marker exception used to short-circuit the WADO-RS 404 path. */
    private static final class InstanceNotFoundSentinel extends RuntimeException {
        InstanceNotFoundSentinel() {
            super("upstream returned 404", null, false, false);
        }
    }
}

package com.example.hms.imaging.dicom;

import com.example.hms.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The DICOMweb calls the in-app viewer makes, against whichever PACS
 * {@link PacsConnectionResolver} chose for the hospital (its own, V317, or the
 * platform fallback).
 *
 * <ul>
 *   <li>QIDO-RS {@code /studies/{study}/instances}: the manifest;</li>
 *   <li>QIDO-RS {@code /studies?AccessionNumber=}: linking a report by accession;</li>
 *   <li>WADO-RS {@code .../frames/{n}/rendered}: one JPEG/PNG frame. The
 *       browser renders an image, so no DICOM parser or viewer bundle ships
 *       in the portal;</li>
 *   <li>QIDO-RS {@code /studies?limit=1}: the admin's connection test.</li>
 * </ul>
 *
 * <p>Safety: every UID is checked by {@link DicomUid} before it reaches a path;
 * the URL is checked by {@link PacsUrlPolicy} before every call; redirects are
 * never followed; bodies are size-capped. Failures surface as
 * {@link PacsUnavailableException} with a fixed reason, and the log line names
 * the reason and status only, never a UID, an accession or an upstream body.
 */
@Component
public class PacsDicomWebGateway {

    private static final Logger log = LoggerFactory.getLogger(PacsDicomWebGateway.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A QIDO answer bigger than this is not a study manifest. */
    static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    /** A rendered frame bigger than this is refused rather than buffered. */
    static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;
    static final int MAX_FRAME_NUMBER = 10_000;

    private static final String PARAM_LIMIT = "limit";
    private static final MediaType DICOM_JSON = MediaType.parseMediaType("application/dicom+json");
    private static final String TAG_SERIES_UID = "0020000E";
    private static final String TAG_SOP_UID = "00080018";
    private static final String TAG_STUDY_UID = "0020000D";
    private static final String TAG_SERIES_NUMBER = "00200011";
    private static final String TAG_INSTANCE_NUMBER = "00200013";
    private static final String TAG_FRAMES = "00280008";
    private static final String TAG_MODALITY = "00080060";

    private final DicomProxyProperties properties;
    private final RestClient restClient;

    public PacsDicomWebGateway(DicomProxyProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().requestFactory(noRedirectFactory()).build();
    }

    /**
     * 5 s connect / 30 s read, as the legacy client; never follows a redirect,
     * which could otherwise lead a vetted public URL to an internal one.
     */
    private static SimpleClientHttpRequestFactory noRedirectFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        return factory;
    }

    /** The instances of a study, sorted by series then instance number; empty when the PACS has none. */
    public List<DicomInstanceRef> studyInstances(PacsConnection connection, String studyUid) {
        requireUid(studyUid);
        URI uri = UriComponentsBuilder.fromUriString(connection.baseUrl())
            .path("/studies/" + studyUid + "/instances")
            .queryParam("includefield", TAG_SERIES_UID, TAG_SERIES_NUMBER, TAG_INSTANCE_NUMBER, TAG_FRAMES,
                TAG_MODALITY)
            .queryParam(PARAM_LIMIT, Math.max(1, properties.getMaxInstances()))
            .build(true).toUri();
        JsonNode root = getJson(connection, uri);
        List<DicomInstanceRef> out = new ArrayList<>();
        for (JsonNode entry : root) {
            String sop = text(entry, TAG_SOP_UID);
            String series = text(entry, TAG_SERIES_UID);
            if (DicomUid.isValid(sop) && DicomUid.isValid(series)) {
                Integer frames = integer(entry, TAG_FRAMES);
                out.add(new DicomInstanceRef(series, sop, integer(entry, TAG_SERIES_NUMBER),
                    integer(entry, TAG_INSTANCE_NUMBER), frames == null || frames < 1 ? 1 : frames,
                    text(entry, TAG_MODALITY)));
            }
        }
        out.sort(Comparator
            .comparing(DicomInstanceRef::seriesNumber, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(DicomInstanceRef::seriesInstanceUid)
            .thenComparing(DicomInstanceRef::instanceNumber, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    /** The study UIDs the PACS holds under this accession number (0, 1, or several). */
    public List<String> studyUidsByAccession(PacsConnection connection, String accessionNumber) {
        URI uri = UriComponentsBuilder.fromUriString(connection.baseUrl())
            .path("/studies")
            .queryParam("AccessionNumber", "{accession}")
            .queryParam("includefield", TAG_STUDY_UID)
            .queryParam(PARAM_LIMIT, 5)
            // Template variable + encode(): the value is fully encoded, so an
            // accession number cannot add a query parameter of its own (&, =).
            .encode()
            .buildAndExpand(accessionNumber).toUri();
        JsonNode root = getJson(connection, uri);
        List<String> out = new ArrayList<>();
        for (JsonNode entry : root) {
            String uid = text(entry, TAG_STUDY_UID);
            if (DicomUid.isValid(uid) && !out.contains(uid)) {
                out.add(uid);
            }
        }
        return out;
    }

    /** One rendered frame; empty when the PACS has no such instance or frame. */
    public Optional<RenderedFrame> renderedFrame(PacsConnection connection, String studyUid, String seriesUid,
                                                 String instanceUid, int frame) {
        requireUid(studyUid);
        requireUid(seriesUid);
        requireUid(instanceUid);
        if (frame < 1 || frame > MAX_FRAME_NUMBER) {
            throw new BusinessException("imaging.pacs.frame.invalid");
        }
        URI uri = UriComponentsBuilder.fromUriString(connection.baseUrl())
            .path("/studies/" + studyUid + "/series/" + seriesUid + "/instances/" + instanceUid
                + "/frames/" + frame + "/rendered")
            .build(true).toUri();
        PacsUrlPolicy.requireCallable(connection.baseUrl(), properties.isAllowPrivateHosts());
        try {
            return restClient.get().uri(uri)
                .headers(h -> authenticate(h, connection))
                .accept(MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 404) {
                        return Optional.<RenderedFrame>empty();
                    }
                    failOnStatus(status, "rendered");
                    MediaType type = response.getHeaders().getContentType();
                    if (type == null || !(MediaType.IMAGE_JPEG.includes(type) || MediaType.IMAGE_PNG.includes(type))) {
                        log.warn("[pacs] rendered frame answered with a non-image type");
                        throw new PacsUnavailableException(PacsUnavailableException.Reason.REJECTED);
                    }
                    byte[] bytes = readCapped(response.getBody(), MAX_FRAME_BYTES);
                    String contentType = MediaType.IMAGE_PNG.includes(type)
                        ? MediaType.IMAGE_PNG_VALUE : MediaType.IMAGE_JPEG_VALUE;
                    return Optional.of(new RenderedFrame(bytes, contentType));
                });
        } catch (PacsUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("[pacs] rendered frame call failed: {}", ex.getClass().getSimpleName());
            throw new PacsUnavailableException(PacsUnavailableException.Reason.UNREACHABLE);
        }
    }

    /** The admin's « test the connection »: one QIDO study query. Throws when it fails. */
    public void ping(PacsConnection connection) {
        URI uri = UriComponentsBuilder.fromUriString(connection.baseUrl())
            .path("/studies").queryParam(PARAM_LIMIT, 1).build(true).toUri();
        getJson(connection, uri);
    }

    private JsonNode getJson(PacsConnection connection, URI uri) {
        PacsUrlPolicy.requireCallable(connection.baseUrl(), properties.isAllowPrivateHosts());
        try {
            return restClient.get().uri(uri)
                .headers(h -> authenticate(h, connection))
                .accept(DICOM_JSON, MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    // 204: no match; 404: no such study. Both are an empty answer.
                    if (status == 204 || status == 404) {
                        return JSON.createArrayNode();
                    }
                    failOnStatus(status, "qido");
                    byte[] body = readCapped(response.getBody(), MAX_JSON_BYTES);
                    if (body.length == 0) {
                        return JSON.createArrayNode();
                    }
                    JsonNode root = JSON.readTree(body);
                    if (!root.isArray()) {
                        log.warn("[pacs] QIDO answered with something other than a JSON array");
                        throw new PacsUnavailableException(PacsUnavailableException.Reason.REJECTED);
                    }
                    return root;
                });
        } catch (PacsUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("[pacs] QIDO call failed: {}", ex.getClass().getSimpleName());
            throw new PacsUnavailableException(PacsUnavailableException.Reason.UNREACHABLE);
        }
    }

    private static void failOnStatus(int status, String call) {
        if (status >= 200 && status < 300) {
            return;
        }
        log.warn("[pacs] {} call answered HTTP {}", call, status);
        if (status == 401 || status == 403) {
            throw new PacsUnavailableException(PacsUnavailableException.Reason.UNAUTHORIZED);
        }
        throw new PacsUnavailableException(status >= 500
            ? PacsUnavailableException.Reason.UNREACHABLE : PacsUnavailableException.Reason.REJECTED);
    }

    private static void authenticate(HttpHeaders headers, PacsConnection connection) {
        if (connection.hasCredentials()) {
            String password = connection.password() == null ? "" : connection.password();
            String token = Base64.getEncoder().encodeToString(
                (connection.username() + ":" + password).getBytes(StandardCharsets.UTF_8));
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + token);
        }
    }

    private static byte[] readCapped(InputStream in, int max) {
        try (InputStream body = in) {
            byte[] bytes = body.readNBytes(max + 1);
            if (bytes.length > max) {
                log.warn("[pacs] upstream answer exceeded {} bytes", max);
                throw new PacsUnavailableException(PacsUnavailableException.Reason.REJECTED);
            }
            return bytes;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void requireUid(String uid) {
        if (!DicomUid.isValid(uid)) {
            throw new BusinessException("imaging.pacs.uid.invalid");
        }
    }

    private static JsonNode firstValue(JsonNode entry, String tag) {
        JsonNode values = entry.path(tag).path("Value");
        return values.isArray() && !values.isEmpty() ? values.get(0) : null;
    }

    private static String text(JsonNode entry, String tag) {
        JsonNode value = firstValue(entry, tag);
        return value == null || value.isNull() ? null : value.asString().trim().toUpperCase(Locale.ROOT);
    }

    private static Integer integer(JsonNode entry, String tag) {
        JsonNode value = firstValue(entry, tag);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        try {
            return Integer.valueOf(value.asString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

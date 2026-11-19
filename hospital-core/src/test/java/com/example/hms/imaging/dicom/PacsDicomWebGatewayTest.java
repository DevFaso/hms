package com.example.hms.imaging.dicom;

import com.example.hms.exception.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The viewer's DICOMweb calls against a real HTTP server standing in for
 * Orthanc's {@code /dicom-web} (JDK HttpServer: real sockets, real headers,
 * no extra test dependency).
 */
class PacsDicomWebGatewayTest {

    private static final String STUDY = "1.2.840.113619.2.55.3";
    private static final String SERIES = "1.2.840.113619.2.55.3.1";
    private static final String SOP_1 = "1.2.840.113619.2.55.3.1.1";
    private static final String SOP_2 = "1.2.840.113619.2.55.3.1.2";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    private HttpServer server;
    private String baseUrl;
    private DicomProxyProperties properties;
    private PacsDicomWebGateway gateway;
    /** path+query → (status, content type, body) */
    private final Map<String, Object[]> routes = new ConcurrentHashMap<>();
    private final List<String> seenAuthorization = new ArrayList<>();
    private final List<String> seenRequests = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/dicom-web";
        properties = new DicomProxyProperties();
        properties.setEnabled(true);
        properties.setAllowPrivateHosts(true);
        gateway = new PacsDicomWebGateway(properties);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestURI().getRawPath()
            + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
        synchronized (seenRequests) {
            seenRequests.add(key);
            seenAuthorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
        }
        Object[] route = routes.entrySet().stream()
            .filter(e -> key.startsWith(e.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(new Object[] {404, "text/plain", "missing".getBytes(StandardCharsets.UTF_8)});
        if (route[1] != null) {
            exchange.getResponseHeaders().set("Content-Type", (String) route[1]);
        }
        if (route.length > 3) {
            exchange.getResponseHeaders().set("Location", (String) route[3]);
        }
        byte[] body = (byte[]) route[2];
        exchange.sendResponseHeaders((Integer) route[0], body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void route(String pathPrefix, int status, String type, String body) {
        routes.put(pathPrefix, new Object[] {status, type, body.getBytes(StandardCharsets.UTF_8)});
    }

    private PacsConnection hospitalPacs(String user, String password) {
        return new PacsConnection(baseUrl, user, password, PacsConnection.Source.HOSPITAL);
    }

    private static String instanceJson(String series, Integer seriesNo, String sop, Integer instanceNo, String frames) {
        return "{\"0020000E\":{\"vr\":\"UI\",\"Value\":[\"" + series + "\"]},"
            + "\"00200011\":{\"vr\":\"IS\",\"Value\":[" + seriesNo + "]},"
            + "\"00080018\":{\"vr\":\"UI\",\"Value\":[\"" + sop + "\"]},"
            + "\"00200013\":{\"vr\":\"IS\",\"Value\":[" + instanceNo + "]},"
            + (frames == null ? "" : "\"00280008\":{\"vr\":\"IS\",\"Value\":[\"" + frames + "\"]},")
            + "\"00080060\":{\"vr\":\"CS\",\"Value\":[\"CR\"]}}";
    }

    @Test
    @DisplayName("QIDO manifest: instances parsed, sorted by instance number, frames defaulted, basic auth sent")
    void manifestParsedAndAuthenticated() {
        route("/dicom-web/studies/" + STUDY + "/instances", 200, "application/dicom+json",
            "[" + instanceJson(SERIES, 1, SOP_2, 2, "3") + "," + instanceJson(SERIES, 1, SOP_1, 1, null)
                + ",{\"00080018\":{\"vr\":\"UI\",\"Value\":[\"not/a/uid\"]}}]");

        List<DicomInstanceRef> refs = gateway.studyInstances(hospitalPacs("orthanc", "s3cret"), STUDY);

        assertThat(refs).extracting(DicomInstanceRef::sopInstanceUid).containsExactly(SOP_1, SOP_2);
        assertThat(refs.get(0).numberOfFrames()).isEqualTo(1);
        assertThat(refs.get(1).numberOfFrames()).isEqualTo(3);
        assertThat(refs.get(0).modality()).isEqualTo("CR");
        assertThat(seenAuthorization.get(0)).isEqualTo("Basic "
            + Base64.getEncoder().encodeToString("orthanc:s3cret".getBytes(StandardCharsets.UTF_8)));
        assertThat(seenRequests.get(0)).contains("includefield=").contains("limit=500");
    }

    @Test
    @DisplayName("no credentials: no Authorization header; 404 and 204 are an empty manifest")
    void emptyAnswers() {
        route("/dicom-web/studies/" + STUDY + "/instances", 204, null, "");
        assertThat(gateway.studyInstances(hospitalPacs("", ""), STUDY)).isEmpty();
        assertThat(seenAuthorization.get(0)).isNull();
        assertThat(gateway.studyInstances(hospitalPacs("", ""), "1.2.3.999")).isEmpty();
    }

    @Test
    @DisplayName("accession lookup is URL-encoded and returns each study UID once")
    void accessionLookup() {
        route("/dicom-web/studies?AccessionNumber=", 200, "application/dicom+json",
            "[{\"0020000D\":{\"vr\":\"UI\",\"Value\":[\"" + STUDY + "\"]}},"
                + "{\"0020000D\":{\"vr\":\"UI\",\"Value\":[\"" + STUDY + "\"]}}]");

        List<String> studies = gateway.studyUidsByAccession(hospitalPacs("", ""), "ACC 7&x=1");

        assertThat(studies).containsExactly(STUDY);
        assertThat(seenRequests.get(0)).startsWith("/dicom-web/studies?AccessionNumber=ACC%207%26x%3D1&");
    }

    @Test
    @DisplayName("rendered frame: the JPEG bytes and type come back; a missing frame is empty")
    void renderedFrame() {
        routes.put("/dicom-web/studies/" + STUDY + "/series/" + SERIES + "/instances/" + SOP_1 + "/frames/1/rendered",
            new Object[] {200, "image/jpeg", JPEG});

        Optional<RenderedFrame> frame = gateway.renderedFrame(hospitalPacs("", ""), STUDY, SERIES, SOP_1, 1);

        assertThat(frame).isPresent();
        assertThat(frame.get().bytes()).isEqualTo(JPEG);
        assertThat(frame.get().contentType()).isEqualTo("image/jpeg");
        assertThat(gateway.renderedFrame(hospitalPacs("", ""), STUDY, SERIES, SOP_2, 1)).isEmpty();
    }

    @Test
    @DisplayName("a rendered answer that is not an image is refused, never relayed")
    void nonImageRefused() {
        route("/dicom-web/studies/" + STUDY + "/series/", 200, "text/html", "<script>alert(1)</script>");
        PacsConnection noAuthPacs = hospitalPacs("", "");
        assertThatThrownBy(() -> gateway.renderedFrame(noAuthPacs, STUDY, SERIES, SOP_1, 1))
            .isInstanceOfSatisfying(PacsUnavailableException.class,
                ex -> assertThat(ex.reason()).isEqualTo(PacsUnavailableException.Reason.REJECTED));
    }

    @Test
    @DisplayName("401 from the PACS is UNAUTHORIZED, 500 is UNREACHABLE; the exception never carries the body")
    void upstreamFailures() {
        route("/dicom-web/studies/" + STUDY + "/instances", 401, "text/plain", "patient DOE JOHN");
        PacsConnection wrongPassword = hospitalPacs("u", "wrong");
        assertThatThrownBy(() -> gateway.studyInstances(wrongPassword, STUDY))
            .isInstanceOfSatisfying(PacsUnavailableException.class, ex -> {
                assertThat(ex.reason()).isEqualTo(PacsUnavailableException.Reason.UNAUTHORIZED);
                assertThat(ex.getMessage()).doesNotContain("DOE");
            });
        route("/dicom-web/studies?limit=1", 500, "text/plain", "boom");
        PacsConnection noAuth = hospitalPacs("", "");
        assertThatThrownBy(() -> gateway.ping(noAuth))
            .isInstanceOfSatisfying(PacsUnavailableException.class,
                ex -> assertThat(ex.reason()).isEqualTo(PacsUnavailableException.Reason.UNREACHABLE));
    }

    @Test
    @DisplayName("a redirect is never followed (it could lead a vetted URL to an internal one)")
    void redirectNotFollowed() {
        routes.put("/dicom-web/studies?limit=1", new Object[] {302, "text/plain", new byte[0],
            "http://169.254.169.254/latest/meta-data"});
        PacsConnection pacs = hospitalPacs("", "");
        assertThatThrownBy(() -> gateway.ping(pacs))
            .isInstanceOf(PacsUnavailableException.class);
        assertThat(seenRequests).hasSize(1);
    }

    @Test
    @DisplayName("a value that is not a DICOM UID never reaches the PACS")
    void uidValidatedBeforeTheCall() {
        PacsConnection pacs = hospitalPacs("", "");
        assertThatThrownBy(() -> gateway.studyInstances(pacs, "../../tools/reset"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gateway.renderedFrame(pacs, STUDY, SERIES, "1.2?x=1", 1))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gateway.renderedFrame(pacs, STUDY, SERIES, SOP_1, 0))
            .isInstanceOf(BusinessException.class);
        assertThat(seenRequests).isEmpty();
    }

    @Test
    @DisplayName("with private hosts refused (production), a loopback PACS is never called")
    void privateHostRefusedInProduction() {
        properties.setAllowPrivateHosts(false);
        PacsConnection production = hospitalPacs("", "");
        assertThatThrownBy(() -> gateway.ping(production))
            .isInstanceOf(PacsUrlPolicy.RejectedException.class);
        assertThat(seenRequests).isEmpty();
    }
}

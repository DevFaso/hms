package com.example.hms.imaging.dicom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link DicomWebHttpClient}. Exercises the QIDO-RS /
 * WADO-RS bridges against a {@link MockRestServiceServer} so the
 * parse-and-fallback contract is pinned without a real PACS:
 *
 * <ul>
 *   <li>QIDO-RS happy path — 200 + valid JSON → list of SOPInstanceUIDs;</li>
 *   <li>QIDO-RS 404 → empty list (no exception bubble);</li>
 *   <li>QIDO-RS 5xx → empty list + log;</li>
 *   <li>WADO-RS happy path — 200 + bytes → returned as-is;</li>
 *   <li>WADO-RS 404 → empty byte[] (the sentinel that replaced the
 *       old {@code null}-return contract per Sonar S1168);</li>
 *   <li>readiness short-circuit — flag off OR blank base-url →
 *       empty result, no upstream call;</li>
 *   <li>malformed QIDO-RS entry — missing field → entry skipped, not
 *       propagated as an exception.</li>
 * </ul>
 */
class DicomWebHttpClientTest {

    private static final String BASE_URL = "https://pacs.example.org/dicom-web";

    private MockRestServiceServer server;
    private DicomProxyProperties properties;
    private DicomWebHttpClient client;

    @BeforeEach
    void setUp() {
        properties = new DicomProxyProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(BASE_URL);

        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        this.client = new DicomWebHttpClient(properties, restClient);
    }

    @Test
    @DisplayName("qidoListInstances parses SOPInstanceUID values from a well-formed QIDO-RS response")
    void qidoHappyPath() {
        server.expect(requestTo(BASE_URL + "/studies/STUDY-1/instances"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                "[{\"00080018\":{\"vr\":\"UI\",\"Value\":[\"1.2.3.4.A\"]}}," +
                "{\"00080018\":{\"vr\":\"UI\",\"Value\":[\"1.2.3.4.B\"]}}]",
                MediaType.parseMediaType("application/dicom+json")));

        List<String> result = client.qidoListInstances("STUDY-1");

        assertThat(result).containsExactly("1.2.3.4.A", "1.2.3.4.B");
    }

    @Test
    @DisplayName("qidoListInstances returns empty when the upstream answers 404")
    void qido404IsEmpty() {
        server.expect(requestTo(BASE_URL + "/studies/STUDY-2/instances"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.qidoListInstances("STUDY-2")).isEmpty();
    }

    @Test
    @DisplayName("qidoListInstances returns empty (not throwing) when the upstream answers 5xx")
    void qido5xxIsEmpty() {
        server.expect(requestTo(BASE_URL + "/studies/STUDY-3/instances"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(client.qidoListInstances("STUDY-3")).isEmpty();
    }

    @Test
    @DisplayName("qidoListInstances skips malformed entries (missing tag / Value) instead of throwing")
    void qidoSkipsMalformedEntries() {
        server.expect(requestTo(BASE_URL + "/studies/STUDY-4/instances"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                "[" +
                "{\"00080018\":{\"vr\":\"UI\",\"Value\":[\"1.2.3.4.GOOD\"]}}," +
                "{\"00080018\":\"not-a-map\"}," +                              // tag is not a Map
                "{\"00080018\":{\"vr\":\"UI\"}}," +                            // missing Value
                "{\"00080018\":{\"vr\":\"UI\",\"Value\":[]}}," +               // empty Value array
                "{\"00080018\":{\"vr\":\"UI\",\"Value\":[null]}}" +            // null inside Value
                "]",
                MediaType.parseMediaType("application/dicom+json")));

        // Only the first entry contributes a UID; the rest are skipped.
        assertThat(client.qidoListInstances("STUDY-4")).containsExactly("1.2.3.4.GOOD");
    }

    @Test
    @DisplayName("qidoListInstances short-circuits to empty when the master flag is off")
    void qidoFlagOffSkipsCall() {
        properties.setEnabled(false);
        // No server expectation set — calling the upstream would
        // trip MockRestServiceServer's "unexpected request".

        assertThat(client.qidoListInstances("STUDY-X")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("qidoListInstances short-circuits to empty when base-url is blank")
    void qidoBlankBaseUrlSkipsCall() {
        properties.setBaseUrl("   ");
        assertThat(client.qidoListInstances("STUDY-Y")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("qidoListInstances short-circuits to empty when studyUid is blank or null")
    void qidoBlankStudyUidSkipsCall() {
        assertThat(client.qidoListInstances("")).isEmpty();
        assertThat(client.qidoListInstances("   ")).isEmpty();
        assertThat(client.qidoListInstances(null)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("qidoListInstances trims a trailing slash in base-url before concat")
    void qidoTrimsTrailingSlashInBaseUrl() {
        properties.setBaseUrl(BASE_URL + "/");
        server.expect(requestTo(BASE_URL + "/studies/STUDY-7/instances"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("[]", MediaType.parseMediaType("application/dicom+json")));

        assertThat(client.qidoListInstances("STUDY-7")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("wadoFetchInstance returns the byte payload from a 200 application/dicom response")
    void wadoHappyPath() {
        byte[] payload = "dicom-bytes".getBytes(StandardCharsets.UTF_8);
        server.expect(requestTo(BASE_URL + "/studies/STUDY-5/instances/INSTANCE-A"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(payload, MediaType.parseMediaType("application/dicom")));

        byte[] result = client.wadoFetchInstance("STUDY-5", "INSTANCE-A");

        assertThat(result).isEqualTo(payload);
    }

    @Test
    @DisplayName("wadoFetchInstance returns empty byte[] when upstream answers 404")
    void wado404ReturnsEmpty() {
        server.expect(requestTo(BASE_URL + "/studies/STUDY-6/instances/INSTANCE-B"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.wadoFetchInstance("STUDY-6", "INSTANCE-B")).isEmpty();
    }

    @Test
    @DisplayName("wadoFetchInstance returns empty byte[] when upstream answers 4xx (other than 404)")
    void wadoOther4xxReturnsEmpty() {
        server.expect(requestTo(BASE_URL + "/studies/STUDY-6a/instances/INSTANCE-C"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client.wadoFetchInstance("STUDY-6a", "INSTANCE-C")).isEmpty();
    }

    @Test
    @DisplayName("wadoFetchInstance short-circuits to empty when blank inputs")
    void wadoBlankInputsSkipCall() {
        assertThat(client.wadoFetchInstance("", "INSTANCE-Z")).isEmpty();
        assertThat(client.wadoFetchInstance("STUDY-Q", "")).isEmpty();
        assertThat(client.wadoFetchInstance(null, "INSTANCE-W")).isEmpty();
        assertThat(client.wadoFetchInstance("STUDY-R", null)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("wadoFetchInstance short-circuits to empty when master flag is off")
    void wadoFlagOffSkipsCall() {
        properties.setEnabled(false);
        assertThat(client.wadoFetchInstance("STUDY-OFF", "INSTANCE-OFF")).isEmpty();
        server.verify();
    }
}

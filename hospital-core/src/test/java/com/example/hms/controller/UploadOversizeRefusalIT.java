package com.example.hms.controller;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2465 (r64): through the real servlet container, a file over the 10 MB
 * limit is refused with a message in the caller's language, whatever its size.
 * A file of 10–20 MB used to get a bare 413 « Content Too Large » with no
 * message, while a 23 MB one got the localized text.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
class UploadOversizeRefusalIT {

    @LocalServerPort
    private int port;

    @ParameterizedTest(name = "{0} MB")
    @ValueSource(doubles = {10.5, 15, 23})
    @DisplayName("an oversize upload is refused with a French message, never a bare 413")
    void oversizeIsRefusedWithAMessage(double megabytes) throws Exception {
        String boundary = "----hms" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.png\"\r\n"
            + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(new byte[(int) (megabytes * 1024 * 1024)]);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/imaging/orders/" + UUID.randomUUID() + "/images"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Accept-Language", "fr")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(413);
        assertThat(response.body()).contains("10 Mo");
    }
}

package com.example.hms.service;

import com.example.hms.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upload limit used to be three numbers: 10MB in the container, 20MB in
 * {@code FileUploadService} and the OpenAPI text, and "5MB" in the 413 message a
 * patient actually read. This pins them to one: the container's
 * {@code max-file-size} is the source, the service constant and the refusal
 * text must say the same, and the request limit must leave room for the
 * multipart envelope around a file of exactly that size.
 */
class UploadLimitConsistencyTest {

    private static Properties applicationProperties() throws IOException {
        // Read the file itself, not the classpath: a test-resources
        // application.properties would otherwise shadow the one prod runs.
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(
                Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    @Test
    void theServiceLimitIsTheContainerLimit() throws IOException {
        DataSize maxFile = DataSize.parse(applicationProperties()
                .getProperty("spring.servlet.multipart.max-file-size"));

        assertThat(FileUploadService.MAX_UPLOAD_BYTES).isEqualTo(maxFile.toBytes());
        assertThat(FileUploadService.MAX_UPLOAD_LABEL)
                .isEqualTo(maxFile.toMegabytes() + " MB");
    }

    @Test
    void theRequestLimitLeavesRoomForTheEnvelope() throws IOException {
        Properties props = applicationProperties();
        DataSize maxFile = DataSize.parse(props.getProperty("spring.servlet.multipart.max-file-size"));
        DataSize maxRequest = DataSize.parse(props.getProperty("spring.servlet.multipart.max-request-size"));

        assertThat(maxRequest.toBytes()).isGreaterThan(maxFile.toBytes());
    }

    @Test
    void theRefusalNamesTheRealLimit() {
        WebRequest request = Mockito.mock(WebRequest.class);
        Mockito.when(request.getDescription(false)).thenReturn("uri=/api/me/patient/documents");

        ResponseEntity<Object> response = new GlobalExceptionHandler()
                .handleMaxUploadSize(new MaxUploadSizeExceededException(FileUploadService.MAX_UPLOAD_BYTES), request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat((String) body.get("message")).endsWith(FileUploadService.MAX_UPLOAD_LABEL);
    }
}

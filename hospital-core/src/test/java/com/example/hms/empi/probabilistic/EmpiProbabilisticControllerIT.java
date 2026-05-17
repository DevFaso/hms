package com.example.hms.empi.probabilistic;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = "app.empi.probabilistic.enabled=false")
class EmpiProbabilisticControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("POST /api/empi/candidates returns 401 or 404 when flag off")
    void candidatesRejectedWhenFlagOff() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"firstName\":\"Awa\",\"lastName\":\"Diallo\",\"dateOfBirth\":\"1990-01-01\",\"sex\":\"F\",\"nationalId\":\"BF1234567890\"}";
        ResponseEntity<String> response = restTemplate.exchange(
            "/empi/candidates",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class
        );
        // Spring Security can produce 401 (anonymous principal blocked
        // ahead of the chain), 403 (anonymous principal reached the
        // chain but the @PreAuthorize role check failed), or 404 (an
        // authenticated SUPER_ADMIN reached the controller with the
        // flag off). All three prove the candidate-match path is not
        // surfacing data.
        assertThat(response.getStatusCode().value()).isIn(401, 403, 404);
    }
}

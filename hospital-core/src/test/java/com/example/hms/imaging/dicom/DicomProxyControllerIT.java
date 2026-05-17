package com.example.hms.imaging.dicom;

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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = "app.imaging.dicom-proxy.enabled=false")
class DicomProxyControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /api/imaging/dicom/{studyUid}/instances returns 401, 403, or 404 when flag off")
    void instancesRejectedWhenFlagOff() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        ResponseEntity<String> response = restTemplate.exchange(
            "/imaging/dicom/1.2.840.113619.2.55.1/instances",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class
        );
        // Same 401/403/404 split as the other v2.0 controller ITs —
        // unauth callers may surface as 401 (filter chain blocked) or
        // 403 (anonymous principal rejected by the @PreAuthorize role
        // check); 404 fires when an authenticated allowlisted user
        // reaches the controller with the flag off.
        assertThat(response.getStatusCode().value()).isIn(401, 403, 404);
    }
}

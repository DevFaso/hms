package com.example.hms.cdshooks;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
class CdsHooksDiscoveryIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /cds-services lists all registered CDS services without auth")
    void discoveryIsPublicAndListsServices() {
        ResponseEntity<String> response = restTemplate.getForEntity("/cds-services", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        assertThat(body).isNotNull();
        String compact = body.replaceAll("\\s+", "");
        assertThat(compact)
            .contains("\"hook\":\"patient-view\"")
            .contains("\"id\":\"hms-patient-view\"")
            .contains("\"hook\":\"order-sign\"")
            .contains("\"id\":\"hms-medication-allergy-check\"");
    }
}

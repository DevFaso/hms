package com.example.hms.security;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import com.example.hms.service.apikey.ApiKeyService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Exercises the REAL security chain for the partner surface (Tier 2 item
 * 45): the {@code ApiKeyAuthenticationFilter} registration, the
 * {@code /partner/**} authority gate, and the principal it produces. The
 * filter degrades to a no-op when its ObjectProvider finds no bean — so a
 * wiring or filter-order regression would be invisible to unit tests;
 * only a booted chain can prove the surface actually works.
 */
@SpringBootTest(classes = HmsApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
class PartnerApiSecurityIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private ApiKeyService apiKeyService;

    private ResponseEntity<String> pingWith(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.set(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey);
        }
        return restTemplate.exchange("/partner/ping", HttpMethod.GET,
            new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("a valid X-API-Key reaches /partner/ping and the principal carries the key's identity")
    void validKeyAuthenticates() {
        UUID hospitalId = UUID.randomUUID();
        when(apiKeyService.authenticate(eq("hms_pk_valid")))
            .thenReturn(Optional.of(new ApiKeyService.ApiKeyAuth(
                UUID.randomUUID(), hospitalId, "Mutuelle X claims")));

        ResponseEntity<String> response = pingWith("hms_pk_valid");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("Mutuelle X claims");
    }

    @Test
    @DisplayName("an invalid key is refused with 401 and no hint about why")
    void invalidKeyIsRefused() {
        when(apiKeyService.authenticate(eq("hms_pk_wrong"))).thenReturn(Optional.empty());

        ResponseEntity<String> response = pingWith("hms_pk_wrong");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("no key at all is refused with 401 - the surface is fail-closed")
    void absentKeyIsRefused() {
        ResponseEntity<String> response = pingWith(null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}

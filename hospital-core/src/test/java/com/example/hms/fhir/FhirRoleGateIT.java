package com.example.hms.fhir;

import com.example.hms.BaseIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Who may use the FHIR servlet at all. Runs the REAL security filter chain
 * (filters on) and asserts only the authorization decision: 401/403 means the
 * chain refused; anything else means the request got past it (MockMvc has no
 * HAPI servlet, so an admitted call ends in the dispatcher's 404).
 *
 * <p>Both principal shapes are exercised: a password-path
 * {@code UsernamePasswordAuthenticationToken} and a Keycloak-path
 * {@code JwtAuthenticationToken}, because {@code RoleExpansion} runs on the
 * first only — a physician or surgeon has to be named, not inherited.
 */
@AutoConfigureMockMvc
class FhirRoleGateIT extends BaseIT {

    private static final String ENCOUNTER = "/fhir/Encounter/" + UUID.randomUUID();
    private static final String ENCOUNTER_SEARCH = "/fhir/Encounter";

    @Autowired
    private MockMvc mockMvc;

    private int status(MockHttpServletRequestBuilder request, RequestPostProcessor principal) throws Exception {
        if (principal != null) {
            request = request.with(principal);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static RequestPostProcessor password(String... roles) {
        return authentication(new UsernamePasswordAuthenticationToken(
            "fhir-gate-user", "n/a",
            Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }

    private static RequestPostProcessor keycloak(String... roles) {
        return jwt().jwt(j -> j.subject("fhir-gate-oidc-" + UUID.randomUUID()))
            .authorities(Arrays.stream(roles).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new));
    }

    @Test
    @DisplayName("a patient token is refused on Encounter read and search, on both auth paths")
    void patientTokenIsRefused() throws Exception {
        assertThat(status(get(ENCOUNTER), password("ROLE_PATIENT"))).isEqualTo(403);
        assertThat(status(get(ENCOUNTER_SEARCH).param("patient", UUID.randomUUID().toString()),
            password("ROLE_PATIENT"))).isEqualTo(403);
        assertThat(status(get(ENCOUNTER), keycloak("ROLE_PATIENT"))).isEqualTo(403);
        assertThat(status(get(ENCOUNTER_SEARCH).param("patient", UUID.randomUUID().toString()),
            keycloak("ROLE_PATIENT"))).isEqualTo(403);
    }

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {
        "ROLE_RECEPTIONIST", "ROLE_BILLING_SPECIALIST", "ROLE_ACCOUNTANT", "ROLE_STAFF",
        "ROLE_LAB_SCIENTIST", "ROLE_PHARMACIST", "ROLE_HOSPITAL_ADMIN"})
    @DisplayName("roles that do not read the chart are refused on every FHIR resource")
    void nonReadersAreRefused(String role) throws Exception {
        for (String path : new String[] {ENCOUNTER, ENCOUNTER_SEARCH, "/fhir/Patient",
            "/fhir/Condition", "/fhir/MedicationRequest", "/fhir/Immunization", "/fhir"}) {
            assertThat(status(get(path), password(role))).as("%s on %s", role, path).isEqualTo(403);
            assertThat(status(get(path), keycloak(role))).as("%s (OIDC) on %s", role, path).isEqualTo(403);
        }
    }

    @ParameterizedTest(name = "{0} is admitted")
    @ValueSource(strings = {
        "ROLE_DOCTOR", "ROLE_PHYSICIAN", "ROLE_SURGEON", "ROLE_NURSE", "ROLE_MIDWIFE",
        "ROLE_RADIOLOGIST", "ROLE_ANESTHESIOLOGIST", "ROLE_PHYSIOTHERAPIST",
        "ROLE_SUPER_ADMIN", "ROLE_FHIR_CLIENT"})
    @DisplayName("the chart-reader roles get past the chain, named rather than inherited")
    void readersAreAdmitted(String role) throws Exception {
        assertThat(status(get(ENCOUNTER), password(role))).as(role).isNotIn(401, 403);
        // The OIDC path does not run RoleExpansion: the role alone must suffice.
        assertThat(status(get(ENCOUNTER), keycloak(role))).as("%s (OIDC)", role).isNotIn(401, 403);
    }

    @Test
    @DisplayName("$export admits the hospital admin its service admits, and nobody it does not")
    void bulkExportKeepsItsOwnPair() throws Exception {
        for (String path : new String[] {"/fhir/$export", "/fhir/Patient/$export"}) {
            assertThat(status(post(path), password("ROLE_HOSPITAL_ADMIN"))).as(path).isNotIn(401, 403);
            assertThat(status(post(path), password("ROLE_SUPER_ADMIN"))).as(path).isNotIn(401, 403);
            assertThat(status(post(path), password("ROLE_PATIENT"))).as(path).isEqualTo(403);
            assertThat(status(post(path), password("ROLE_RECEPTIONIST"))).as(path).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("discovery stays public and everything else needs a token")
    void discoveryIsPublic() throws Exception {
        assertThat(status(get("/fhir/metadata"), null)).isNotIn(401, 403);
        assertThat(status(get("/fhir/.well-known/smart-configuration"), null)).isNotIn(401, 403);
        assertThat(status(get(ENCOUNTER), null)).isEqualTo(401);
    }
}

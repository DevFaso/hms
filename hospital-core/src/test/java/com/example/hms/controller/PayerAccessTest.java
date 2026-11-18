package com.example.hms.controller;

import com.example.hms.service.billing.PayerCatalogueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing-money plan PR 6 (owner decision D-1, 2026-11-15; plan section 3.8): the payer catalogue's role matrix,
 * through real method security. Only the hospital admin and the accountant reach a write; the cashier (billing
 * specialist), reception, the claims reviewer, a clinician and the platform super-admin are refused before the
 * service. A super-admin who also carries the hospital admin authority passes this annotation and is refused by the
 * service, which requires the assignment at the acting hospital (PayerCatalogueServiceTest). Nothing is deleted, and
 * no route edits a rule in place.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = PayerController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.hms\\.security\\..*"))
@Import(PayerAccessTest.MethodSecurity.class)
@DisplayName("Payer catalogue: role matrix")
class PayerAccessTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurity {
    }

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PayerCatalogueService service;

    private static final UUID PAYER = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID RULE = UUID.randomUUID();

    private static void actAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("someone", "n/a", role));
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static List<MockHttpServletRequestBuilder> everyWrite() {
        String plans = "/billing/payers/" + PAYER + "/plans";
        return List.of(
            json(post("/billing/payers"), "{\"code\":\"MUT-TEST-A\",\"name\":\"Mutuelle Test A\",\"payerType\":\"MUTUELLE\"}"),
            json(put("/billing/payers/" + PAYER), "{\"name\":\"Mutuelle Test A\",\"payerType\":\"MUTUELLE\"}"),
            json(post("/billing/payers/" + PAYER + "/deactivate"), "{\"reason\":\"Convention résiliée\"}"),
            json(post(plans), "{\"code\":\"MTA-ESS\",\"name\":\"Formule\",\"defaultCoveragePercent\":75}"),
            json(put(plans + "/" + PLAN), "{\"name\":\"Formule\",\"defaultCoveragePercent\":75}"),
            json(post(plans + "/" + PLAN + "/rules"), "{\"category\":\"LAB_TEST\",\"excluded\":true}"),
            json(post(plans + "/" + PLAN + "/rules/" + RULE + "/end"), "{\"reason\":\"Avenant\"}"));
    }

    private static List<MockHttpServletRequestBuilder> everyRead() {
        return List.of(
            get("/billing/payers"),
            get("/billing/payers").param("includeInactive", "true"),
            get("/billing/payers/" + PAYER));
    }

    @ParameterizedTest(name = "{0} cannot change the payer catalogue")
    @ValueSource(strings = {"ROLE_BILLING_SPECIALIST", "ROLE_RECEPTIONIST", "ROLE_CLAIMS_REVIEWER", "ROLE_DOCTOR",
        "ROLE_NURSE", "ROLE_PHARMACIST", "ROLE_PATIENT", "ROLE_SUPER_ADMIN", "ROLE_ADMIN", "ROLE_STAFF"})
    void everyoneButTheAdminAndTheAccountantIsRefusedEveryWrite(String role) throws Exception {
        clearInvocations(service);
        for (MockHttpServletRequestBuilder request : everyWrite()) {
            actAs(role);
            mockMvc.perform(request).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0} reaches every write")
    @ValueSource(strings = {"ROLE_HOSPITAL_ADMIN", "ROLE_ACCOUNTANT"})
    void theAdminAndTheAccountantReachEveryWrite(String role) throws Exception {
        int[] expected = {201, 200, 200, 201, 200, 201, 200};
        List<MockHttpServletRequestBuilder> writes = everyWrite();
        for (int i = 0; i < writes.size(); i++) {
            actAs(role);
            mockMvc.perform(writes.get(i)).andExpect(status().is(expected[i]));
        }
    }

    @ParameterizedTest(name = "{0} reads the payer catalogue")
    @ValueSource(strings = {"ROLE_HOSPITAL_ADMIN", "ROLE_ACCOUNTANT", "ROLE_BILLING_SPECIALIST", "ROLE_RECEPTIONIST",
        "ROLE_CLAIMS_REVIEWER", "ROLE_SUPER_ADMIN"})
    void theFinanceDeskReceptionAndTheSuperAdminRead(String role) throws Exception {
        when(service.list(anyBoolean())).thenReturn(List.of());
        for (MockHttpServletRequestBuilder request : everyRead()) {
            actAs(role);
            mockMvc.perform(request).andExpect(status().isOk());
        }
    }

    @ParameterizedTest(name = "{0} does not read the payer catalogue")
    @ValueSource(strings = {"ROLE_DOCTOR", "ROLE_NURSE", "ROLE_PHARMACIST", "ROLE_PATIENT", "ROLE_LAB_SCIENTIST"})
    void cliniciansAndPatientsDoNotRead(String role) throws Exception {
        clearInvocations(service);
        for (MockHttpServletRequestBuilder request : everyRead()) {
            actAs(role);
            mockMvc.perform(request).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("nothing is deleted, and no route edits a rule in place")
    void noRouteDeletesOrEditsARule() throws Exception {
        String rule = "/billing/payers/" + PAYER + "/plans/" + PLAN + "/rules/" + RULE;
        for (MockHttpServletRequestBuilder request : List.of(
                json(put(rule), "{\"coveragePercent\":1}"),
                json(patch(rule), "{\"coveragePercent\":1}"),
                delete(rule),
                delete("/billing/payers/" + PAYER + "/plans/" + PLAN),
                delete("/billing/payers/" + PAYER),
                json(patch("/billing/payers/" + PAYER), "{}"))) {
            actAs("ROLE_HOSPITAL_ADMIN");
            int code = mockMvc.perform(request).andReturn().getResponse().getStatus();
            org.assertj.core.api.Assertions.assertThat(code).as(request.toString()).isIn(404, 405);
        }
        verifyNoInteractions(service);
    }
}

package com.example.hms.controller;

import com.example.hms.service.billing.InvoiceRefundService;
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
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing-money plan PR 8 (plan section 3.8, owner decision D-3): the refund role matrix, through real method
 * security. Each step admits only its roles; every other role is refused before the service. The platform
 * super-admin reads only (and, holding the hospital admin authority through RoleExpansion, is refused by the service,
 * which checks the assignment: InvoiceRefundServiceTest). A card number in a reason is refused at the edge.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = InvoiceRefundController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.hms\\.security\\..*"))
@Import(InvoiceRefundAccessTest.MethodSecurity.class)
@DisplayName("Invoice refunds: role matrix")
class InvoiceRefundAccessTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurity {
    }

    @Autowired private MockMvc mockMvc;
    @MockitoBean private InvoiceRefundService service;

    private static final UUID INVOICE = UUID.randomUUID();
    private static final UUID REFUND = UUID.randomUUID();
    private static final String BASE = "/billing-invoices/" + INVOICE + "/refunds";

    private static final List<String> EVERY_ROLE = List.of("ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_ACCOUNTANT",
        "ROLE_BILLING_SPECIALIST", "ROLE_RECEPTIONIST", "ROLE_CLAIMS_REVIEWER", "ROLE_DOCTOR", "ROLE_NURSE",
        "ROLE_PHARMACIST", "ROLE_PATIENT", "ROLE_ADMIN", "ROLE_STAFF");

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

    private record Step(String name, MockHttpServletRequestBuilder request, Set<String> allowed) {
    }

    private static List<Step> steps() {
        Set<String> readers = Set.of("ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_ACCOUNTANT",
            "ROLE_BILLING_SPECIALIST", "ROLE_RECEPTIONIST");
        Set<String> requesters = Set.of("ROLE_HOSPITAL_ADMIN", "ROLE_ACCOUNTANT", "ROLE_BILLING_SPECIALIST",
            "ROLE_RECEPTIONIST");
        Set<String> approvers = Set.of("ROLE_HOSPITAL_ADMIN", "ROLE_ACCOUNTANT");
        Set<String> payers = Set.of("ROLE_HOSPITAL_ADMIN", "ROLE_BILLING_SPECIALIST", "ROLE_RECEPTIONIST");
        return List.of(
            new Step("queue", get("/billing-invoices/refunds"), readers),
            new Step("list", get(BASE), readers),
            new Step("receipt", get(BASE + "/" + REFUND + "/receipt"), readers),
            new Step("request", json(post(BASE), "{\"amount\":500,\"method\":\"CASH\",\"reason\":\"Trop-perçu\"}"),
                requesters),
            new Step("approve", json(post(BASE + "/" + REFUND + "/approve"), "{}"), approvers),
            new Step("reject", json(post(BASE + "/" + REFUND + "/reject"), "{\"reason\":\"Non\"}"), approvers),
            new Step("pay", post(BASE + "/" + REFUND + "/pay"), payers),
            new Step("cancel", json(post(BASE + "/" + REFUND + "/cancel"), "{\"reason\":\"Erreur\"}"), requesters));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"queue", "list", "receipt", "request", "approve", "reject", "pay", "cancel"})
    void eachStepAdmitsOnlyItsRoles(String name) throws Exception {
        Step step = steps().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
        for (String role : EVERY_ROLE) {
            clearInvocations(service);
            actAs(role);
            int code = mockMvc.perform(step.request()).andReturn().getResponse().getStatus();
            if (step.allowed().contains(role)) {
                org.assertj.core.api.Assertions.assertThat(code).as(name + " as " + role).isNotEqualTo(403);
            } else {
                org.assertj.core.api.Assertions.assertThat(code).as(name + " as " + role).isEqualTo(403);
                verifyNoInteractions(service);
            }
        }
    }

    @Test
    @DisplayName("the super-admin never reaches a step that moves money")
    void theSuperAdminOnlyReads() throws Exception {
        for (Step step : steps()) {
            boolean write = !Set.of("queue", "list", "receipt").contains(step.name());
            if (write) {
                actAs("ROLE_SUPER_ADMIN");
                mockMvc.perform(step.request()).andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a card number in a refund reason or reference is refused before the service (400)")
    void aCardNumberIsRefusedAtTheEdge() throws Exception {
        actAs("ROLE_BILLING_SPECIALIST");
        mockMvc.perform(json(post(BASE),
                "{\"amount\":500,\"method\":\"CASH\",\"reason\":\"Carte 4111 1111 1111 1111\"}"))
            .andExpect(status().isBadRequest());
        actAs("ROLE_BILLING_SPECIALIST");
        mockMvc.perform(json(post(BASE),
                "{\"amount\":500,\"method\":\"BANK_TRANSFER\",\"reference\":\"4111-1111-1111-1111\",\"reason\":\"x\"}"))
            .andExpect(status().isBadRequest());
        actAs("ROLE_HOSPITAL_ADMIN");
        mockMvc.perform(json(post(BASE + "/" + REFUND + "/reject"), "{\"reason\":\"5555555555554444\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}

package com.example.hms.controller;

import com.example.hms.service.billing.PriceListService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing-money plan PR 2 (owner decision D-1, 2026-11-15; plan section 3.8): the price-list role matrix, through
 * real method security. Only the hospital admin and the accountant reach a write; the cashier (billing specialist),
 * reception, the claims reviewer, a clinician and the platform super-admin are refused before the service. A
 * super-admin who ALSO carries the hospital admin authority (RoleExpansion) passes this annotation and is refused by
 * the service, which requires the assignment at the acting hospital (PriceListServiceTest, PriceListPostgresIT).
 * There is no route that edits or deletes a price version.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = PriceListController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.hms\\.security\\..*"))
@Import(PriceListAccessTest.MethodSecurity.class)
@DisplayName("Price list: role matrix")
class PriceListAccessTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurity {
    }

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PriceListService service;

    private static final UUID ITEM = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();

    private static void actAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("someone", "n/a", role));
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    private static List<MockHttpServletRequestBuilder> everyWrite() {
        return List.of(
            post("/billing/price-list").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"CS-GEN\",\"label\":\"Consultation générale\",\"category\":\"CONSULTATION\","
                    + "\"unitPrice\":2000}"),
            put("/billing/price-list/" + ITEM).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Consultation\",\"category\":\"CONSULTATION\"}"),
            post("/billing/price-list/" + ITEM + "/prices").contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitPrice\":2500,\"effectiveFrom\":\"2026-12-01\",\"changeReason\":\"Hausse\"}"),
            post("/billing/price-list/" + ITEM + "/deactivate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Retiré\"}"));
    }

    private static List<MockHttpServletRequestBuilder> everyRead() {
        return List.of(
            get("/billing/price-list"),
            get("/billing/price-list").param("category", "LAB_TEST").param("includeInactive", "true"),
            get("/billing/price-list/link-options"),
            get("/billing/price-list/" + ITEM));
    }

    @ParameterizedTest(name = "{0} cannot write a price")
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
        actAs(role);
        mockMvc.perform(everyWrite().get(0)).andExpect(status().isCreated());
        actAs(role);
        mockMvc.perform(everyWrite().get(1)).andExpect(status().isOk());
        actAs(role);
        mockMvc.perform(everyWrite().get(2)).andExpect(status().isCreated());
        actAs(role);
        mockMvc.perform(everyWrite().get(3)).andExpect(status().isOk());
        verify(service).create(any());
        verify(service).update(eq(ITEM), any());
        verify(service).addPrice(eq(ITEM), any());
        verify(service).deactivate(eq(ITEM), any());
    }

    @ParameterizedTest(name = "{0} reads the price list")
    @ValueSource(strings = {"ROLE_HOSPITAL_ADMIN", "ROLE_ACCOUNTANT", "ROLE_BILLING_SPECIALIST", "ROLE_RECEPTIONIST",
        "ROLE_CLAIMS_REVIEWER", "ROLE_SUPER_ADMIN"})
    void theFinanceDeskAndTheSuperAdminRead(String role) throws Exception {
        when(service.list(any(), anyBoolean())).thenReturn(List.of());
        for (MockHttpServletRequestBuilder request : everyRead()) {
            actAs(role);
            mockMvc.perform(request).andExpect(status().isOk());
        }
    }

    @ParameterizedTest(name = "{0} does not read the price list")
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
    @DisplayName("no route edits or deletes a price version, nor deletes an item")
    void aPriceVersionCannotBeEditedOrDeleted() throws Exception {
        String version = "/billing/price-list/" + ITEM + "/prices/" + VERSION;
        for (MockHttpServletRequestBuilder request : List.of(
                put(version).contentType(MediaType.APPLICATION_JSON).content("{\"unitPrice\":1}"),
                patch(version).contentType(MediaType.APPLICATION_JSON).content("{\"unitPrice\":1}"),
                delete(version),
                get(version),
                delete("/billing/price-list/" + ITEM + "/prices"),
                put("/billing/price-list/" + ITEM + "/prices").contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete("/billing/price-list/" + ITEM),
                patch("/billing/price-list/" + ITEM).contentType(MediaType.APPLICATION_JSON).content("{}"))) {
            actAs("ROLE_HOSPITAL_ADMIN");
            int code = mockMvc.perform(request).andReturn().getResponse().getStatus();
            org.assertj.core.api.Assertions.assertThat(code)
                .as(request.toString())
                .isIn(404, 405);
        }
        verifyNoInteractions(service);
    }
}

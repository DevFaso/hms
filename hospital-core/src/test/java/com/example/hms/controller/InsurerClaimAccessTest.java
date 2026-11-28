package com.example.hms.controller;

import com.example.hms.service.billing.claim.InsurerClaimBatchService;
import com.example.hms.service.billing.claim.InsurerClaimReportService;
import com.example.hms.service.billing.claim.InsurerClaimSettlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Insurer claims plan PRs 2 to 4 (plan section 4.4, owner decision of 2026-11-26): the role matrix of the claims
 * endpoints, through real method security. Readers: super-admin, hospital admin, accountant, billing specialist,
 * claims reviewer. Builders (draft, submit, export, refusal, write-off request): the same without the super-admin.
 * Finance (insurer payments, cancelling a sent bordereau, deciding a write-off): hospital admin and accountant
 * only - never the billing specialist, who holds the till. Every other role is refused before any service. A
 * request without its key, its reference or its reason is refused at the edge.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = InsurerClaimController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.hms\\.security\\..*"))
@Import(InsurerClaimAccessTest.MethodSecurity.class)
@DisplayName("Insurer claims: role matrix")
class InsurerClaimAccessTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurity {
    }

    @Autowired private MockMvc mockMvc;
    @MockitoBean private InsurerClaimBatchService batchService;
    @MockitoBean private InsurerClaimSettlementService settlementService;
    @MockitoBean private InsurerClaimReportService reportService;

    private static final String BASE = "/billing-invoices/insurer-claims";
    private static final UUID ID = UUID.randomUUID();
    private static final String KEY = "\"clientRequestId\":\"" + UUID.randomUUID() + "\"";

    private static final List<String> EVERY_ROLE = List.of("ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_ACCOUNTANT",
        "ROLE_BILLING_SPECIALIST", "ROLE_RECEPTIONIST", "ROLE_CLAIMS_REVIEWER", "ROLE_DOCTOR", "ROLE_NURSE",
        "ROLE_PHARMACIST", "ROLE_PATIENT", "ROLE_ADMIN", "ROLE_STAFF");
    private static final Set<String> READERS = Set.of("ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_ACCOUNTANT",
        "ROLE_BILLING_SPECIALIST", "ROLE_CLAIMS_REVIEWER");
    private static final Set<String> BUILDERS = Set.of("ROLE_HOSPITAL_ADMIN", "ROLE_ACCOUNTANT",
        "ROLE_BILLING_SPECIALIST", "ROLE_CLAIMS_REVIEWER");
    private static final Set<String> FINANCE = Set.of("ROLE_HOSPITAL_ADMIN", "ROLE_ACCOUNTANT");

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
        String payment = "{\"payerId\":\"" + ID + "\",\"amount\":9000,\"receivedDate\":\"2026-11-20\","
            + "\"method\":\"BANK_TRANSFER\",\"reference\":\"VIR-42\"," + KEY + "}";
        String refusal = "{\"amount\":2800,\"reasonClass\":\"PATIENT_SIDE\",\"reason\":\"Plafond atteint\","
            + "\"resolution\":\"RESUBMIT\"," + KEY + "}";
        String writeOff = "{\"amount\":2800,\"reasonCode\":\"COMMERCIAL_WAIVER\",\"reason\":\"Accord\","
            + "\"supportingDocument\":\"Lettre 14\"," + KEY + "}";
        String reason = "{\"reason\":\"Erreur de saisie\"}";
        return List.of(
            new Step("list batches", get(BASE + "/batches"), READERS),
            new Step("get batch", get(BASE + "/batches/" + ID), READERS),
            new Step("open lines", get(BASE + "/open-lines").param("payerId", ID.toString()), READERS),
            new Step("list payments", get(BASE + "/payments"), READERS),
            new Step("get payment", get(BASE + "/payments/" + ID), READERS),
            new Step("list write-offs", get(BASE + "/write-offs"), READERS),
            new Step("ageing", get(BASE + "/reports/ageing"), READERS),
            new Step("reconciliation", get(BASE + "/reports/reconciliation"), READERS),
            new Step("create batch", json(post(BASE + "/batches"), "{\"payerId\":\"" + ID
                + "\",\"periodFrom\":\"2026-11-01\",\"periodTo\":\"2026-11-30\"}"), BUILDERS),
            new Step("change shares", json(put(BASE + "/batches/" + ID + "/shares"), "{\"add\":[]}"), BUILDERS),
            new Step("line documents", json(put(BASE + "/batches/" + ID + "/lines/" + ID + "/documents"),
                "{\"attachedDocuments\":\"Ordonnance\"}"), BUILDERS),
            new Step("submit", json(post(BASE + "/batches/" + ID + "/submit"), "{}"), BUILDERS),
            new Step("payer receipt", json(put(BASE + "/batches/" + ID + "/payer-receipt"),
                "{\"receivedOn\":\"2026-11-21\"}"), BUILDERS),
            new Step("delete draft", delete(BASE + "/batches/" + ID), BUILDERS),
            new Step("export pdf", get(BASE + "/batches/" + ID + "/bordereau.pdf"), BUILDERS),
            new Step("export csv", get(BASE + "/batches/" + ID + "/bordereau.csv"), BUILDERS),
            new Step("refuse line", json(post(BASE + "/lines/" + ID + "/refusals"), refusal), BUILDERS),
            new Step("ask write-off", json(post(BASE + "/lines/" + ID + "/write-offs"), writeOff), BUILDERS),
            new Step("withdraw write-off", post(BASE + "/write-offs/" + ID + "/cancel"), BUILDERS),
            new Step("cancel batch", json(post(BASE + "/batches/" + ID + "/cancel"), reason), FINANCE),
            new Step("record payment", json(post(BASE + "/payments"), payment), FINANCE),
            new Step("allocate", json(post(BASE + "/payments/" + ID + "/allocations"),
                "{\"allocations\":[{\"claimLineId\":\"" + ID + "\",\"amount\":100}]}"), FINANCE),
            new Step("reverse payment", json(post(BASE + "/payments/" + ID + "/reverse"), reason), FINANCE),
            new Step("approve write-off", post(BASE + "/write-offs/" + ID + "/approve"), FINANCE),
            new Step("refuse write-off", json(post(BASE + "/write-offs/" + ID + "/refuse"), reason), FINANCE));
    }

    @Test
    @DisplayName("each endpoint admits only its roles; every other role is refused before any service")
    void eachStepAdmitsOnlyItsRoles() throws Exception {
        for (Step step : steps()) {
            for (String role : EVERY_ROLE) {
                clearInvocations(batchService, settlementService, reportService);
                actAs(role);
                int code = mockMvc.perform(step.request()).andReturn().getResponse().getStatus();
                if (step.allowed().contains(role)) {
                    assertThat(code).as(step.name() + " as " + role).isNotIn(400, 403, 404, 405);
                } else {
                    assertThat(code).as(step.name() + " as " + role).isEqualTo(403);
                    verifyNoInteractions(batchService, settlementService, reportService);
                }
            }
        }
    }

    @Test
    @DisplayName("an insurer payment is never recorded by the billing specialist or the claims reviewer")
    void theTillNeverRecordsAnInsurerPayment() {
        Step record = steps().stream().filter(step -> step.name().equals("record payment")).findFirst().orElseThrow();
        assertThat(record.allowed()).containsExactlyInAnyOrder("ROLE_HOSPITAL_ADMIN", "ROLE_ACCOUNTANT");
        assertThat(InsurerClaimController.FINANCE).doesNotContain("BILLING_SPECIALIST", "CLAIMS_REVIEWER",
            "SUPER_ADMIN");
        assertThat(InsurerClaimController.BUILDERS).doesNotContain("SUPER_ADMIN");
    }

    @Test
    @DisplayName("review of #1394 (F2): a whole share is refused to the patient by the hospital admin or the "
        + "accountant only, as a refused bordereau line is re-billed")
    void aWholeRefusalToThePatientIsFinanceOnly() throws NoSuchMethodException {
        String rule = InvoicePayerShareController.class
            .getMethod("reject", java.util.UUID.class, java.util.UUID.class,
                com.example.hms.payload.dto.billing.PayerShareRejectRequestDTO.class)
            .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value();
        assertThat(rule).isEqualTo(InvoicePayerShareController.REFUSERS).isEqualTo(InsurerClaimController.FINANCE);
    }

    @Test
    @DisplayName("a payment without its amount, date, reference or key, a refusal without a reason, and a card "
        + "number in a reference are refused at the edge, with the fields named")
    void incompleteMoneyRequestsAreRefusedAtTheEdge() throws Exception {
        actAs("ROLE_ACCOUNTANT");
        String body = mockMvc.perform(json(post(BASE + "/payments"), "{\"payerId\":\"" + ID + "\"}"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("amount", "receivedDate", "method", "reference", "clientRequestId");
        int card = mockMvc.perform(json(post(BASE + "/payments"), "{\"payerId\":\"" + ID
                + "\",\"amount\":9000,\"receivedDate\":\"2026-11-20\",\"method\":\"CHEQUE\","
                + "\"reference\":\"4111 1111 1111 1111\"," + KEY + "}"))
            .andReturn().getResponse().getStatus();
        assertThat(card).isEqualTo(400);
        int cardMethod = mockMvc.perform(json(post(BASE + "/payments"), "{\"payerId\":\"" + ID
                + "\",\"amount\":9000,\"receivedDate\":\"2026-11-20\",\"method\":\"CARD\","
                + "\"reference\":\"REF-1\"," + KEY + "}"))
            .andReturn().getResponse().getStatus();
        assertThat(cardMethod).as("no card method exists for a payer").isEqualTo(400);
        int refusal = mockMvc.perform(json(post(BASE + "/lines/" + ID + "/refusals"),
                "{\"amount\":100,\"reasonClass\":\"PATIENT_SIDE\",\"resolution\":\"RESUBMIT\"," + KEY + "}"))
            .andReturn().getResponse().getStatus();
        assertThat(refusal).isEqualTo(400);
        int reversal = mockMvc.perform(json(post(BASE + "/payments/" + ID + "/reverse"), "{\"reason\":\" \"}"))
            .andReturn().getResponse().getStatus();
        assertThat(reversal).isEqualTo(400);
        verifyNoInteractions(settlementService);
    }
}

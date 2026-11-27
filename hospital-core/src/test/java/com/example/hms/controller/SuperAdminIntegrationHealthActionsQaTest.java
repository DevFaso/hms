package com.example.hms.controller;

import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.mapper.IntegrationHealthSnapshotMapper;
import com.example.hms.repository.IntegrationHealthSnapshotRepository;
import com.example.hms.repository.integration.IntegrationHealthEventRepository;
import com.example.hms.service.impl.SuperAdminIntegrationHealthServiceImpl;
import com.example.hms.service.integration.eligibility.StubEligibilityProvider;
import com.example.hms.service.integration.health.IntegrationHealthActionService;
import com.example.hms.service.integration.health.IntegrationHealthRecorder;
import com.example.hms.service.platform.discovery.IntegrationDescriptor;
import com.example.hms.service.platform.discovery.PlatformIntegrationAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D851: the console listed four integrations (billing, ehr, inventory,
 * eligibility) and every row's Re-sync, history and Test connection answered
 * 404 "Intégration introuvable", because only the probe and re-sync beans
 * counted as "known" and none of the listed rows has one. These are the exact
 * calls the console makes, against the real inventory and action services.
 */
@DisplayName("QA D851: the integration console's actions know every listed integration")
class SuperAdminIntegrationHealthActionsQaTest {

    private static final List<String> LISTED = List.of("billing", "ehr", "inventory", "eligibility");

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        IntegrationHealthSnapshotRepository snapshots = mock(IntegrationHealthSnapshotRepository.class);
        when(snapshots.findAllByOrderByIntegrationIdAscLastStatusAsc()).thenReturn(List.of());
        when(snapshots.findByIntegrationIdOrderByLastStatusAscUpdatedAtDesc(anyString())).thenReturn(List.of());
        IntegrationHealthEventRepository events = mock(IntegrationHealthEventRepository.class);
        when(events.findRecentForIntegration(anyString(), any())).thenReturn(List.of());

        // QA D4300: the four partner connectors, registered as on a running platform.
        IntegrationHealthRecorder recorder = mock(IntegrationHealthRecorder.class);
        List<com.example.hms.service.integration.partner.StubPartnerConnector> partners = List.of(
            new com.example.hms.service.integration.partner.NhisConnector(recorder, null),
            new com.example.hms.service.integration.partner.NhiaConnector(recorder, null),
            new com.example.hms.service.integration.partner.CnamgsConnector(recorder, null),
            new com.example.hms.service.integration.partner.MutuelleConnector(recorder, null));
        com.example.hms.utility.MessageUtil.setMessageSource(TestMessageSources.bundles());
        IntegrationHealthActionService actions = new IntegrationHealthActionService(
            List.copyOf(partners), List.copyOf(partners), recorder, events);
        SuperAdminIntegrationHealthServiceImpl inventory = new SuperAdminIntegrationHealthServiceImpl(
            List.of(adapter("billing", PlatformServiceType.BILLING),
                adapter("ehr", PlatformServiceType.EHR),
                adapter("inventory", PlatformServiceType.INVENTORY)),
            List.of(new StubEligibilityProvider()),
            snapshots, new IntegrationHealthSnapshotMapper(), actions, TestMessageSources.bundles());

        mvc = MockMvcBuilders
            .standaloneSetup(new SuperAdminIntegrationHealthController(inventory, actions))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @org.junit.jupiter.api.AfterEach
    void resetMessages() {
        com.example.hms.utility.MessageUtil.setMessageSource(null);
    }

    private static final List<String> PARTNERS =
        List.of("partner.nhis", "partner.nhia", "partner.cnamgs", "partner.mutuelle");

    @Test
    @DisplayName("D4300: the inventory lists the partner connectors too, each with a test and its re-sync")
    void inventoryListsEveryConnectorWithItsActions() throws Exception {
        mvc.perform(get("/super-admin/integrations").header("Accept-Language", "fr"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalIntegrations").value(8))
            // Every listed integration can be tested.
            .andExpect(jsonPath("$.integrations[?(@.probeSupported == false)]").isEmpty())
            .andExpect(jsonPath("$.integrations[?(@.resyncSupported == true)].integrationId",
                org.hamcrest.Matchers.containsInAnyOrder(PARTNERS.toArray())))
            // Named in words, and shown as not configured while nothing is behind them.
            .andExpect(jsonPath("$.integrations[?(@.integrationId == 'partner.nhis')].displayName",
                org.hamcrest.Matchers.contains(org.hamcrest.Matchers.containsString("NHIS"))))
            .andExpect(jsonPath("$.integrations[?(@.integrationId == 'partner.nhis')].stub",
                org.hamcrest.Matchers.contains(true)));
    }

    @Test
    @DisplayName("D4300: a partner connector has its own page, history, test and re-sync (was 404 on the page)")
    void aPartnerConnectorIsKnownEverywhere() throws Exception {
        for (String id : PARTNERS) {
            mvc.perform(get("/super-admin/integrations/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.integrationId").value(id));
            mvc.perform(get("/super-admin/integrations/" + id + "/history")).andExpect(status().isOk());
            mvc.perform(post("/super-admin/integrations/" + id + "/resync")).andExpect(status().isAccepted());
            mvc.perform(post("/super-admin/integrations/" + id + "/probe").header("Accept-Language", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                // D4303: the reason in French, with no English and no raw key.
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("partenaire")))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(
                    org.hamcrest.Matchers.containsString("stub"))));
        }
    }

    @Test
    @DisplayName("history answers 200 for every listed integration (was 404)")
    void historyKnowsEveryListedIntegration() throws Exception {
        for (String id : LISTED) {
            mvc.perform(get("/super-admin/integrations/" + id + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        }
    }

    @Test
    @DisplayName("Re-sync on billing is the 'not supported' refusal (400), not 'not found' (404)")
    void resyncOnBillingIsNotSupportedRatherThanUnknown() throws Exception {
        for (String id : LISTED) {
            mvc.perform(post("/super-admin/integrations/" + id + "/resync"))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("D4300: Test connection on a platform integration checks its configuration (was a 400 refusal)")
    void probeOnAPlatformIntegrationChecksItsConfiguration() throws Exception {
        for (String id : List.of("billing", "ehr", "inventory")) {
            mvc.perform(post("/super-admin/integrations/" + id + "/probe").header("Accept-Language", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("configurée")));
        }
        // Only the built-in reference provider answers eligibility: nothing is connected.
        mvc.perform(post("/super-admin/integrations/eligibility/probe"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @DisplayName("an id the console never listed is still 404 on every action")
    void unknownIntegrationStaysNotFound() throws Exception {
        mvc.perform(get("/super-admin/integrations/nope/history")).andExpect(status().isNotFound());
        mvc.perform(post("/super-admin/integrations/nope/resync")).andExpect(status().isNotFound());
        mvc.perform(post("/super-admin/integrations/nope/probe")).andExpect(status().isNotFound());
    }

    private static PlatformIntegrationAdapter adapter(String id, PlatformServiceType type) {
        return new PlatformIntegrationAdapter() {
            @Override
            public PlatformServiceType getServiceType() {
                return type;
            }

            @Override
            public IntegrationDescriptor describe(Locale locale) {
                return IntegrationDescriptor.builder()
                    .id(id)
                    .serviceType(type)
                    .displayName(id)
                    .provider("test")
                    .enabled(true)
                    .capabilities(List.of())
                    .build();
            }
        };
    }
}

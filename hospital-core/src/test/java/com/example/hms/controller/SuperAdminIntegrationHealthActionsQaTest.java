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

        IntegrationHealthActionService actions = new IntegrationHealthActionService(
            List.of(), List.of(), mock(IntegrationHealthRecorder.class), events);
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

    @Test
    @DisplayName("the inventory lists the four integrations and says none can be probed or re-synced")
    void inventoryAdvertisesNoActions() throws Exception {
        mvc.perform(get("/super-admin/integrations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalIntegrations").value(4))
            .andExpect(jsonPath("$.integrations[?(@.probeSupported == true)]").isEmpty())
            .andExpect(jsonPath("$.integrations[?(@.resyncSupported == true)]").isEmpty());
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
    @DisplayName("Test connection on a listed integration is the 'not supported' refusal (400), not 404")
    void probeOnListedIntegrationIsNotSupportedRatherThanUnknown() throws Exception {
        for (String id : LISTED) {
            mvc.perform(post("/super-admin/integrations/" + id + "/probe"))
                .andExpect(status().isBadRequest());
        }
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

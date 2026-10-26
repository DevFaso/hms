package com.example.hms.controller;

import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.mapper.SubscriptionMapper;
import com.example.hms.model.platform.OrganizationSubscription;
import com.example.hms.model.platform.SubscriptionPlan;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.OrganizationSubscriptionRepository;
import com.example.hms.repository.SubscriptionPlanRepository;
import com.example.hms.service.impl.SubscriptionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D850 / D865, the exact super-admin calls of the report: DELETE a plan an
 * organization is subscribed to, and POST a plan in currency "ZZZZ".
 */
@DisplayName("QA D850/D865: subscription plan endpoints")
class SuperAdminSubscriptionPlanQaTest {

    private SubscriptionPlanRepository planRepository;
    private OrganizationSubscriptionRepository subscriptionRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        planRepository = mock(SubscriptionPlanRepository.class);
        subscriptionRepository = mock(OrganizationSubscriptionRepository.class);
        when(planRepository.save(any(SubscriptionPlan.class))).thenAnswer(inv -> {
            SubscriptionPlan plan = inv.getArgument(0);
            if (plan.getId() == null) {
                plan.setId(UUID.randomUUID());
            }
            return plan;
        });
        SubscriptionServiceImpl service = new SubscriptionServiceImpl(
            planRepository, subscriptionRepository, mock(OrganizationRepository.class), new SubscriptionMapper());
        mvc = MockMvcBuilders.standaloneSetup(new SuperAdminSubscriptionController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(
                tools.jackson.databind.json.JsonMapper.builder()
                    // The production module: a field the body leaves out keeps its default.
                    .addModule(new com.example.hms.config.RequestBodyDefaultsModule()).build()))
            .build();
    }

    @Test
    @DisplayName("D850: DELETE on a plan an organization holds is 409 and the plan stays active (was 204)")
    void deletingAPlanInUseIsAConflict() throws Exception {
        SubscriptionPlan plan = SubscriptionPlan.builder()
            .name("Essentiel Faso").tierCode("ESS81562").monthlyPriceCents(2_500_000L)
            .currency("XOF").includedSeats(25).featureKeys("lab,pharmacy").active(true).build();
        plan.setId(UUID.randomUUID());
        when(planRepository.findByIdForUpdate(plan.getId())).thenReturn(Optional.of(plan));
        when(subscriptionRepository.countByPlanIdAndStatus(plan.getId(), OrganizationSubscription.Status.ACTIVE))
            .thenReturn(1L);

        mvc.perform(delete("/super-admin/subscriptions/plans/" + plan.getId()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.field").value("planId"));

        assertThat(plan.isActive()).isTrue();
        verify(planRepository, never()).save(any(SubscriptionPlan.class));
    }

    @Test
    @DisplayName("D850: DELETE on a plan nobody holds still deactivates it (204)")
    void deletingAnUnusedPlanStillWorks() throws Exception {
        SubscriptionPlan plan = SubscriptionPlan.builder()
            .name("Ancienne").tierCode("OLD").monthlyPriceCents(0L).currency("XOF").active(true).build();
        plan.setId(UUID.randomUUID());
        when(planRepository.findByIdForUpdate(plan.getId())).thenReturn(Optional.of(plan));

        mvc.perform(delete("/super-admin/subscriptions/plans/" + plan.getId()))
            .andExpect(status().isNoContent());

        assertThat(plan.isActive()).isFalse();
    }

    @Test
    @DisplayName("D865: currency ZZZZ is a 400 on the currency field (was 201)")
    void unknownCurrencyIsRefusedOnItsField() throws Exception {
        mvc.perform(post("/super-admin/subscriptions/plans")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Devise bizarre\",\"tierCode\":\"CUR1\",\"monthlyPriceCents\":1,"
                    + "\"currency\":\"ZZZZ\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.currency").exists());
        verify(planRepository, never()).save(any(SubscriptionPlan.class));
    }
}

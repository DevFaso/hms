package com.example.hms.controller;

import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.model.Hospital;
import com.example.hms.model.platform.ReportDefinition;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ReportDefinitionRepository;
import com.example.hms.repository.ReportRunRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.reporting.ScheduledReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D855, the exact call of the report: a hospital admin creates a scheduled
 * report for "accueil.clinique@@example". It was saved (201) and every run
 * then failed in the mail service with "Invalid email format", in English.
 */
@DisplayName("QA D855: scheduled report recipients")
class ReportControllerRecipientsQaTest {

    private final UUID hospitalId = UUID.randomUUID();
    private ReportDefinitionRepository definitionRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        HospitalContextHolder.clear();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .hospitalAdmin(true)
            .activeHospitalId(hospitalId)
            .permittedHospitalIds(Set.of(hospitalId))
            .staffHospitalIds(Set.of(hospitalId))
            .build());
        definitionRepository = mock(ReportDefinitionRepository.class);
        when(definitionRepository.save(any(ReportDefinition.class))).thenAnswer(inv -> {
            ReportDefinition d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        HospitalRepository hospitalRepository = mock(HospitalRepository.class);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        ReportController controller = new ReportController(definitionRepository,
            mock(ReportRunRepository.class), mock(ScheduledReportService.class), hospitalRepository);
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(
                tools.jackson.databind.json.JsonMapper.builder()
                    // The production module: a field the body leaves out keeps its default.
                    .addModule(new com.example.hms.config.RequestBodyDefaultsModule()).build()))
            .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
            .build();
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    private static String body(String recipients) {
        return "{\"name\":\"Activité mensuelle\",\"reportType\":\"ENCOUNTER_ACTIVITY\","
            + "\"period\":\"MONTHLY\",\"recipients\":\"" + recipients + "\"}";
    }

    @Test
    @DisplayName("a malformed recipient is a 400 on the recipients field and nothing is saved (was 201)")
    void malformedRecipientIsRefused() throws Exception {
        mvc.perform(post("/reports").contentType(MediaType.APPLICATION_JSON)
                .content(body("accueil.clinique@@example")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.recipients").exists());
        verify(definitionRepository, never()).save(any(ReportDefinition.class));
    }

    @Test
    @DisplayName("well-formed recipients are saved, trimmed")
    void wellFormedRecipientsAreSaved() throws Exception {
        mvc.perform(post("/reports").contentType(MediaType.APPLICATION_JSON)
                .content(body(" accueil@example.org , direction@example.org ")))
            .andExpect(status().isCreated());
        ArgumentCaptor<ReportDefinition> saved = ArgumentCaptor.forClass(ReportDefinition.class);
        verify(definitionRepository).save(saved.capture());
        assertThat(saved.getValue().getRecipients()).isEqualTo("accueil@example.org,direction@example.org");
    }
}

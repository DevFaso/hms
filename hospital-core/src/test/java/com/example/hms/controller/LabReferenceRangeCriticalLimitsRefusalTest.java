package com.example.hms.controller;

import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.service.LabTestDefinitionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Locale;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D896: {@code PUT /lab-test-definitions/{id}/reference-ranges} with a
 * critical high limit inside the reference range answered 400
 * {@code {"message":"Validation failure"}}, in English, with no
 * {@code fieldErrors}: Spring's method validation of the list body was handled
 * as a bare {@code ResponseStatusException}. The refusal is now translated and
 * names the limit.
 */
class LabReferenceRangeCriticalLimitsRefusalTest {

    private static final String RANGES = "/lab-test-definitions/" + UUID.randomUUID() + "/reference-ranges";

    private LabTestDefinitionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(LabTestDefinitionService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setValidationMessageSource(TestMessageSources.bundles());
        validator.afterPropertiesSet();
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        advice.setMessageSource(TestMessageSources.bundles());
        mvc = MockMvcBuilders.standaloneSetup(new LabTestDefinitionController(service, validator))
            .setControllerAdvice(advice)
            .setValidator(validator)
            .build();
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void aCriticalHighInsideTheRangeIsRefusedInFrenchOnItsField() throws Exception {
        // neg.js: glucose 3.9–6.1 with a critical high of 5.0.
        mvc.perform(put(RANGES).locale(Locale.FRENCH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"minValue\":3.9,\"maxValue\":6.1,\"unit\":\"mmol/L\",\"gender\":\"ALL\",\"criticalHigh\":5.0}]"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(
                "Seuil critique haut : il doit être supérieur ou égal au maximum de l'intervalle de référence."))
            .andExpect(jsonPath("$.fieldErrors['[0].criticalHigh']").value(containsString("Seuil critique haut")));
        verifyNoInteractions(service);
    }

    @Test
    void aCriticalLowInsideTheRangeIsNamedOnCriticalLowInSpanish() throws Exception {
        mvc.perform(put(RANGES).locale(Locale.forLanguageTag("es"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"minValue\":3.9,\"maxValue\":6.1},{\"minValue\":3.9,\"maxValue\":6.1,\"criticalLow\":4.5}]"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Límite crítico bajo")))
            .andExpect(jsonPath("$.fieldErrors['[1].criticalLow']").exists());
        verifyNoInteractions(service);
    }

    @Test
    void invertedCriticalLimitsAreOneMessageOnCriticalHigh() throws Exception {
        mvc.perform(put(RANGES).locale(Locale.ENGLISH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"minValue\":3.9,\"maxValue\":6.1,\"criticalLow\":9.0,\"criticalHigh\":2.0}]"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Critical high limit: it must be at or above the critical low limit."))
            .andExpect(jsonPath("$.fieldErrors['[0].criticalHigh']").exists())
            .andExpect(jsonPath("$.fieldErrors['[0].criticalLow']").doesNotExist());
    }

    @Test
    void consistentCriticalLimitsReachTheService() throws Exception {
        mvc.perform(put(RANGES).locale(Locale.FRENCH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"minValue\":3.9,\"maxValue\":6.1,\"criticalLow\":2.5,\"criticalHigh\":25.0}]"))
            .andExpect(status().isOk());
        verify(service).updateReferenceRanges(any(), anyList());
    }

    @Test
    void aDefinitionBodyNamesTheLimitUnderReferenceRanges() throws Exception {
        // Path re-verified: the single POST validates the nested ranges too.
        mvc.perform(post("/lab-test-definitions").locale(Locale.FRENCH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Glycémie\",\"testCode\":\"GLU-R15\",\"referenceRanges\":"
                    + "[{\"minValue\":3.9,\"maxValue\":6.1,\"criticalHigh\":5.0}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors['referenceRanges[0].criticalHigh']")
                .value(containsString("Seuil critique haut")));
        verifyNoInteractions(service);
    }
}

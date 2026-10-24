package com.example.hms.controller;

import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.service.LabTestDefinitionService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D667: POST /lab-test-definitions/batch with an element missing its name
 * returned 500; it is a 400 like the single POST, and nothing is created.
 */
class LabTestDefinitionBatchValidationTest {

    private LabTestDefinitionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(LabTestDefinitionService.class);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        mvc = MockMvcBuilders.standaloneSetup(new LabTestDefinitionController(service, validator))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void incompleteElementIsA400AndNothingIsCreated() throws Exception {
        mvc.perform(post("/lab-test-definitions/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"id\":\"317d5ee5-0000-4000-8000-000000000001\",\"testCode\":\"TDR-R9A\"}]"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.name").exists());
        verifyNoInteractions(service);
    }

    @Test
    void emptyBatchIsA400() throws Exception {
        mvc.perform(post("/lab-test-definitions/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}

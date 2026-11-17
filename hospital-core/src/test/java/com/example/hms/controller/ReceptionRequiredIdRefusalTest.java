package com.example.hms.controller;

import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.ReceptionService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D1258 (round 26): {@code POST /reception/waitlist} with no patient (absent,
 * blank, or an empty body) answered 404 "Patient introuvable avec ID : null"
 * with an empty {@code fieldErrors}: the body was bound without {@code @Valid}
 * and its ids carried no constraint. Now a 400 naming every missing id, before
 * the service (and any lookup) runs.
 */
class ReceptionRequiredIdRefusalTest {

    private final ReceptionService service = mock(ReceptionService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new ReceptionController(service, mock(RoleValidator.class)))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    @BeforeEach
    void bindHospital() {
        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(UUID.randomUUID()).build());
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"patientId\":null,\"departmentId\":null}", "{\"reason\":\"suivi\"}"})
    @DisplayName("D1258: a waitlist entry with no patient and no department names both fields")
    void aWaitlistEntryWithoutItsIdsNamesThem(String body) throws Exception {
        mvc.perform(post("/reception/waitlist").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.patientId").exists())
            .andExpect(jsonPath("$.fieldErrors.departmentId").exists());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"department", "patient"})
    @DisplayName("D1258: one id missing names that field only")
    void oneMissingIdIsNamed(String present) throws Exception {
        String body = "{\"" + present + "Id\":\"" + UUID.randomUUID() + "\"}";
        String missing = "patient".equals(present) ? "departmentId" : "patientId";
        mvc.perform(post("/reception/waitlist").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors." + missing).exists())
            .andExpect(jsonPath("$.fieldErrors." + present + "Id").doesNotExist());
        verifyNoInteractions(service);
    }
}

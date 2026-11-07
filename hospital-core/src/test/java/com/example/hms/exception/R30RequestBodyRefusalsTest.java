package com.example.hms.exception;

import com.example.hms.config.RequestBodyDefaultsModule;
import com.example.hms.enums.ImmunizationStatus;
import com.example.hms.mapper.ImmunizationMapper;
import com.example.hms.model.PatientImmunization;
import com.example.hms.payload.dto.LabQcEventRequestDTO;
import com.example.hms.payload.dto.medicalhistory.ImmunizationRequestDTO;
import com.example.hms.payload.dto.transfer.TransferOrderRequestDTO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA round 30: D1348 (an immunization status is one of the vocabulary) and
 * D1349 (every malformed value of a body, and every field its validation
 * refuses, named in ONE answer), on the real Jackson module, advice and
 * handler. The codebase-wide guard is phase 5 of WriteEndpointRequiredFieldSweepIT.
 */
@DisplayName("R30: malformed request values are all named, with the blank required fields")
class R30RequestBodyRefusalsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().addModule(new RequestBodyDefaultsModule()).build();
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

    private final MalformedRequestBodyAdvice advice = advice(FACTORY.getValidator());
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @BeforeEach
    void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void unbindRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("D1348: a free-text immunization status (FAIT, Donné) is refused by field")
    void aFreeTextImmunizationStatusIsRefused() throws Exception {
        for (String status : new String[] {"FAIT", "Donné", "completed"}) {
            Map<String, ?> errors = refusal(
                "{\"patientId\":\"" + java.util.UUID.randomUUID() + "\",\"hospitalId\":\"" + java.util.UUID.randomUUID()
                    + "\",\"vaccineDisplay\":\"BCG\",\"administrationDate\":\"2026-10-01\",\"status\":\"" + status + "\"}",
                ImmunizationRequestDTO.class, "createImmunization");
            assertThat(errors).as(status).containsOnlyKeys("status");
        }
    }

    @Test
    @DisplayName("D1348: every value of the vocabulary is accepted and stored by its name")
    void everyVocabularyStatusIsAccepted() throws Exception {
        for (ImmunizationStatus status : ImmunizationStatus.values()) {
            RequestBodyProblems.begin();
            ImmunizationRequestDTO dto = MAPPER.readValue("{\"status\":\"" + status.name() + "\"}",
                ImmunizationRequestDTO.class);
            assertThat(RequestBodyProblems.finish()).isEmpty();
            PatientImmunization entity = new ImmunizationMapper().toEntity(dto, null, null, null, null);
            assertThat(entity.getStatus()).isEqualTo(status.name());
        }
    }

    @Test
    @DisplayName("D1348: a row stored before the check (free text) still reads as it was")
    void aLegacyFreeTextRowStillReads() {
        PatientImmunization legacy = new PatientImmunization();
        legacy.setStatus("Fait");
        assertThat(new ImmunizationMapper().toResponseDTO(legacy).getStatus()).isEqualTo("Fait");
    }

    @Test
    @DisplayName("D1349: two malformed values and a blank required field are named in one answer")
    void everyMalformedValueAndBlankRequiredFieldIsNamed() throws Exception {
        Map<String, ?> errors = refusal(
            "{\"admissionId\":\"zz\",\"toBedId\":\"yy\",\"reason\":\"   \"}",
            TransferOrderRequestDTO.class, "transfer");
        assertThat(errors).containsOnlyKeys("admissionId", "toBedId", "reason");
    }

    @Test
    @DisplayName("D1349: a malformed number and a malformed date are both named, with the missing values")
    void numbersAndDatesAreCollectedToo() throws Exception {
        Map<String, ?> errors = refusal(
            "{\"hospitalId\":\"nope\",\"measuredValue\":\"abc\",\"recordedAt\":\"2026-13-45T10:00\"}",
            LabQcEventRequestDTO.class, "qc");
        assertThat(errors).containsKeys("hospitalId", "measuredValue", "recordedAt", "qcLevel", "expectedValue");
    }

    @Test
    @DisplayName("D1349: outside a request body read, a malformed value still fails at once (stored JSON, partners)")
    void outsideARequestBodyReadJacksonStaysStrict() {
        RequestContextHolder.resetRequestAttributes();
        assertThatThrownBy(() -> MAPPER.readValue("{\"admissionId\":\"zz\"}", TransferOrderRequestDTO.class))
            .isInstanceOf(tools.jackson.databind.exc.InvalidFormatException.class);
    }

    @Test
    @DisplayName("D1349: a body without a malformed value passes the advice unchanged (Spring validates it)")
    void aWellFormedBodyPassesUnchanged() throws Exception {
        RequestBodyProblems.begin();
        TransferOrderRequestDTO dto = MAPPER.readValue("{\"reason\":\"   \"}", TransferOrderRequestDTO.class);
        Object out = advice.afterBodyRead(dto, null, parameter("transfer"), TransferOrderRequestDTO.class, null);
        assertThat(out).isSameAs(dto);
    }

    /** Reads the body as MVC does (collector, advice) and returns the handler's fieldErrors. */
    private Map<String, ?> refusal(String json, Class<?> type, String method) throws Exception {
        RequestBodyProblems.begin();
        Object body = MAPPER.readValue(json, type);
        MethodParameter parameter = parameter(method);
        try {
            advice.afterBodyRead(body, null, parameter, type, null);
        } catch (MalformedRequestBodyException refused) {
            ResponseEntity<Object> response = handler.handleMalformedRequestBody(refused,
                new ServletWebRequest(new MockHttpServletRequest()));
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = (Map<String, Object>) response.getBody();
            // Sonar S5838 (round 31): the dedicated map assertion.
            assertThat(responseBody).containsEntry("code", "MALFORMED_REQUEST");
            @SuppressWarnings("unchecked")
            Map<String, ?> errors = (Map<String, ?>) responseBody.get("fieldErrors");
            return errors;
        }
        throw new AssertionError("the body was not refused");
    }

    private static MalformedRequestBodyAdvice advice(Validator validator) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("validator", validator);
        return new MalformedRequestBodyAdvice(beans.getBeanProvider(Validator.class));
    }

    private static MethodParameter parameter(String method) throws NoSuchMethodException {
        for (java.lang.reflect.Method m : Endpoints.class.getDeclaredMethods()) {
            if (m.getName().equals(method)) {
                return new MethodParameter(m, 0);
            }
        }
        throw new NoSuchMethodException(method);
    }

    /** Handler signatures as the controllers declare them (Sonar S1172, round 31: each reads its body). */
    @SuppressWarnings("unused")
    private static final class Endpoints {
        void createImmunization(@jakarta.validation.Valid @RequestBody ImmunizationRequestDTO body) {
            assertThat(body).isNotNull();
        }

        void transfer(@jakarta.validation.Valid @RequestBody TransferOrderRequestDTO body) {
            assertThat(body).isNotNull();
        }

        void qc(@jakarta.validation.Valid @RequestBody LabQcEventRequestDTO body) {
            assertThat(body).isNotNull();
        }
    }
}

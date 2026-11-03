package com.example.hms.exception;

import com.example.hms.config.RequestBodyDefaultsModule;
import com.example.hms.enums.PatientGender;
import com.example.hms.mapper.PatientMapper;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.PatientProfileUpdateRequestDTO;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.payload.dto.StaffPaymentRequestDTO;
import com.example.hms.payload.dto.clinical.BirthPlanRequestDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO;
import com.example.hms.payload.dto.portal.PatientPaymentRequestDTO;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA round 31, the request-body half of branch 3 (D1363, D1364, D1369,
 * D1370, D1373), on the real Jackson module, advice, validator and handler:
 * every malformed or blank field of a body is named in ONE answer. The
 * codebase-wide guards are FreeTextStatusRequestFieldGuardTest (a closed
 * vocabulary is an enum or edge-checked) and RequiredRequestIdGuardTest
 * (nested objects are cascaded with @Valid).
 */
@DisplayName("R31: closed vocabularies, nested sections and list bodies are refused by field, all at once")
class R31RequestBodyRefusalsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().addModule(new RequestBodyDefaultsModule()).build();
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    private final MalformedRequestBodyAdvice advice = advice(VALIDATOR);
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

    // ── D1373: patient sex ──

    @Test
    @DisplayName("D1373: a sex outside the vocabulary (HOMME, X, FEMININ) is refused by field, on create and edit")
    void anUnknownSexIsRefused() {
        for (String gender : new String[] {"HOMME", "X", "FEMININ", "masculin"}) {
            PatientProfileUpdateRequestDTO patch = new PatientProfileUpdateRequestDTO();
            patch.setGender(gender);
            assertThat(violatedFields(patch)).as("PATCH " + gender).containsExactly("gender");
            PatientRequestDTO create = validPatient();
            create.setGender(gender);
            assertThat(violatedFields(create)).as("POST " + gender).containsExactly("gender");
        }
    }

    @Test
    @DisplayName("D1373: every code is accepted in any case and stored as the code; blank is left to the required rule")
    void everyCodeIsAcceptedAndStoredAsTheCode() {
        for (PatientGender gender : PatientGender.values()) {
            for (String sent : new String[] {gender.name(), gender.name().toLowerCase(java.util.Locale.ROOT),
                gender.name().charAt(0) + gender.name().substring(1).toLowerCase(java.util.Locale.ROOT)}) {
                PatientRequestDTO create = validPatient();
                create.setGender(sent);
                assertThat(violatedFields(create)).as(sent).isEmpty();
                Patient patient = new Patient();
                new PatientMapper(null).updatePatientFromDto(create, patient, null);
                assertThat(patient.getGender()).isEqualTo(gender.name());
            }
        }
        for (String alias : new String[] {"F", "m", " f "}) {
            PatientRequestDTO create = validPatient();
            create.setGender(alias);
            assertThat(violatedFields(create)).as("alias " + alias).isEmpty();
            Patient patient = new Patient();
            new PatientMapper(null).updatePatientFromDto(create, patient, null);
            assertThat(patient.getGender()).isEqualTo(alias.trim().equalsIgnoreCase("f") ? "FEMALE" : "MALE");
        }
        PatientRequestDTO blank = validPatient();
        blank.setGender("  ");
        assertThat(violatedFields(blank)).as("one refusal for a blank sex: required, not also invalid")
            .containsExactly("gender");
        assertThat(VALIDATOR.validate(blank).iterator().next().getMessageTemplate()).isEqualTo("{patient.gender.required}");
    }

    @Test
    @DisplayName("D1373: a row stored before the check keeps its text (the readers already read it)")
    void aLegacyGenderIsNotRewritten() {
        assertThat(PatientGender.normalize(null)).isNull();
        assertThat(PatientGender.normalize("")).isEmpty();
        assertThat(PatientGender.normalize(" female ")).isEqualTo("FEMALE");
    }

    // ── D1363: payment method ──

    @Test
    @DisplayName("D1363: an unsupported method is named with a malformed or blank amount, on both payment paths")
    void anUnsupportedMethodIsNamedWithTheAmount() throws Exception {
        // A malformed amount is refused by the advice, with the body's validation in the same answer.
        for (String amount : new String[] {"\"abc\"", "\"mille\""}) {
            assertThat(refusal("{\"amount\":" + amount + ",\"method\":\"TROC\"}", StaffPaymentRequestDTO.class, "staffPay"))
                .as("staff " + amount).containsKeys("amount", "method");
            assertThat(refusal("{\"amount\":" + amount + ",\"paymentMethod\":\"BOGUS\"}", PatientPaymentRequestDTO.class,
                "patientPay")).as("patient " + amount).containsKeys("amount", "paymentMethod");
        }
        // A blank amount is absent (D1316): the body passes to Spring's validation, which names both.
        assertThat(validatedAfterRead("{\"amount\":\"\",\"method\":\"TROC\"}", StaffPaymentRequestDTO.class))
            .containsExactlyInAnyOrder("amount", "method");
        assertThat(validatedAfterRead("{\"amount\":\"\",\"paymentMethod\":\"BOGUS\"}", PatientPaymentRequestDTO.class))
            .contains("amount", "paymentMethod");
        StaffPaymentRequestDTO negative = new StaffPaymentRequestDTO(new java.math.BigDecimal("-5"), "TROC", null, null);
        assertThat(violatedFields(negative)).containsExactlyInAnyOrder("amount", "method");
    }

    @Test
    @DisplayName("D1363: every payment method the till knows is still accepted, in any case")
    void everyKnownMethodIsAccepted() {
        for (com.example.hms.enums.PaymentMethod method : com.example.hms.enums.PaymentMethod.values()) {
            StaffPaymentRequestDTO staff = new StaffPaymentRequestDTO(java.math.BigDecimal.TEN,
                method.name().toLowerCase(java.util.Locale.ROOT), "REF-1", null);
            assertThat(violatedFields(staff)).as(method.name()).isEmpty();
        }
    }

    // ── D1364: notification preferences ──

    @Test
    @DisplayName("D1364: a blank type, channel and on/off are all named as required, not 'enabled malformed' alone")
    void blankPreferenceValuesAreAllNamed() throws Exception {
        RequestBodyProblems.begin();
        List<NotificationPreferenceUpdateDTO> body = MAPPER.readValue(
            "[{\"notificationType\":\"\",\"channel\":\"\",\"enabled\":\"\"}]", new TypeReference<>() { });
        assertThat(RequestBodyProblems.finish()).as("nothing malformed: three blanks, read as absent").isEmpty();
        assertThat(violatedFields(body.get(0))).containsExactlyInAnyOrder("notificationType", "channel", "enabled");
        // Beside a malformed value, the advice names the blanks too.
        Map<String, ?> errors = listRefusal("[{\"notificationType\":\"ZZZ\",\"channel\":\"\",\"enabled\":\"\"}]");
        assertThat(errors).containsOnlyKeys("[0].notificationType", "[0].channel", "[0].enabled");
    }

    @Test
    @DisplayName("D1364: one object sent for the list is read as that one element, so its refusals name fields")
    void oneObjectForTheListIsReadAsOneElement() throws Exception {
        RequestBodyProblems.begin();
        List<NotificationPreferenceUpdateDTO> body = MAPPER.readValue("{\"channel\":\"\"}", new TypeReference<>() { });
        assertThat(RequestBodyProblems.finish()).isEmpty();
        assertThat(body).hasSize(1);
        assertThat(violatedFields(body.get(0))).containsExactlyInAnyOrder("notificationType", "channel", "enabled");
    }

    @Test
    @DisplayName("D1364: outside a request body read, an object for a list still fails at once")
    void outsideARequestBodyReadAListStaysStrict() {
        RequestContextHolder.resetRequestAttributes();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> MAPPER.readValue("{}", new TypeReference<List<NotificationPreferenceUpdateDTO>>() { }))
            .isInstanceOf(tools.jackson.databind.DatabindException.class);
    }

    @Test
    @DisplayName("D1364: a well-formed preference list still reads as before")
    void aWellFormedPreferenceListReads() throws Exception {
        RequestBodyProblems.begin();
        List<NotificationPreferenceUpdateDTO> body = MAPPER.readValue(
            "[{\"notificationType\":\"APPOINTMENT_REMINDER\",\"channel\":\"EMAIL\",\"enabled\":false}]",
            new TypeReference<>() { });
        assertThat(RequestBodyProblems.finish()).isEmpty();
        assertThat(body.get(0).getEnabled()).isFalse();
        assertThat(violatedFields(body.get(0))).isEmpty();
    }

    // ── D1369: birth plan introduction ──

    @Test
    @DisplayName("D1369: the introduction's blank name and due date are named with the rest of the body, by path")
    void theIntroductionIsValidatedWithTheBody() throws Exception {
        Map<String, ?> errors = refusal(
            "{\"introduction\":{\"patientName\":\"  \",\"expectedDueDate\":\"bientot\"},\"flexibilityAcknowledgment\":\"oui\"}",
            BirthPlanRequestDTO.class, "birthPlan");
        assertThat(errors).containsOnlyKeys("introduction.patientName", "introduction.expectedDueDate",
            "flexibilityAcknowledgment");
        BirthPlanRequestDTO blank = new BirthPlanRequestDTO();
        blank.setIntroduction(new BirthPlanRequestDTO.IntroductionDTO());
        assertThat(violatedFields(blank)).containsExactlyInAnyOrder("introduction.patientName",
            "introduction.expectedDueDate", "flexibilityAcknowledgment");
    }

    // ── D1370: lab order ──

    @Test
    @DisplayName("D1370: a malformed priority, status and channel are named with a blank required field")
    void labOrderVocabulariesAreNamedTogether() {
        LabOrderRequestDTO order = new LabOrderRequestDTO();
        order.setPriority("ZZZ");
        order.setStatus("BOF");
        order.setOrderChannel("BOF");
        assertThat(violatedFields(order)).contains("priority", "status", "orderChannel");
        order.setPriority("urgent");
        order.setStatus("ordered");
        order.setOrderChannel("walk-in");
        assertThat(violatedFields(order)).doesNotContain("priority", "status", "orderChannel");
    }

    // ── helpers ──

    private static Set<String> violatedFields(Object bean) {
        Set<String> out = new TreeSet<>();
        VALIDATOR.validate(bean).forEach(v -> out.add(v.getPropertyPath().toString()));
        return out;
    }

    private static PatientRequestDTO validPatient() {
        PatientRequestDTO dto = new PatientRequestDTO();
        dto.setFirstName("Awa");
        dto.setLastName("Kaboré");
        dto.setDateOfBirth(java.time.LocalDate.of(1990, 1, 1));
        dto.setGender("FEMALE");
        dto.setPhoneNumberPrimary("+22670202020");
        dto.setHospitalId(UUID.randomUUID());
        dto.setUserId(UUID.randomUUID());
        return dto;
    }

    /** Reads the body as MVC does; nothing malformed, so the advice passes it and Spring validates it. */
    private Set<String> validatedAfterRead(String json, Class<?> type) throws Exception {
        RequestBodyProblems.begin();
        Object body = MAPPER.readValue(json, type);
        assertThat(RequestBodyProblems.finish()).as("nothing malformed").isEmpty();
        return violatedFields(body);
    }

    private Map<String, ?> listRefusal(String json) throws Exception {
        RequestBodyProblems.begin();
        Object body = MAPPER.readValue(json, new TypeReference<List<NotificationPreferenceUpdateDTO>>() { });
        return refused(body, "preferences", List.class);
    }

    /** Reads the body as MVC does (collector, advice) and returns the handler's fieldErrors. */
    private Map<String, ?> refusal(String json, Class<?> type, String method) throws Exception {
        RequestBodyProblems.begin();
        Object body = MAPPER.readValue(json, type);
        return refused(body, method, type);
    }

    private Map<String, ?> refused(Object body, String method, Class<?> type) throws Exception {
        MethodParameter parameter = parameter(method);
        try {
            advice.afterBodyRead(body, null, parameter, type, null);
        } catch (MalformedRequestBodyException refused) {
            ResponseEntity<Object> response = handler.handleMalformedRequestBody(refused,
                new ServletWebRequest(new MockHttpServletRequest()));
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            Map<?, ?> responseBody = (Map<?, ?>) response.getBody();
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

    /** Handler signatures as the controllers declare them. */
    @SuppressWarnings("unused")
    private static final class Endpoints {
        void staffPay(@jakarta.validation.Valid @RequestBody StaffPaymentRequestDTO body) {
            assertThat(body).isNotNull();
        }

        void patientPay(@jakarta.validation.Valid @RequestBody PatientPaymentRequestDTO body) {
            assertThat(body).isNotNull();
        }

        void preferences(@jakarta.validation.Valid @RequestBody List<NotificationPreferenceUpdateDTO> body) {
            assertThat(body).isNotNull();
        }

        void birthPlan(@jakarta.validation.Valid @RequestBody BirthPlanRequestDTO body) {
            assertThat(body).isNotNull();
        }
    }
}

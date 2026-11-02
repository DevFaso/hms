package com.example.hms.exception;

import com.example.hms.controller.ReceptionController;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.payload.dto.LabTestDefinitionRequestDTO;
import com.example.hms.payload.dto.WaitlistEntryRequestDTO;
import com.example.hms.payload.dto.portal.PatientPaymentRequestDTO;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.ReceptionService;
import com.example.hms.utility.RoleValidator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * QA round 29 unit proofs of the field-named refusals the behavioural sweep
 * ({@code WriteEndpointRequiredFieldSweepIT}) cannot reach by itself.
 */
@DisplayName("R29: refusals name their field")
class R29FieldRefusalsTest {

    @AfterEach
    void reset() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    private static Set<String> violatedPaths(Object body, Class<?>... groups) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Set<ConstraintViolation<Object>> violations = factory.getValidator().validate(body, groups);
            return violations.stream().map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
        }
    }

    private static void callerWith(String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("caller", "n/a", authorities));
    }

    @Test
    @DisplayName("D1318: an empty staff booking names its date, time, clinician, hospital and patient at once")
    void anEmptyStaffBookingNamesEveryPart() {
        callerWith("ROLE_RECEPTIONIST");
        assertThat(violatedPaths(new AppointmentRequestDTO()))
            .contains("appointmentDate", "startTime", "staffId", "hospitalId", "patientNamedForStaffBooking");
    }

    @Test
    @DisplayName("D1318: a patient booking for themselves is not asked to name a patient")
    void aPatientBookingNamesNoPatient() {
        callerWith("ROLE_PATIENT");
        assertThat(violatedPaths(new AppointmentRequestDTO())).doesNotContain("patientNamedForStaffBooking")
            .contains("startTime");
    }

    @Test
    @DisplayName("D1317: the reference rule is keyed transactionReference; a blank method is 'required'")
    void thePaymentRulesAreKeyedOnTheirFields() {
        assertThat(RequestFieldPaths.toJson(PatientPaymentRequestDTO.class, "referenceGivenForNonCashPayment"))
            .isEqualTo("transactionReference");
        PatientPaymentRequestDTO blank = new PatientPaymentRequestDTO();
        blank.setPaymentMethod("  ");
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(blank))
                .filteredOn(v -> v.getPropertyPath().toString().equals("paymentMethod"))
                .extracting(v -> v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())
                .contains("NotBlank").doesNotContain("Size");
        }
    }

    @Test
    @DisplayName("D1338: a blank test name is keyed testName, the request property")
    void theTestNameIsKeyedOnItsJsonName() {
        assertThat(RequestFieldPaths.toJson(LabTestDefinitionRequestDTO.class, "name")).isEqualTo("testName");
        assertThat(RequestFieldPaths.toJson(LabTestDefinitionRequestDTO.class, "testCode")).isEqualTo("testCode");
    }

    @Test
    @DisplayName("D1338 / D1301: a 409 on a typed value carries fieldErrors for that field")
    void aFieldConflictCarriesFieldErrors() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Object body = handler.handleConflictException(new ConflictException("testCode:Code K is already used."),
            new ServletWebRequest(new MockHttpServletRequest())).getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) body;
        assertThat(map).containsEntry("field", "testCode")
            .containsEntry("fieldErrors", Map.of("testCode", "Code K is already used."));
    }

    @Test
    @DisplayName("D1323: an unknown waitlist priority is refused on priority")
    void anUnknownPriorityIsRefused() {
        WaitlistEntryRequestDTO entry = WaitlistEntryRequestDTO.builder()
            .patientId(UUID.randomUUID()).departmentId(UUID.randomUUID()).priority("URGENTISSIME").build();
        assertThat(violatedPaths(entry)).contains("priority");
        entry.setPriority("STAT");
        assertThat(violatedPaths(entry)).doesNotContain("priority");
    }

    @Test
    @DisplayName("D1324: the 'Tous' filter (ALL) lists every status")
    void allMeansEveryStatus() {
        ReceptionService service = mock(ReceptionService.class);
        UUID hospital = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(hospital).build());
        ReceptionController controller = new ReceptionController(service, mock(RoleValidator.class));

        controller.getWaitlist(null, "ALL");
        controller.getWaitlist(null, "all");
        controller.getWaitlist(null, "WAITING");

        verify(service, org.mockito.Mockito.times(2)).getWaitlist(org.mockito.ArgumentMatchers.any(), isNull(), isNull());
        verify(service).getWaitlist(org.mockito.ArgumentMatchers.any(), isNull(), eq("WAITING"));
    }

    @Test
    @DisplayName("D1331: an empty handoff names its situation, an empty care note its narrative, with the rest")
    void theNurseBodiesNameEveryMissingField() {
        assertThat(violatedPaths(new com.example.hms.payload.dto.nurse.NurseHandoffCreateRequestDTO()))
            .contains("situation");
        com.example.hms.payload.dto.nurse.NurseCareNoteRequestDTO note = new com.example.hms.payload.dto.nurse.NurseCareNoteRequestDTO();
        assertThat(violatedPaths(note)).contains("template", "narrative");
        note.setTemplate("DAR");
        note.setDataPart("Patient calme");
        assertThat(violatedPaths(note)).isEmpty();
    }

    @Test
    @DisplayName("D1334 / D1333: a collection names its specimen type")
    void aCollectionNamesItsSpecimenType() {
        assertThat(violatedPaths(new com.example.hms.payload.dto.LabSpecimenRequestDTO())).contains("specimenType");
    }
}

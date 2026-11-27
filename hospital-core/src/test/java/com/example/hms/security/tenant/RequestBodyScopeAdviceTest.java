package com.example.hms.security.tenant;

import com.example.hms.enums.FacilityType;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.EncounterRequestDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.recordaccess.RecordAccessDecision;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.support.UserAccountAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA r81 D4260: what {@link RequestBodyScopeAdvice} lets through and what it refuses, case by
 * case. {@code RequestBodyScopeGuardTest} runs it over every handler; {@code VisitWriteScopeIT}
 * over the real stack.
 */
@DisplayName("r81 D4260: a request body is measured against the acting hospital")
class RequestBodyScopeAdviceTest {

    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID OWN = UUID.randomUUID();
    private static final UUID ALSO_HELD = UUID.randomUUID();
    private static final UUID FOREIGN = UUID.randomUUID();

    private final ActingScopeResolver resolver = mock(ActingScopeResolver.class);
    private final UserAccountAccess access = mock(UserAccountAccess.class);
    private final PatientHospitalRegistrationRepository registrations = mock(PatientHospitalRegistrationRepository.class);
    private final RecordAccessPolicy policy = mock(RecordAccessPolicy.class);
    private final StaffRepository staff = mock(StaffRepository.class);

    private final RequestBodyScopeAdvice advice = new RequestBodyScopeAdvice(
        new Fixed<>(resolver), new Fixed<>(access), new Fixed<>(registrations), new Fixed<>(policy), new Fixed<>(staff));

    /** Handlers to hang the body on. */
    @SuppressWarnings("unused")
    static class Handlers {
        void plain(@RequestBody EncounterRequestDTO body) {
            // a handler with no exemption
        }

        @RequestBodyScopeExempt(fields = "patientId", reason = "test: the patient is the service's to bound")
        void patientLifted(@RequestBody EncounterRequestDTO body) {
            // a handler that lifts the patient
        }

        @HospitalScopeExempt(reason = "test: the hospital is not the acting scope")
        void hospitalExempt(@RequestBody EncounterRequestDTO body) {
            // a handler whose hospital is not its scope
        }

        void many(@RequestBody List<EncounterRequestDTO> bodies) {
            // a list body
        }
    }

    @BeforeEach
    void refusingByDefault() {
        when(resolver.narrowTo(any())).thenReturn(new ActingScope.Refused(ActingScope.Reason.NOT_PERMITTED));
        when(access.organisationHospitalIds()).thenReturn(Set.of());
        when(policy.decide(any(), any(), any())).thenAnswer(call -> RecordAccessDecision.refused(
            call.getArgument(1), call.getArgument(2), call.getArgument(0), null, null));
        when(staff.findHospitalIdAndUserIdById(any())).thenReturn(List.of());
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("a hospital the caller holds no role at is refused with 403")
    void aHospitalNotHeldIsRefused() {
        actingAt(OWN, Set.of(OWN));
        EncounterRequestDTO body = body(FOREIGN, null, null);

        assertThatThrownBy(() -> read("plain", body)).isInstanceOf(HospitalScopeRefusedException.class);
    }

    @Test
    @DisplayName("the acting hospital itself passes without touching the scope")
    void theActingHospitalPasses() {
        actingAt(OWN, Set.of(OWN));

        assertThatCode(() -> read("plain", body(OWN, null, null))).doesNotThrowAnyException();
        verify(resolver, never()).narrowTo(any());
    }

    @Test
    @DisplayName("another hospital the caller holds becomes the acting one")
    void anotherHospitalHeldBecomesTheActingOne() {
        actingAt(OWN, Set.of(OWN, ALSO_HELD));
        when(resolver.narrowTo(ALSO_HELD))
            .thenReturn(new ActingScope.Pinned(ALSO_HELD, ActingScope.Source.REQUESTED));

        assertThatCode(() -> read("plain", body(ALSO_HELD, null, null))).doesNotThrowAnyException();
        verify(resolver).narrowTo(ALSO_HELD);
    }

    @Test
    @DisplayName("a hospital of the organisation an organisation admin administers passes, the scope unchanged")
    void aHospitalOfTheAdministeredOrganisationPasses() {
        actingAt(OWN, Set.of(OWN));
        when(access.organisationHospitalIds()).thenReturn(Set.of(OWN, FOREIGN));

        assertThatCode(() -> read("plain", body(FOREIGN, null, null))).doesNotThrowAnyException();
        assertThat(ActingScopeResolver.pinnedHospitalIdOf(HospitalContextHolder.getContextOrEmpty())).isEqualTo(OWN);
    }

    @Test
    @DisplayName("an organisation admin's hospital of the organisation is not narrowed to, so no refusal is recorded")
    void theOrganisationScopeIsCheckedBeforeAnyRefusal() {
        actingAt(OWN, Set.of(OWN));
        when(access.organisationHospitalIds()).thenReturn(Set.of(OWN, FOREIGN));

        assertThatCode(() -> read("plain", body(FOREIGN, null, null))).doesNotThrowAnyException();
        verify(resolver, never()).narrowTo(any());
    }

    @Test
    @DisplayName("on a write, a hospital where a staff caller is only a patient is refused with 403")
    void aHospitalWhereTheCallerIsOnlyAPatientIsRefusedOnAWrite() {
        treatedAt(ALSO_HELD);
        writing("/api/encounters");

        assertThatThrownBy(() -> read("plain", body(ALSO_HELD, null, null)))
            .isInstanceOf(HospitalScopeRefusedException.class);
    }

    @Test
    @DisplayName("on a write, a patient is measured only against the hospitals where the caller works")
    void aPatientIsMeasuredOnlyWhereTheCallerWorks() {
        treatedAt(ALSO_HELD);
        writing("/api/encounters");
        UUID patient = UUID.randomUUID();
        when(registrations.existsByPatientIdAndHospitalIdIn(eq(patient), any())).thenAnswer(call ->
            call.<java.util.Collection<UUID>>getArgument(1).contains(ALSO_HELD));

        assertThatThrownBy(() -> read("plain", body(null, patient, null)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the patient portal keeps the hospital where the caller is a patient")
    void thePatientPortalKeepsTheCallersOwnHospital() {
        treatedAt(ALSO_HELD);
        writing("/api/me/patient/appointments");
        when(resolver.narrowTo(ALSO_HELD))
            .thenReturn(new ActingScope.Pinned(ALSO_HELD, ActingScope.Source.REQUESTED));

        assertThatCode(() -> read("plain", body(ALSO_HELD, null, null))).doesNotThrowAnyException();
        verify(resolver).narrowTo(ALSO_HELD);
    }

    @Test
    @DisplayName("a handler whose hospital is not its acting scope is left alone")
    void anExemptHandlersHospitalIsLeftAlone() {
        actingAt(OWN, Set.of(OWN));

        assertThatCode(() -> read("hospitalExempt", body(FOREIGN, null, null))).doesNotThrowAnyException();
        verify(resolver, never()).narrowTo(any());
    }

    @Test
    @DisplayName("a patient with no registration and no care relationship at the acting hospital answers 404")
    void aPatientOutOfReachIsUnknown() {
        actingAt(OWN, Set.of(OWN));
        UUID patientId = UUID.randomUUID();

        assertThatThrownBy(() -> read("plain", body(null, patientId, null)))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(registrations).existsByPatientIdAndHospitalIdIn(patientId, Set.of(OWN));
    }

    @Test
    @DisplayName("a patient registered at the acting hospital passes; so does one in the care of its staff")
    void aPatientInReachPasses() {
        actingAt(OWN, Set.of(OWN));
        UUID registered = UUID.randomUUID();
        UUID inCare = UUID.randomUUID();
        when(registrations.existsByPatientIdAndHospitalIdIn(registered, Set.of(OWN))).thenReturn(true);
        when(policy.decide(CALLER, inCare, OWN))
            .thenReturn(RecordAccessDecision.permitted(inCare, OWN, CALLER, null, null));

        assertThatCode(() -> read("plain", body(null, registered, null))).doesNotThrowAnyException();
        assertThatCode(() -> read("plain", body(null, inCare, null))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a handler that lifts the patient leaves it to its service, and still measures the hospital")
    void aLiftedPatientIsLeftToTheService() {
        actingAt(OWN, Set.of(OWN));

        assertThatCode(() -> read("patientLifted", body(OWN, UUID.randomUUID(), null))).doesNotThrowAnyException();
        assertThatThrownBy(() -> read("patientLifted", body(FOREIGN, UUID.randomUUID(), null)))
            .isInstanceOf(HospitalScopeRefusedException.class);
    }

    @Test
    @DisplayName("a staff member of another hospital, and an unknown staff id, answer 404")
    void aStaffMemberOfAnotherHospitalIsUnknown() {
        actingAt(OWN, Set.of(OWN));
        UUID elsewhere = UUID.randomUUID();
        when(staff.findHospitalIdAndUserIdById(elsewhere)).thenReturn(rows(FOREIGN, UUID.randomUUID()));

        assertThatThrownBy(() -> read("plain", body(null, null, elsewhere)))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> read("plain", body(null, null, UUID.randomUUID())))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a staff member of the acting hospital passes; so does the caller's own staff record elsewhere")
    void aStaffMemberOfTheActingHospitalPasses() {
        actingAt(OWN, Set.of(OWN));
        UUID colleague = UUID.randomUUID();
        UUID ownRecordElsewhere = UUID.randomUUID();
        when(staff.findHospitalIdAndUserIdById(colleague)).thenReturn(rows(OWN, UUID.randomUUID()));
        when(staff.findHospitalIdAndUserIdById(ownRecordElsewhere)).thenReturn(rows(FOREIGN, CALLER));

        assertThatCode(() -> read("plain", body(null, null, colleague))).doesNotThrowAnyException();
        assertThatCode(() -> read("plain", body(null, null, ownRecordElsewhere))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("every element of a list body is measured")
    void everyElementOfAListBodyIsMeasured() throws NoSuchMethodException {
        actingAt(OWN, Set.of(OWN));
        MethodParameter parameter = new MethodParameter(Handlers.class.getDeclaredMethod("many", List.class), 0);
        List<EncounterRequestDTO> bodies = List.of(body(OWN, null, null), body(FOREIGN, null, null));

        assertThatThrownBy(() -> advice.afterBodyRead(bodies, null, parameter, parameter.getGenericParameterType(),
            JacksonJsonHttpMessageConverter.class)).isInstanceOf(HospitalScopeRefusedException.class);
    }

    @Test
    @DisplayName("left to the handler: a super-admin, a patient's own request, a provider facility, no tenant context")
    void whoIsLeftToTheHandler() {
        EncounterRequestDTO foreign = body(FOREIGN, UUID.randomUUID(), UUID.randomUUID());

        // No tenant context at all.
        assertThatCode(() -> read("plain", foreign)).doesNotThrowAnyException();

        HospitalContextHolder.setContext(HospitalContext.builder().principalUserId(CALLER).superAdmin(true).build());
        when(resolver.isVerifiedSuperAdmin()).thenReturn(true);
        assertThatCode(() -> read("plain", foreign)).doesNotThrowAnyException();
        when(resolver.isVerifiedSuperAdmin()).thenReturn(false);

        HospitalContextHolder.clear();
        HospitalContextHolder.setContext(HospitalContext.builder().principalUserId(CALLER).patientOwned(true).build());
        assertThatCode(() -> read("plain", foreign)).doesNotThrowAnyException();
        verify(resolver, never()).narrowTo(any());
        verifyNoInteractions(registrations, staff, policy);

        // A pharmacy: the patient and the staff are its services' to bound; the hospital is still measured.
        HospitalContextHolder.clear();
        HospitalContextHolder.setContext(HospitalContext.builder().principalUserId(CALLER)
            .permittedHospitalIds(Set.of(OWN)).activeHospitalId(OWN)
            .hospitalFacilityTypes(Map.of(OWN, FacilityType.PHARMACY)).build());
        assertThatCode(() -> read("plain", body(OWN, UUID.randomUUID(), UUID.randomUUID()))).doesNotThrowAnyException();
        assertThatThrownBy(() -> read("plain", foreign)).isInstanceOf(HospitalScopeRefusedException.class);
        verify(registrations, never()).existsByPatientIdAndHospitalIdIn(any(), eq(Set.of(OWN)));
    }

    /* --------------------------------------------------------------------- */

    private static void actingAt(UUID hospitalId, Set<UUID> held) {
        HospitalContextHolder.clear();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(CALLER)
            .permittedHospitalIds(held)
            .activeHospitalId(hospitalId)
            .hospitalFacilityTypes(Map.of(hospitalId, FacilityType.HOSPITAL))
            .build());
    }

    /** A doctor of OWN who is also a patient at {@code patientAt}: several hospitals, none named. */
    private static void treatedAt(UUID patientAt) {
        HospitalContextHolder.clear();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(CALLER)
            .permittedHospitalIds(Set.of(OWN, patientAt))
            .staffHospitalIds(Set.of(OWN))
            .hospitalRoles(Map.of(OWN, Set.of("ROLE_DOCTOR"), patientAt, Set.of("ROLE_PATIENT")))
            .scopeRefusal(ActingScope.Reason.AMBIGUOUS)
            .hospitalFacilityTypes(Map.of(OWN, FacilityType.HOSPITAL, patientAt, FacilityType.HOSPITAL))
            .build());
    }

    private static void writing(String uri) {
        org.springframework.mock.web.MockHttpServletRequest request =
            new org.springframework.mock.web.MockHttpServletRequest("POST", uri);
        request.setContextPath("/api");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
            new org.springframework.web.context.request.ServletRequestAttributes(request));
    }

    private static EncounterRequestDTO body(UUID hospitalId, UUID patientId, UUID staffId) {
        EncounterRequestDTO body = new EncounterRequestDTO();
        body.setHospitalId(hospitalId);
        body.setPatientId(patientId);
        body.setStaffId(staffId);
        return body;
    }

    private static List<Object[]> rows(UUID hospitalId, UUID userId) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {hospitalId, userId});
        return rows;
    }

    private Object read(String handler, EncounterRequestDTO body) throws NoSuchMethodException {
        MethodParameter parameter = new MethodParameter(
            Handlers.class.getDeclaredMethod(handler, EncounterRequestDTO.class), 0);
        return advice.afterBodyRead(body, null, parameter, EncounterRequestDTO.class,
            JacksonJsonHttpMessageConverter.class);
    }

    /** A provider over one bean. */
    private record Fixed<B>(B bean) implements ObjectProvider<B> {
        @Override
        public B getObject() {
            return bean;
        }

        @Override
        public B getIfAvailable() {
            return bean;
        }
    }
}

package com.example.hms.service.recordaccess;

import com.example.hms.enums.RecordAccessDenialReason;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.enums.RecordAccessPosture;
import com.example.hms.enums.TenantIsolationMode;
import com.example.hms.enums.TreatmentRelationshipKind;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRecordSharingOptOutRepository;
import com.example.hms.repository.StaffRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.example.hms.model.BreakGlassSession;

/**
 * The gates, in the order they close. Each test opens every earlier gate and
 * shuts one, so the reason names exactly the rule under test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RecordAccessPolicyImpl")
class RecordAccessPolicyImplTest {

    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientRecordSharingOptOutRepository optOutRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private TreatmentRelationshipResolver resolver;
    @Mock private com.example.hms.repository.PatientHospitalRegistrationRepository registrationRepository;
    @Mock private BreakGlassGate breakGlassGate;
    @Mock private com.example.hms.repository.PatientRepository patientRepository;

    @InjectMocks private RecordAccessPolicyImpl policy;

    private final UUID actor = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private Hospital hospital;

    @BeforeEach
    void openEveryGate() {
        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setIsolationMode(TenantIsolationMode.ROW_LEVEL);
        hospital.setRecordAccessPosture(RecordAccessPosture.TREATMENT_PRESUMED);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patient)).thenReturn(false);
        Staff staff = new Staff();
        staff.setActive(true);
        when(staffRepository.findByUserIdAndHospitalId(actor, hospitalId)).thenReturn(Optional.of(staff));
        when(resolver.resolve(patient, hospitalId, actor)).thenReturn(Optional.of(new TreatmentRelationship(
            TreatmentRelationshipKind.OPEN_ENCOUNTER, UUID.randomUUID(), hospitalId, patient, null, null, true)));
    }

    @AfterEach
    void noRequestBound() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("every gate open → PERMITTED with the carrier attached")
    void permitted() {
        RecordAccessDecision d = policy.decide(actor, patient, hospitalId);

        assertThat(d.permitted()).isTrue();
        assertThat(d.reason()).isEqualTo(RecordAccessDenialReason.PERMITTED);
        assertThat(d.relationship().kind()).isEqualTo(TreatmentRelationshipKind.OPEN_ENCOUNTER);
        assertThat(d.posture()).isEqualTo(RecordAccessPosture.TREATMENT_PRESUMED);
    }

    @Test
    @DisplayName("unknown hospital → HOSPITAL_UNKNOWN, and nothing else is consulted")
    void hospitalUnknown() {
        UUID other = UUID.randomUUID();
        when(hospitalRepository.findById(other)).thenReturn(Optional.empty());

        RecordAccessDecision d = policy.decide(actor, patient, other);

        assertThat(d.reason()).isEqualTo(RecordAccessDenialReason.HOSPITAL_UNKNOWN);
        verify(resolver, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("a SCHEMA-isolated tenant can never be read across, whatever its posture says")
    void schemaIsolated() {
        hospital.setIsolationMode(TenantIsolationMode.SCHEMA);

        RecordAccessDecision d = policy.decide(actor, patient, hospitalId);

        assertThat(d.permitted()).isFalse();
        assertThat(d.reason()).isEqualTo(RecordAccessDenialReason.SCHEMA_ISOLATED_TENANT);
        verify(resolver, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("EXPLICIT_CONSENT posture refuses the treatment presumption — the legal escape hatch")
    void explicitConsentPosture() {
        hospital.setRecordAccessPosture(RecordAccessPosture.EXPLICIT_CONSENT);

        RecordAccessDecision d = policy.decide(actor, patient, hospitalId);

        assertThat(d.reason()).isEqualTo(RecordAccessDenialReason.HOSPITAL_REQUIRES_CONSENT);
        assertThat(d.posture()).isEqualTo(RecordAccessPosture.EXPLICIT_CONSENT);
        verify(resolver, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("an opted-out patient is refused BEFORE the relationship is looked up, so the refusal discloses nothing")
    void optedOutBeforeRelationship() {
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patient)).thenReturn(true);

        RecordAccessDecision d = policy.decide(actor, patient, hospitalId);

        assertThat(d.reason()).isEqualTo(RecordAccessDenialReason.PATIENT_OPTED_OUT);
        verify(resolver, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("no active staff record at the acting hospital → NOT_STAFF_AT_HOSPITAL")
    void notStaffHere() {
        when(staffRepository.findByUserIdAndHospitalId(actor, hospitalId)).thenReturn(Optional.empty());

        assertThat(policy.decide(actor, patient, hospitalId).reason())
            .isEqualTo(RecordAccessDenialReason.NOT_STAFF_AT_HOSPITAL);
    }

    @Test
    @DisplayName("an INACTIVE staff record does not count as being staff here")
    void inactiveStaff() {
        Staff inactive = new Staff();
        inactive.setActive(false);
        when(staffRepository.findByUserIdAndHospitalId(actor, hospitalId)).thenReturn(Optional.of(inactive));

        assertThat(policy.decide(actor, patient, hospitalId).reason())
            .isEqualTo(RecordAccessDenialReason.NOT_STAFF_AT_HOSPITAL);
    }

    @Test
    @DisplayName("E9 #58 — registration at the acting hospital is the relationship, no carrier needed")
    void registrationAloneIsTheRelationship() {
        when(resolver.resolve(patient, hospitalId, actor)).thenReturn(Optional.empty());
        com.example.hms.model.PatientHospitalRegistration registration =
            new com.example.hms.model.PatientHospitalRegistration();
        registration.setId(UUID.randomUUID());
        registration.setRegistrationDate(java.time.LocalDate.of(2026, 9, 1));
        when(registrationRepository.findByPatientIdAndHospitalId(patient, hospitalId))
            .thenReturn(Optional.of(registration));

        RecordAccessDecision d = policy.decide(actor, patient, hospitalId);

        assertThat(d.permitted()).isTrue();
        assertThat(d.relationship().kind()).isEqualTo(TreatmentRelationshipKind.REGISTRATION);
        assertThat(d.relationship().carrierId()).isEqualTo(registration.getId());
        assertThat(d.relationship().expiresAt()).isNull();
        verify(resolver, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("no registration and no carrier → NO_TREATMENT_RELATIONSHIP")
    void noRelationship() {
        when(resolver.resolve(patient, hospitalId, actor)).thenReturn(Optional.empty());

        RecordAccessDecision d = policy.decide(actor, patient, hospitalId);

        assertThat(d.permitted()).isFalse();
        assertThat(d.reason()).isEqualTo(RecordAccessDenialReason.NO_TREATMENT_RELATIONSHIP);
        assertThat(d.relationship()).isNull();
    }

    @Test
    @DisplayName("within one HTTP request the decision is computed once per (actor, patient, hospital)")
    void cachedPerRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        policy.decide(actor, patient, hospitalId);
        policy.decide(actor, patient, hospitalId);
        policy.decide(actor, patient, hospitalId);

        verify(resolver, times(1)).resolve(patient, hospitalId, actor);
    }

    @Test
    @DisplayName("a different patient in the same request is a different decision")
    void cacheIsKeyed() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        UUID other = UUID.randomUUID();
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(other)).thenReturn(false);
        when(resolver.resolve(other, hospitalId, actor)).thenReturn(Optional.empty());

        assertThat(policy.decide(actor, patient, hospitalId).permitted()).isTrue();
        assertThat(policy.decide(actor, other, hospitalId).permitted()).isFalse();
    }

    @Test
    @DisplayName("E9 #62 (Tier B): no registration and no carrier, but a live break-the-glass session here — permitted, kind BREAK_GLASS")
    void breakGlassStandsInForTheRelationship() {
        when(resolver.resolve(patient, hospitalId, actor)).thenReturn(Optional.empty());
        when(registrationRepository.findByPatientIdAndHospitalId(patient, hospitalId)).thenReturn(Optional.empty());
        BreakGlassSession session = new BreakGlassSession();
        session.setId(UUID.randomUUID());
        session.setStartedAt(java.time.LocalDateTime.now().minusMinutes(5));
        session.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
        when(breakGlassGate.liveSession(actor, patient, hospitalId)).thenReturn(Optional.of(session));

        RecordAccessDecision d = policy.decide(actor, patient, hospitalId);

        assertThat(d.permitted()).isTrue();
        assertThat(d.relationship().kind()).isEqualTo(TreatmentRelationshipKind.BREAK_GLASS);
        assertThat(d.relationship().carrierId()).isEqualTo(session.getId());
        assertThat(d.relationship().expiresAt()).isEqualTo(session.getExpiresAt());
        assertThat(d.relationship().actorDirectlyAttached()).isTrue();
    }

    @Test
    @DisplayName("a break-the-glass session does not bypass the opt-out or the staff gate")
    void breakGlassDoesNotBypassEarlierGates() {
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patient)).thenReturn(true);
        when(breakGlassGate.liveSession(actor, patient, hospitalId)).thenReturn(Optional.of(new BreakGlassSession()));

        RecordAccessDecision d = policy.decide(actor, patient, hospitalId);

        assertThat(d.permitted()).isFalse();
        assertThat(d.reason()).isEqualTo(RecordAccessDenialReason.PATIENT_OPTED_OUT);
        verify(breakGlassGate, never()).liveSession(any(), any(), any());
    }

    // ------------------------------------------------------------------ E8 #54: restricted charts

    private com.example.hms.model.Patient restrictedPatient() {
        com.example.hms.model.Patient p = new com.example.hms.model.Patient();
        p.setId(patient);
        p.setChartRestricted(true);
        when(patientRepository.findByIdUnscoped(patient)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    @DisplayName("a restricted chart refuses a registered clinician without a live session — CHART_RESTRICTED")
    void restrictedChartRefusesRegistrationAlone() {
        restrictedPatient();
        PatientHospitalRegistration reg = new PatientHospitalRegistration();
        reg.setId(UUID.randomUUID());
        when(registrationRepository.findByPatientIdAndHospitalId(patient, hospitalId)).thenReturn(Optional.of(reg));
        when(breakGlassGate.liveSession(actor, patient, hospitalId)).thenReturn(Optional.empty());

        RecordAccessDecision d = policy.decide(actor, patient, hospitalId);

        assertThat(d.permitted()).isFalse();
        assertThat(d.reason()).isEqualTo(RecordAccessDenialReason.CHART_RESTRICTED);
    }

    @Test
    @DisplayName("a restricted chart opens under a live session, and the relationship becomes BREAK_GLASS")
    void restrictedChartOpensUnderASession() {
        restrictedPatient();
        com.example.hms.model.BreakGlassSession session = new com.example.hms.model.BreakGlassSession();
        session.setId(UUID.randomUUID());
        session.setStartedAt(java.time.LocalDateTime.now().minusMinutes(5));
        session.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
        when(breakGlassGate.liveSession(actor, patient, hospitalId)).thenReturn(Optional.of(session));

        RecordAccessDecision d = policy.decide(actor, patient, hospitalId);

        assertThat(d.permitted()).isTrue();
        assertThat(d.relationship().kind()).isEqualTo(TreatmentRelationshipKind.BREAK_GLASS);
        assertThat(d.relationship().actorDirectlyAttached()).isTrue();
    }

    @Test
    @DisplayName("the flag is read only after a relationship exists: a stranger still gets NOT_STAFF_AT_HOSPITAL")
    void restrictionDisclosesNothingToAStranger() {
        restrictedPatient();
        when(staffRepository.findByUserIdAndHospitalId(actor, hospitalId)).thenReturn(Optional.empty());

        RecordAccessDecision d = policy.decide(actor, patient, hospitalId);

        assertThat(d.reason()).isEqualTo(RecordAccessDenialReason.NOT_STAFF_AT_HOSPITAL);
        verify(patientRepository, never()).findByIdUnscoped(patient);
    }
}

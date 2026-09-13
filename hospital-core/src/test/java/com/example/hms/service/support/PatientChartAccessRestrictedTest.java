package com.example.hms.service.support;

import com.example.hms.enums.RecordAccessDenialReason;
import com.example.hms.enums.RecordAccessPosture;
import com.example.hms.enums.TreatmentRelationshipKind;
import com.example.hms.exception.ChartRestrictedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.recordaccess.RecordAccessDecision;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.recordaccess.TreatmentRelationship;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E8 #54 — a restricted chart is refused loudly. Registration used to open the
 * chart before the policy was consulted; for a restricted patient the policy
 * decides, and its CHART_RESTRICTED refusal surfaces as a 403 with a code the
 * portal turns into the declaration prompt, never as the 404 that hides
 * every other refusal.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatientChartAccessRestrictedTest {

    @Mock private PatientRepository patientRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private RecordAccessPolicy policy;

    private PatientChartAccess access;
    private final UUID patientId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private Patient patient;

    @BeforeEach
    void setUp() {
        access = new PatientChartAccess(patientRepository, registrationRepository, policy);
        patient = new Patient();
        patient.setId(patientId);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(actor).activeHospitalId(hospitalId).superAdmin(false).build());
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("an unrestricted registered patient opens without consulting the policy, as before")
    void registrationStillOpensAnUnrestrictedChart() {
        assertThat(access.require(patientId, hospitalId)).isSameAs(patient);
        verify(policy, never()).decide(any(), any(), any());
    }

    @Test
    @DisplayName("a restricted chart without a live session is a 403 CHART_RESTRICTED, not a 404")
    void restrictedWithoutSessionIsLoud() {
        patient.setChartRestricted(true);
        when(policy.decide(actor, patientId, hospitalId)).thenReturn(RecordAccessDecision.refused(
            patientId, hospitalId, actor, RecordAccessDenialReason.CHART_RESTRICTED,
            RecordAccessPosture.TREATMENT_PRESUMED));

        assertThatThrownBy(() -> access.require(patientId, hospitalId))
            .isInstanceOf(ChartRestrictedException.class)
            .hasMessageContaining("break-the-glass");
    }

    @Test
    @DisplayName("a restricted chart opens under a live break-the-glass session")
    void restrictedWithSessionOpens() {
        patient.setChartRestricted(true);
        TreatmentRelationship session = new TreatmentRelationship(
            TreatmentRelationshipKind.BREAK_GLASS, UUID.randomUUID(), hospitalId, patientId, null, null, true);
        when(policy.decide(actor, patientId, hospitalId)).thenReturn(RecordAccessDecision.permitted(
            patientId, hospitalId, actor, session, RecordAccessPosture.TREATMENT_PRESUMED));

        assertThat(access.require(patientId, hospitalId)).isSameAs(patient);
    }

    @Test
    @DisplayName("every other refusal stays a 404, indistinguishable from no such patient")
    void otherRefusalsStayQuiet() {
        patient.setChartRestricted(true);
        when(policy.decide(actor, patientId, hospitalId)).thenReturn(RecordAccessDecision.refused(
            patientId, hospitalId, actor, RecordAccessDenialReason.NOT_STAFF_AT_HOSPITAL,
            RecordAccessPosture.TREATMENT_PRESUMED));

        assertThatThrownBy(() -> access.require(patientId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}

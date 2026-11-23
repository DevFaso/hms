package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientInsuranceMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.OtherHospitalInsuranceDTO;
import com.example.hms.payload.dto.PatientInsuranceResponseDTO;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.billing.PayerCatalogueService;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Owner decision 2026-11-22 (replaces "cross-hospital insurance stays hidden", 2026-11-12): a patient's policy
 * belongs to the patient, the agreement with the insurer to each hospital. Hospital B's desk sees a policy recorded at
 * hospital A, to verify, by its identity only, and only where RecordAccessPolicy lets B read A (the patient's opt-out,
 * A's EXPLICIT_CONSENT or SCHEMA isolation hide it). B can take it over as its OWN coverage, under its own agreement;
 * A's row is never written.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatientInsuranceOtherHospitalTest {

    @Mock private PatientInsuranceRepository patientInsuranceRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private PatientInsuranceMapper patientInsuranceMapper;
    @Mock private MessageSource messageSource;
    @Mock private RoleValidator roleValidator;
    @Mock private PatientChartAccess patientChartAccess;
    @Mock private PatientSubjectReadGuard subjectReadGuard;
    @Mock private ActingScopeResolver actingScopeResolver;
    @Mock private PayerCatalogueService payerCatalogueService;
    @Mock private RecordAccessPolicy recordAccessPolicy;
    @Mock private CrossHospitalReachRecorder reachRecorder;

    @InjectMocks private PatientInsuranceServiceImpl service;

    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID planAtA = UUID.randomUUID();
    private Patient patient;
    private UserRoleHospitalAssignment atA;
    private UserRoleHospitalAssignment atB;
    private PatientInsurance rowA;

    @BeforeEach
    void setUp() {
        patient = Patient.builder().build();
        patient.setId(patientId);
        atA = assignmentAt(hospitalA, "Clinique Saint-Camille");
        atB = assignmentAt(hospitalB, "CHU Yalgado");
        rowA = PatientInsurance.builder()
            .patient(patient)
            .assignment(atA)
            .payerCode("CNAMU")
            .providerName("Caisse Nationale AMU")
            .policyNumber("AMU-778899")
            .groupNumber("GRP-1")
            .subscriberName("Awa Ouedraogo")
            .subscriberRelationship("Conjoint")
            .effectiveDate(LocalDate.of(2026, 1, 1))
            .expirationDate(LocalDate.of(2026, 12, 31))
            .eligibilityNotes("Plafond annuel atteint à 80 % - accord préalable 4411")
            .verifiedBy("agent.a")
            .verifiedAt(java.time.LocalDateTime.of(2026, 3, 1, 9, 0))
            .linkedByUserId(UUID.randomUUID())
            .linkedAs("STAFF")
            .payerPlanId(planAtA)
            .primary(true)
            .build();
        rowA.setId(UUID.randomUUID());
        rowA.setCreatedAt(java.time.LocalDateTime.of(2026, 2, 14, 10, 30));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalB);
        when(subjectReadGuard.callerUserId()).thenReturn(Optional.of(actor));
        when(roleValidator.canLinkPayerPlan(actor, hospitalB)).thenReturn(true);
        when(patientChartAccess.require(patientId, hospitalB)).thenReturn(patient);
        when(payerCatalogueService.labelsOf(anyCollection())).thenReturn(Map.of(planAtA,
            new PayerCatalogueService.PlanLabel(planAtA, "F-A", "Formule Famille", "CNAMU", "Caisse Nationale AMU")));
        when(payerCatalogueService.activeAgreementsAt(hospitalB))
            .thenReturn(List.of(new PayerCatalogueService.Agreement("CNAMU-BF", "Caisse nationale  AMU")));
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(actor, hospitalB))
            .thenReturn(Optional.of(atB));
        when(patientInsuranceRepository.findById(rowA.getId())).thenReturn(Optional.of(rowA));
    }

    private static UserRoleHospitalAssignment assignmentAt(UUID hospitalId, String name) {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName(name);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);
        return assignment;
    }

    private void bReadsA() {
        when(recordAccessPolicy.readableHospitalIds(actor, patientId, hospitalB)).thenReturn(Set.of(hospitalB, hospitalA));
        when(patientInsuranceRepository.findByPatient_IdAndAssignment_Hospital_IdIn(eq(patientId), anyCollection()))
            .thenReturn(List.of(rowA));
    }

    /** The key test: A's policy reaches B by its identity only, to verify; the read is accounted. */
    @Test
    void readShowsAnotherHospitalsPolicyIdentityOnly() {
        bReadsA();

        List<OtherHospitalInsuranceDTO> shown = service.getOtherHospitalPolicies(patientId);

        assertThat(shown).hasSize(1);
        OtherHospitalInsuranceDTO policy = shown.get(0);
        assertThat(policy.getSourceInsuranceId()).isEqualTo(rowA.getId());
        assertThat(policy.getSourceHospitalName()).isEqualTo("Clinique Saint-Camille");
        assertThat(policy.getInsurerName()).isEqualTo("Caisse Nationale AMU");
        assertThat(policy.getPolicyNumber()).isEqualTo("AMU-778899");
        assertThat(policy.getPlanName()).isEqualTo("Formule Famille");
        assertThat(policy.getHolderRelationship()).isEqualTo("Conjoint");
        assertThat(policy.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(policy.getExpirationDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(policy.getDeclaredOn()).isEqualTo(LocalDate.of(2026, 2, 14));
        assertThat(policy.isAgreementHere()).isTrue();
        // Nothing of A's row goes through the full mapper (notes, verifier, plan id, who linked it).
        verify(patientInsuranceMapper, never()).toPatientInsuranceResponseDTO(any());
        verify(patientInsuranceMapper, never()).toPatientInsuranceResponseDTOs(any());
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalB), eq(actor), eq(null),
            eq(Map.of(hospitalA.toString(), 1L)), eq(PatientInsuranceServiceImpl.OTHER_HOSPITAL_READ_DESCRIPTION));
        // The full row by id stays hidden at B (QA D1651 holds).
        assertThatThrownBy(() -> service.getPatientInsuranceById(rowA.getId(), java.util.Locale.FRENCH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * The patient's opt-out, or A's EXPLICIT_CONSENT / SCHEMA posture: RecordAccessPolicy leaves only B readable, so
     * A's policy is not even looked up, and nothing is accounted.
     */
    @Test
    void optOutOrSourcePostureHidesIt() {
        when(recordAccessPolicy.readableHospitalIds(actor, patientId, hospitalB)).thenReturn(Set.of(hospitalB));

        assertThat(service.getOtherHospitalPolicies(patientId)).isEmpty();

        verify(patientInsuranceRepository, never()).findByPatient_IdAndAssignment_Hospital_IdIn(any(), anyCollection());
        verify(reachRecorder, never()).recordReach(any(), any(), any(), any(), anyMap(), anyString());
    }

    @Test
    void aPolicyThisHospitalAlreadyHoldsIsNotRepeated() {
        bReadsA();
        PatientInsurance ownAtB = PatientInsurance.builder().patient(patient).assignment(atB)
            .payerCode("CNAMU-BF").providerName("Caisse Nationale AMU").policyNumber("amu-778899").build();
        when(patientInsuranceRepository.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalB))
            .thenReturn(List.of(ownAtB));

        assertThat(service.getOtherHospitalPolicies(patientId)).isEmpty();
    }

    @Test
    void withoutAnAgreementHereTheBlockSaysSo() {
        bReadsA();
        when(payerCatalogueService.activeAgreementsAt(hospitalB))
            .thenReturn(List.of(new PayerCatalogueService.Agreement("MUGEF", "Mutuelle des fonctionnaires")));

        assertThat(service.getOtherHospitalPolicies(patientId)).singleElement()
            .extracting(OtherHospitalInsuranceDTO::isAgreementHere).isEqualTo(false);
    }

    /** Only the desk (hospital admin, billing specialist, reception) of the acting hospital; a super-admin alone is not one. */
    @Test
    void aCallerWhoIsNotTheDeskIsRefused() {
        bReadsA();
        when(roleValidator.canLinkPayerPlan(actor, hospitalB)).thenReturn(false);

        assertThatThrownBy(() -> service.getOtherHospitalPolicies(patientId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.reuseOtherHospitalPolicy(rowA.getId()))
            .isInstanceOf(AccessDeniedException.class);
        verify(patientInsuranceRepository, never()).findByPatient_IdAndAssignment_Hospital_IdIn(any(), anyCollection());
        verify(patientInsuranceRepository, never()).save(any());
    }

    /** Reuse: B's own row, under B's payer, unverified, no plan, identity fields only; A's row untouched. */
    @Test
    void reuseMakesThisHospitalsOwnUnverifiedCoverageAndNeverWritesTheSource() {
        bReadsA();
        when(patientInsuranceRepository.save(any(PatientInsurance.class))).thenAnswer(call -> {
            PatientInsurance saved = call.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        PatientInsuranceResponseDTO response = new PatientInsuranceResponseDTO();
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(any())).thenReturn(response);

        assertThat(service.reuseOtherHospitalPolicy(rowA.getId())).isSameAs(response);

        ArgumentCaptor<PatientInsurance> written = ArgumentCaptor.forClass(PatientInsurance.class);
        verify(patientInsuranceRepository, times(1)).save(written.capture());
        PatientInsurance own = written.getValue();
        assertThat(own).isNotSameAs(rowA);
        assertThat(own.getAssignment()).isSameAs(atB);
        assertThat(own.getPayerCode()).isEqualTo("CNAMU-BF");
        assertThat(own.getProviderName()).isEqualTo("Caisse nationale  AMU");
        assertThat(own.getPolicyNumber()).isEqualTo("AMU-778899");
        assertThat(own.getSubscriberRelationship()).isEqualTo("Conjoint");
        assertThat(own.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(own.getExpirationDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(own.getVerifiedAt()).isNull();
        assertThat(own.getVerifiedBy()).isNull();
        assertThat(own.getEligibilityNotes()).isNull();
        assertThat(own.getPayerPlanId()).isNull();
        assertThat(own.getGroupNumber()).isNull();
        assertThat(own.getSubscriberName()).isNull();
        assertThat(own.getLinkedByUserId()).isEqualTo(actor);
        // A's row is exactly as it was.
        assertThat(rowA.getAssignment()).isSameAs(atA);
        assertThat(rowA.getPayerPlanId()).isEqualTo(planAtA);
        assertThat(rowA.isPrimary()).isTrue();
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalB), eq(actor), eq(null),
            eq(Map.of(hospitalA.toString(), 1L)), eq(PatientInsuranceServiceImpl.OTHER_HOSPITAL_REUSE_DESCRIPTION));
    }

    /**
     * No agreement with that insurer here: an identity-only record (the insurer as recorded, no plan, unverified), so
     * no payer share is billed until this hospital has its own agreement and links one of its formules.
     */
    @Test
    void reuseWithoutAnAgreementHereMakesAnIdentityOnlyRecord() {
        bReadsA();
        when(payerCatalogueService.activeAgreementsAt(hospitalB)).thenReturn(List.of());
        when(patientInsuranceRepository.save(any(PatientInsurance.class))).thenAnswer(call -> {
            PatientInsurance saved = call.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        service.reuseOtherHospitalPolicy(rowA.getId());

        ArgumentCaptor<PatientInsurance> written = ArgumentCaptor.forClass(PatientInsurance.class);
        verify(patientInsuranceRepository, times(1)).save(written.capture());
        PatientInsurance own = written.getValue();
        assertThat(own.getAssignment()).isSameAs(atB);
        // Review of #1293 (L1): another hospital's free-text code means nothing here; the name as recorded stays.
        assertThat(own.getPayerCode()).isNull();
        assertThat(own.getProviderName()).isEqualTo("Caisse Nationale AMU");
        assertThat(own.getPayerPlanId()).isNull();
        assertThat(own.getVerifiedAt()).isNull();
        verify(payerCatalogueService, never()).requireLinkablePlan(any(), any());
    }

    @Test
    void reuseOfAPolicyTheCallerMayNotReadAnswersAsMissing() {
        when(recordAccessPolicy.readableHospitalIds(actor, patientId, hospitalB)).thenReturn(Set.of(hospitalB));

        assertThatThrownBy(() -> service.reuseOtherHospitalPolicy(rowA.getId()))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(patientInsuranceRepository, never()).save(any());
    }

    /** Only another hospital's row is taken over: this hospital's own, or a shared untagged one, answers as missing. */
    @Test
    void reuseOfAnOwnOrSharedRowAnswersAsMissing() {
        bReadsA();
        PatientInsurance shared = PatientInsurance.builder().patient(patient).payerCode("CNAMU")
            .providerName("Caisse Nationale AMU").policyNumber("X").build();
        shared.setId(UUID.randomUUID());
        when(patientInsuranceRepository.findById(shared.getId())).thenReturn(Optional.of(shared));
        PatientInsurance own = PatientInsurance.builder().patient(patient).assignment(atB).payerCode("CNAMU")
            .providerName("CNAMU Burkina").policyNumber("Y").build();
        own.setId(UUID.randomUUID());
        when(patientInsuranceRepository.findById(own.getId())).thenReturn(Optional.of(own));

        assertThatThrownBy(() -> service.reuseOtherHospitalPolicy(shared.getId()))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.reuseOtherHospitalPolicy(own.getId()))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(patientInsuranceRepository, never()).save(any());
    }

    /**
     * Review of #1293 (L2): under the lock, the reuse finds the row this hospital already holds by the list's own
     * rule (same number, same insurer by name), even under another code: a stale page or a direct call never makes a
     * second row.
     */
    @Test
    void reuseTwiceReturnsTheRowThisHospitalAlreadyHolds() {
        bReadsA();
        PatientInsurance held = PatientInsurance.builder().patient(patient).assignment(atB).payerCode("AMU-OLD")
            .providerName("CAISSE NATIONALE AMU").policyNumber("amu-778899").build();
        when(patientInsuranceRepository.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalB))
            .thenReturn(List.of(held));
        PatientInsuranceResponseDTO response = new PatientInsuranceResponseDTO();
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(held)).thenReturn(response);

        assertThat(service.getOtherHospitalPolicies(patientId)).isEmpty();
        assertThat(service.reuseOtherHospitalPolicy(rowA.getId())).isSameAs(response);
        verify(patientInsuranceRepository, never()).save(any());
    }

    /** Review of #1293 (L3): a shared (untagged) row this hospital sees already holds the policy. */
    @Test
    void aSharedRowThisHospitalSeesAlreadyHoldsThePolicy() {
        bReadsA();
        PatientInsurance shared = PatientInsurance.builder().patient(patient).payerCode("CNAMU")
            .providerName("Caisse Nationale AMU").policyNumber("AMU-778899").build();
        when(patientInsuranceRepository.findByPatient_IdAndAssignmentIsNull(patientId)).thenReturn(List.of(shared));
        PatientInsuranceResponseDTO response = new PatientInsuranceResponseDTO();
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(shared)).thenReturn(response);

        assertThat(service.getOtherHospitalPolicies(patientId)).isEmpty();
        assertThat(service.reuseOtherHospitalPolicy(rowA.getId())).isSameAs(response);
        verify(patientInsuranceRepository, never()).save(any());
    }

    /**
     * Review of #1293 (M1): payer codes are unique only within one hospital. A's "MS" (Mutuelle Alpha) is not B's
     * "MS" (Mutuelle Beta): no agreement here, and the reuse never files Alpha's member number under Beta.
     */
    @Test
    void aCollidingCodeIsAnotherInsurer() {
        rowA.setPayerCode("MS");
        rowA.setProviderName("Mutuelle Alpha");
        bReadsA();
        when(payerCatalogueService.activeAgreementsAt(hospitalB))
            .thenReturn(List.of(new PayerCatalogueService.Agreement("MS", "Mutuelle Beta")));
        when(patientInsuranceRepository.save(any(PatientInsurance.class))).thenAnswer(call -> {
            PatientInsurance saved = call.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        assertThat(service.getOtherHospitalPolicies(patientId)).singleElement()
            .extracting(OtherHospitalInsuranceDTO::isAgreementHere).isEqualTo(false);
        service.reuseOtherHospitalPolicy(rowA.getId());

        ArgumentCaptor<PatientInsurance> written = ArgumentCaptor.forClass(PatientInsurance.class);
        verify(patientInsuranceRepository, times(1)).save(written.capture());
        assertThat(written.getValue().getProviderName()).isEqualTo("Mutuelle Alpha");
        assertThat(written.getValue().getPayerCode()).isNull();
    }

    /** Review of #1293 (M1): same name and code first, then same name; a code alone never; ties keep catalogue order. */
    @Test
    void theAgreementIsChosenByNameThenCodeInCatalogueOrder() {
        PayerCatalogueService.Agreement nameOnly = new PayerCatalogueService.Agreement("AMU-2", "Caisse Nationale AMU");
        PayerCatalogueService.Agreement nameAndCode = new PayerCatalogueService.Agreement("CNAMU", "caisse nationale amu");
        PayerCatalogueService.Agreement codeOnly = new PayerCatalogueService.Agreement("CNAMU", "Autre caisse");
        PayerCatalogueService.Agreement secondNameOnly = new PayerCatalogueService.Agreement("AMU-3", "Caisse Nationale AMU");

        assertThat(PayerCatalogueService.Agreement.bestFor(List.of(codeOnly, nameOnly, nameAndCode),
            "cnamu", "Caisse  Nationale AMU")).contains(nameAndCode);
        assertThat(PayerCatalogueService.Agreement.bestFor(List.of(codeOnly, nameOnly, secondNameOnly),
            "CNAMU", "Caisse Nationale AMU")).contains(nameOnly);
        assertThat(PayerCatalogueService.Agreement.bestFor(List.of(codeOnly), "CNAMU", "Caisse Nationale AMU"))
            .isEmpty();
        assertThat(new PayerCatalogueService.Agreement("CNAMU", null).matches("CNAMU", "Caisse Nationale AMU"))
            .isTrue();
    }
}

package com.example.hms.service.impl;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RefillStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.RefillRequest;
import com.example.hms.payload.dto.medication.PatientMedicationResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import java.util.Map;
import java.util.Set;

@ExtendWith(MockitoExtension.class)
class PatientMedicationServiceImplTest {

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PatientChartAccess patientChartAccess;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private RefillRequestRepository refillRequestRepository;
    @Mock private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;
    @Mock private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;
    @Mock private com.example.hms.service.pharmacy.ReadyForCollectionLookup readyForCollection;
    @Mock private com.example.hms.repository.AdmissionRepository admissionRepository;

    @Mock private com.example.hms.service.pharmacy.PrescriptionControlledResolver controlledResolver;

    @InjectMocks
    private PatientMedicationServiceImpl service;

    private UUID patientId, hospitalId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        patient = new Patient(); patient.setId(patientId);
        hospital = Hospital.builder().build(); hospital.setId(hospitalId);
        // E9 #59c — the read spans the readable set; by default just the acting hospital.
        lenient().when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId)))
            .thenReturn(Set.of(hospitalId));
    }

    @Test
    void getMedications_patientNotFound_throws() {
        when(patientChartAccess.require(eq(patientId), any()))
            .thenThrow(new ResourceNotFoundException("patient.notFound", patientId));
        assertThatThrownBy(() -> service.getMedicationsForPatient(patientId, hospitalId, 10))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getMedications_hospitalNotFound_throws() {
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMedicationsForPatient(patientId, hospitalId, 10))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getMedications_returnsSortedByDate() {
        Prescription p1 = new Prescription();
        p1.setId(UUID.randomUUID());
        p1.setCreatedAt(LocalDateTime.now().minusDays(2));
        p1.setMedicationName("Aspirin");
        p1.setDuration("7 days");

        Prescription p2 = new Prescription();
        p2.setId(UUID.randomUUID());
        p2.setCreatedAt(LocalDateTime.now());
        p2.setMedicationName("Ibuprofen");
        p2.setDuration("14 days");

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId)))
                .thenReturn(List.of(p1, p2));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getMedicationName()).isEqualTo("Ibuprofen"); // newer first
    }

    @Test
    void getMedications_defaultLimit_appliesWhenZero() {
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId)))
                .thenReturn(List.of());

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 0);

        assertThat(result).isEmpty();
    }

    @Test
    void getMedications_usesDisplayNameOverMedicationName() {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setCreatedAt(LocalDateTime.now());
        p.setMedicationName("generic");
        p.setMedicationDisplayName("Brand Name");

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId)))
                .thenReturn(List.of(p));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 10);

        assertThat(result.get(0).getMedicationName()).isEqualTo("Brand Name");
    }

    @Test
    void getMedications_resolvesDurationInWeeks() {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        p.setMedicationName("Med");
        p.setDuration("2 weeks");

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId)))
                .thenReturn(List.of(p));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 10);

        assertThat(result.get(0).getEndDate()).isNotNull();
    }

    @Test
    void getMedications_discontinuedStatus() {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setCreatedAt(LocalDateTime.now());
        p.setMedicationName("Med");
        p.setStatus(PrescriptionStatus.DISCONTINUED);
        p.setSignedAt(LocalDateTime.now().minusDays(1)); // QA D1537: a stopped order was signed first

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId)))
                .thenReturn(List.of(p));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 10);

        assertThat(result.get(0).getStatus()).isEqualTo("DISCONTINUED");
    }

    @Test
    void getMedications_nullStatusWithPastEndDate_completed() {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setCreatedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
        p.setMedicationName("Med");
        p.setDuration("1 days");
        p.setStatus(null);

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId)))
                .thenReturn(List.of(p));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 10);

        assertThat(result.get(0).getStatus()).isEqualTo("COMPLETED");
    }

    // ── Refill visibility ────────────────────────────────────────────
    // refills_allowed / refills_remaining had no consumer anywhere before
    // this — the portal shipped REFILLS_REMAINING and REFILLS_COUNT
    // translations with no data behind them.

    private Prescription refillablePrescription() {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setCreatedAt(LocalDateTime.now());
        p.setMedicationName("Metformin 500mg");
        p.setStatus(PrescriptionStatus.DISPENSED);
        p.setRefillsAllowed(3);
        p.setRefillsRemaining(2);
        p.setRefillsUsed(1);
        return p;
    }

    private void stubMedicationsFor(Prescription p) {
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId)))
                .thenReturn(List.of(p));
    }

    @Test
    void getMedications_carriesRefillCounts() {
        Prescription p = refillablePrescription();
        stubMedicationsFor(p);

        PatientMedicationResponseDTO dto =
                service.getMedicationsForPatient(patientId, hospitalId, 10).get(0);

        assertThat(dto.getRefillsAllowed()).isEqualTo(3);
        assertThat(dto.getRefillsRemaining()).isEqualTo(2);
        assertThat(dto.getRefillsUsed()).isEqualTo(1);
    }

    @Test
    void getMedications_dispensedPrescriptionIsStillRefillable() {
        // A patient asks for a refill precisely because they already collected
        // the medication, so DISPENSED must not read as spent.
        stubMedicationsFor(refillablePrescription());

        assertThat(service.getMedicationsForPatient(patientId, hospitalId, 10).get(0).isRefillable())
                .isTrue();
    }

    @Test
    void getMedications_discontinuedPrescriptionIsNotRefillable() {
        Prescription p = refillablePrescription();
        p.setStatus(PrescriptionStatus.DISCONTINUED);
        p.setSignedAt(LocalDateTime.now().minusDays(1)); // QA D1537: a stopped order was signed first
        stubMedicationsFor(p);

        assertThat(service.getMedicationsForPatient(patientId, hospitalId, 10).get(0).isRefillable())
                .isFalse();
    }

    @Test
    void qaD719_completedControlledSubstanceIsNotRefillable() {
        Prescription p = refillablePrescription();
        p.setControlledSubstance(true);
        stubMedicationsFor(p);

        assertThat(service.getMedicationsForPatient(patientId, hospitalId, 10).get(0).isRefillable())
                .isFalse();
    }

    @Test
    void qaD719Retest_catalogControlledMorphineWithTickFalseIsNotRefillable() {
        Prescription p = refillablePrescription();
        p.setControlledSubstance(false);
        when(controlledResolver.isControlled(p)).thenReturn(true);
        stubMedicationsFor(p);

        PatientMedicationResponseDTO dto = service.getMedicationsForPatient(patientId, hospitalId, 10).get(0);
        assertThat(dto.isRefillable()).isFalse();
        // QA D813: the row names the same reason the refill request is refused with.
        assertThat(dto.getRefillBlockedReason()).isEqualTo("CONTROLLED");
    }

    @Test
    void qaD813_rowSaysWhyARefillIsBlocked() {
        Prescription refillable = refillablePrescription();
        stubMedicationsFor(refillable);
        assertThat(service.getMedicationsForPatient(patientId, hospitalId, 10).get(0).getRefillBlockedReason())
                .isNull();

        refillable.setStatus(com.example.hms.enums.PrescriptionStatus.DISCONTINUED);
        refillable.setSignedAt(LocalDateTime.now().minusDays(1)); // QA D1537: a stopped order was signed first
        PatientMedicationResponseDTO dto = service.getMedicationsForPatient(patientId, hospitalId, 10).get(0);
        assertThat(dto.isRefillable()).isFalse();
        assertThat(dto.getRefillBlockedReason()).isEqualTo("NOT_REFILLABLE");
    }

    @Test
    void getMedications_surfacesTheLatestRefillDecision() {
        Prescription p = refillablePrescription();
        RefillRequest denied = new RefillRequest();
        denied.setId(UUID.randomUUID());
        denied.setPrescription(p);
        denied.setStatus(RefillStatus.DENIED);
        denied.setProviderNotes("Discontinued — see clinic");
        denied.setUpdatedAt(LocalDateTime.now());

        stubMedicationsFor(p);
        when(refillRequestRepository.findByPrescription_IdInOrderByUpdatedAtDesc(List.of(p.getId())))
                .thenReturn(List.of(denied));

        PatientMedicationResponseDTO dto =
                service.getMedicationsForPatient(patientId, hospitalId, 10).get(0);

        assertThat(dto.getRefillRequestStatus()).isEqualTo("DENIED");
        assertThat(dto.getRefillProviderNotes()).isEqualTo("Discontinued — see clinic");
        assertThat(dto.isRefillRequestOpen()).isFalse();
    }

    @Test
    void getMedications_aHeldRequestStillCountsAsOpen() {
        Prescription p = refillablePrescription();
        RefillRequest paused = new RefillRequest();
        paused.setId(UUID.randomUUID());
        paused.setPrescription(p);
        paused.setStatus(RefillStatus.PAUSED);
        paused.setUpdatedAt(LocalDateTime.now());

        stubMedicationsFor(p);
        when(refillRequestRepository.findByPrescription_IdInOrderByUpdatedAtDesc(List.of(p.getId())))
                .thenReturn(List.of(paused));

        assertThat(service.getMedicationsForPatient(patientId, hospitalId, 10).get(0)
                .isRefillRequestOpen()).isTrue();
    }

    @Test
    void getMedications_noRequestHistoryLeavesTheDecisionBlank() {
        stubMedicationsFor(refillablePrescription());

        PatientMedicationResponseDTO dto =
                service.getMedicationsForPatient(patientId, hospitalId, 10).get(0);

        assertThat(dto.getRefillRequestStatus()).isNull();
        assertThat(dto.isRefillRequestOpen()).isFalse();
    }

    @Test
    void getMedications_followsThePatientWithProvenanceAndAccountsTheReach() {
        // E9 #59c — the metformin prescribed at Hôpital B is on the list at
        // Hôpital A with its hospital on the row; the disclosure is accounted.
        UUID otherHospitalId = UUID.randomUUID();
        Hospital other = Hospital.builder().name("Hôpital B").build(); other.setId(otherHospitalId);
        Prescription local = new Prescription(); local.setId(UUID.randomUUID()); local.setHospital(hospital);
        local.setCreatedAt(LocalDateTime.now()); local.setMedicationName("Amlodipine");
        local.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        Prescription foreign = new Prescription(); foreign.setId(UUID.randomUUID()); foreign.setHospital(other);
        foreign.setCreatedAt(LocalDateTime.now().minusDays(1)); foreign.setMedicationName("Metformin");
        foreign.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId)))
            .thenReturn(Set.of(hospitalId, otherHospitalId));
        when(prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId, otherHospitalId)))
            .thenReturn(List.of(local, foreign));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 10);

        assertThat(result).extracting(PatientMedicationResponseDTO::getHospitalId).containsExactly(hospitalId, otherHospitalId);
        assertThat(result.get(1).getHospitalName()).isEqualTo("Hôpital B");
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), any(), isNull(),
            eq(Map.of(otherHospitalId.toString(), 1L)), anyString());
    }

    // -- No hospital scope: the staff path refuses, the portal path reads the patient's own rows --

    @Test
    void staffRead_withNoHospitalScope_refusesInsteadOfReadingEveryHospital() {
        Prescription foreign = new Prescription(); foreign.setId(UUID.randomUUID());
        when(patientChartAccess.require(eq(patientId), isNull())).thenReturn(patient);
        // Stubbed so the test would SEE the leak if the fallback ever ran again.
        lenient().when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(foreign)));

        assertThatThrownBy(() -> service.getMedicationsForPatient(patientId, null, 10))
            .isInstanceOf(ResourceNotFoundException.class)
            .extracting(thrown -> ((ResourceNotFoundException) thrown).getMessageKey())
            .isEqualTo("patient.notFound");

        verify(prescriptionRepository, never()).findByPatient_Id(any(), any());
        org.mockito.Mockito.verifyNoInteractions(reachRecorder);
    }

    @Test
    void portalRead_carriesReadinessOnTheRowWithAFillWaiting_andNoneElsewhere() {
        // G15 AC-12
        Prescription waiting = new Prescription(); waiting.setId(UUID.randomUUID());
        waiting.setCreatedAt(LocalDateTime.now()); waiting.setMedicationName("Amlodipine");
        waiting.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        Prescription other = new Prescription(); other.setId(UUID.randomUUID());
        other.setCreatedAt(LocalDateTime.now().minusDays(1)); other.setMedicationName("Metformin");
        other.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        LocalDateTime readyAt = LocalDateTime.of(2026, 10, 6, 15, 30);
        when(patientChartAccess.requireOwnRecord(patientId)).thenReturn(patient);
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(waiting, other)));
        when(readyForCollection.openPreparations(List.of(waiting, other))).thenReturn(java.util.Map.of(
            waiting.getId(), new com.example.hms.service.pharmacy.ReadyForCollectionLookup.Readiness(
                readyAt, "Pharmacie Centrale")));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatientPortal(patientId, null, 10);

        assertThat(result.get(0).getReadyForCollectionAt()).isEqualTo(readyAt);
        assertThat(result.get(0).getReadyForCollectionPharmacyName()).isEqualTo("Pharmacie Centrale");
        assertThat(result.get(1).getReadyForCollectionAt()).isNull();
        assertThat(result.get(1).getReadyForCollectionPharmacyName()).isNull();
    }

    /** QA D1362 (round 31): the portal's total is every issued prescription, never a draft. */
    @Test
    void d1362_theTotalCountsEveryIssuedPrescription() {
        java.util.List<Prescription> rows = new java.util.ArrayList<>();
        for (com.example.hms.enums.PrescriptionStatus status : java.util.List.of(
                com.example.hms.enums.PrescriptionStatus.SIGNED, com.example.hms.enums.PrescriptionStatus.DRAFT,
                com.example.hms.enums.PrescriptionStatus.SIGNED, com.example.hms.enums.PrescriptionStatus.DISCONTINUED)) {
            Prescription p = new Prescription();
            p.setId(UUID.randomUUID());
            p.setStatus(status);
            rows.add(p);
        }
        when(patientChartAccess.requireOwnRecord(patientId)).thenReturn(patient);
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(rows));
        long expected = rows.stream().filter(com.example.hms.service.support.PrescriptionIssuance::wasIssued).count();

        assertThat(service.countMedicationsForPatientPortal(patientId)).isEqualTo(expected).isLessThan(rows.size());
    }

    private Prescription d1537Row(String name, com.example.hms.enums.PrescriptionStatus status, boolean signed) {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setMedicationName(name);
        p.setCreatedAt(LocalDateTime.now());
        p.setStatus(status);
        if (signed) {
            p.setSignedAt(LocalDateTime.now().minusHours(1));
        }
        return p;
    }

    /**
     * QA D1537 (round 40): a draft cancelled before anyone signed it was never the patient's medicine.
     * It is not a past medication, not counted; a signed order that was discontinued still is.
     */
    @Test
    void d1537_aDiscardedDraftIsNeitherListedNorCountedForThePatient() {
        Prescription discardedDraft = d1537Row("Totapen", com.example.hms.enums.PrescriptionStatus.CANCELLED, false);
        Prescription discardedUnsigned = d1537Row("Doliprane", com.example.hms.enums.PrescriptionStatus.DISCONTINUED, false);
        Prescription stoppedAfterSigning = d1537Row("Amoxicilline", com.example.hms.enums.PrescriptionStatus.DISCONTINUED, true);
        Prescription cancelledAfterSigning = d1537Row("Ibuprofene", com.example.hms.enums.PrescriptionStatus.CANCELLED, true);
        Prescription liveDraft = d1537Row("Efferalgan", com.example.hms.enums.PrescriptionStatus.DRAFT, false);
        List<Prescription> rows = List.of(discardedDraft, discardedUnsigned, stoppedAfterSigning, cancelledAfterSigning, liveDraft);
        when(patientChartAccess.requireOwnRecord(patientId)).thenReturn(patient);
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(rows));

        assertThat(service.getMedicationsForPatientPortal(patientId, null, 50))
            .extracting(PatientMedicationResponseDTO::getMedicationName)
            .containsExactlyInAnyOrder("Amoxicilline", "Ibuprofene");
        assertThat(service.getMedicationsPageForPatientPortal(patientId, org.springframework.data.domain.PageRequest.of(0, 20))
            .getContent()).extracting(PatientMedicationResponseDTO::getMedicationName)
            .containsExactlyInAnyOrder("Amoxicilline", "Ibuprofene");
        assertThat(service.countMedicationsForPatientPortal(patientId)).isEqualTo(2);
    }

    /** QA D1537: the clinician keeps a live draft (on hold), never a discarded one as a stopped course. */
    @Test
    void d1537_theStaffListKeepsALiveDraftButNotADiscardedOne() {
        Prescription discardedDraft = d1537Row("Totapen", com.example.hms.enums.PrescriptionStatus.CANCELLED, false);
        Prescription stoppedAfterSigning = d1537Row("Amoxicilline", com.example.hms.enums.PrescriptionStatus.DISCONTINUED, true);
        Prescription liveDraft = d1537Row("Efferalgan", com.example.hms.enums.PrescriptionStatus.DRAFT, false);
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId))).thenReturn(Set.of(hospitalId));
        when(prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId)))
            .thenReturn(List.of(discardedDraft, stoppedAfterSigning, liveDraft));

        assertThat(service.getMedicationsForPatient(patientId, hospitalId, 50))
            .extracting(PatientMedicationResponseDTO::getMedicationName)
            .containsExactlyInAnyOrder("Amoxicilline", "Efferalgan");
    }

    @Test
    void staffRead_carriesReadinessToo() {
        // G15 AC-12: the staff medication list shows the same fact.
        Prescription waiting = new Prescription(); waiting.setId(UUID.randomUUID());
        waiting.setCreatedAt(LocalDateTime.now()); waiting.setMedicationName("Amlodipine");
        waiting.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId)))
            .thenReturn(List.of(waiting));
        when(readyForCollection.openPreparations(List.of(waiting))).thenReturn(java.util.Map.of(
            waiting.getId(), new com.example.hms.service.pharmacy.ReadyForCollectionLookup.Readiness(
                LocalDateTime.of(2026, 10, 6, 15, 30), "Pharmacie Centrale")));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatient(patientId, hospitalId, 10);

        assertThat(result.get(0).getReadyForCollectionPharmacyName()).isEqualTo("Pharmacie Centrale");
    }

    @Test
    void D152_portalRead_leavesOutADraft_andEndsAStayOrderWithTheStay() {
        Prescription draft = new Prescription(); draft.setId(UUID.randomUUID());
        draft.setCreatedAt(LocalDateTime.now()); draft.setMedicationName("Brouillon");
        draft.setStatus(com.example.hms.enums.PrescriptionStatus.DRAFT);
        Prescription stayOrder = new Prescription(); stayOrder.setId(UUID.randomUUID());
        stayOrder.setCreatedAt(LocalDateTime.now().minusDays(1)); stayOrder.setMedicationName("Ceftriaxone");
        stayOrder.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        com.example.hms.model.Encounter closedStay = new com.example.hms.model.Encounter();
        closedStay.setAdmissionId(UUID.randomUUID());
        closedStay.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
        stayOrder.setEncounter(closedStay);
        when(patientChartAccess.requireOwnRecord(patientId)).thenReturn(patient);
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(draft, stayOrder)));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatientPortal(patientId, null, 10);

        assertThat(result).extracting(PatientMedicationResponseDTO::getMedicationName).containsExactly("Ceftriaxone");
        // D136: discharged, the stay's antibiotic is no longer "Actif".
        assertThat(result.get(0).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void D136_anOrderANonAttendingClinicianWroteDuringTheStay_endsWithTheDischarge() {
        // Not filed on the stay encounter (only the attending's orders are):
        // the MAR already hid it after discharge, the patient still saw "Actif".
        LocalDateTime admitted = LocalDateTime.now().minusDays(3);
        Prescription consultOrder = new Prescription(); consultOrder.setId(UUID.randomUUID());
        consultOrder.setCreatedAt(admitted.plusDays(1)); consultOrder.setMedicationName("Paracetamol");
        consultOrder.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        Prescription afterwards = new Prescription(); afterwards.setId(UUID.randomUUID());
        afterwards.setCreatedAt(LocalDateTime.now()); afterwards.setMedicationName("Amlodipine");
        afterwards.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        com.example.hms.model.Admission stay = new com.example.hms.model.Admission();
        stay.setAdmissionDateTime(admitted);
        stay.setActualDischargeDateTime(admitted.plusDays(2));
        when(patientChartAccess.requireOwnRecord(patientId)).thenReturn(patient);
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(consultOrder, afterwards)));
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId)).thenReturn(List.of(stay));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatientPortal(patientId, null, 10);

        assertThat(result).extracting(PatientMedicationResponseDTO::getMedicationName, PatientMedicationResponseDTO::getStatus)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("Amlodipine", "ACTIVE"),
                org.assertj.core.groups.Tuple.tuple("Paracetamol", "COMPLETED"));
    }

    @Test
    void D316_aSameDayVisitPrescription_isStillActive_whenAStayOpenedAndClosedThatDay() {
        // QA round 6: admitted and discharged the same morning, then seen in
        // clinic; the clinic's prescription read "Terminé" the day it was written.
        LocalDateTime admitted = LocalDateTime.now().minusHours(6);
        com.example.hms.model.Encounter visit = new com.example.hms.model.Encounter();
        visit.setAdmissionId(null);
        visit.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
        Prescription clinicOrder = new Prescription(); clinicOrder.setId(UUID.randomUUID());
        clinicOrder.setCreatedAt(admitted.plusHours(1)); clinicOrder.setMedicationName("Amoxicillin");
        clinicOrder.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        clinicOrder.setEncounter(visit);
        com.example.hms.model.Admission stay = new com.example.hms.model.Admission();
        stay.setAdmissionDateTime(admitted);
        stay.setActualDischargeDateTime(admitted.plusHours(3));
        when(patientChartAccess.requireOwnRecord(patientId)).thenReturn(patient);
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(clinicOrder)));
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId)).thenReturn(List.of(stay));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatientPortal(patientId, null, 10);

        assertThat(result).extracting(PatientMedicationResponseDTO::getStatus).containsExactly("ACTIVE");
    }

    @Test
    void portalRead_withNoHospitalScope_returnsThePatientsOwnRows_withoutTheStaffGate() {
        Prescription own = new Prescription(); own.setId(UUID.randomUUID());
        own.setCreatedAt(LocalDateTime.now()); own.setMedicationName("Amlodipine");
        own.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        when(patientChartAccess.requireOwnRecord(patientId)).thenReturn(patient);
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(own)));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatientPortal(patientId, null, 10);

        assertThat(result).extracting(PatientMedicationResponseDTO::getMedicationName).containsExactly("Amlodipine");
        verify(patientChartAccess, never()).require(any(), any());
        org.mockito.Mockito.verifyNoInteractions(reachRecorder);
    }

    @Test
    void portalRead_withAHospitalId_showsEveryHospitalsRows_andDisclosesNothing() {
        // A patient registered at two hospitals, the portal passing one of
        // them: the patient is not a staff reader of their own chart, so both
        // hospitals' prescriptions come back, the record policy is never asked
        // and no cross-hospital disclosure is written.
        UUID otherHospitalId = UUID.randomUUID();
        Hospital other = Hospital.builder().name("Hôpital B").build(); other.setId(otherHospitalId);
        Prescription local = new Prescription(); local.setId(UUID.randomUUID()); local.setHospital(hospital);
        local.setCreatedAt(LocalDateTime.now()); local.setMedicationName("Amlodipine");
        local.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        Prescription foreign = new Prescription(); foreign.setId(UUID.randomUUID()); foreign.setHospital(other);
        foreign.setCreatedAt(LocalDateTime.now().minusDays(1)); foreign.setMedicationName("Metformin");
        foreign.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        when(patientChartAccess.requireOwnRecord(patientId)).thenReturn(patient);
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(local, foreign)));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatientPortal(patientId, hospitalId, 10);

        assertThat(result).extracting(PatientMedicationResponseDTO::getHospitalId)
            .containsExactly(hospitalId, otherHospitalId);
        verify(recordAccessPolicy, never()).readableHospitalIds(any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(reachRecorder);
    }

    @Test
    void staffRead_neverTakesTheOwnRecordResolver() {
        when(patientChartAccess.require(patientId, hospitalId)).thenReturn(patient);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId))).thenReturn(List.of());

        service.getMedicationsForPatient(patientId, hospitalId, 10);

        verify(patientChartAccess, never()).requireOwnRecord(any());
    }

    /**
     * QA D1346 (round 30): PATIENT_A2 had three running medicines older than
     * twenty cancelled orders; the portal asked for 20 and got the twenty
     * cancelled ones. Every active course comes first, whatever the limit;
     * the limit bounds only the history after it.
     */
    @Test
    void d1346_everyActiveCourseIsListedFirst_whateverTheLimitAndTheCancelledOrders() {
        LocalDateTime start = LocalDateTime.now().minusDays(3);
        List<Prescription> rows = new java.util.ArrayList<>();
        List<UUID> running = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Prescription p = rx("Amoxicilline " + i, PrescriptionStatus.SIGNED, start.plusMinutes(i));
            rows.add(p);
            running.add(p.getId());
        }
        for (int i = 0; i < 25; i++) {
            rows.add(rx("Paracétamol " + i, PrescriptionStatus.CANCELLED, start.plusHours(1).plusMinutes(i)));
        }
        when(patientChartAccess.requireOwnRecord(patientId)).thenReturn(patient);
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(rows));

        List<PatientMedicationResponseDTO> result = service.getMedicationsForPatientPortal(patientId, null, 20);

        assertThat(result).hasSize(23);
        assertThat(result.subList(0, 3)).extracting(PatientMedicationResponseDTO::getId)
            .containsExactly(running.get(2), running.get(1), running.get(0));
        assertThat(result.subList(0, 3)).extracting(PatientMedicationResponseDTO::getStatus).containsOnly("ACTIVE");
        assertThat(result.subList(3, 23)).extracting(PatientMedicationResponseDTO::getStatus)
            .containsOnly("DISCONTINUED");
        assertThat(service.getMedicationsForPatientPortal(patientId, null, 1)).hasSize(4);
    }

    private static Prescription rx(String name, PrescriptionStatus status, LocalDateTime createdAt) {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setMedicationName(name);
        p.setStatus(status);
        p.setCreatedAt(createdAt);
        if (status != null && !status.isProposal()) {
            p.setSignedAt(createdAt); // QA D1537: every order past a proposal was signed
        }
        return p;
    }

    /** QA D1398: the patient takes it at home; "q6h" alone reads as a fixed schedule. */
    @Test
    void d1398_theRowSaysItIsAsNeededAndItsLeastInterval() {
        Prescription prn = issued("Paracétamol", LocalDateTime.now().minusDays(1));
        prn.setAsNeeded(true);
        prn.setPrnMinIntervalHours(6);
        Prescription fixed = issued("Amoxicilline", LocalDateTime.now().minusDays(2));
        when(patientChartAccess.requireOwnRecord(patientId)).thenReturn(patient);
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(prn, fixed)));

        List<PatientMedicationResponseDTO> rows = service.getMedicationsForPatientPortal(patientId, null, 10);

        assertThat(rows.get(0).isAsNeeded()).isTrue();
        assertThat(rows.get(0).getPrnMinIntervalHours()).isEqualTo(6);
        assertThat(rows.get(1).isAsNeeded()).isFalse();
        assertThat(rows.get(1).getPrnMinIntervalHours()).isNull();
    }

    /** QA D1399: 20 of 249 past medicines with nothing to say so; every row is on a page, with the total. */
    @Test
    void d1399_thePagesReachEveryRowWithTheServersTotal() {
        List<Prescription> rows = new java.util.ArrayList<>();
        LocalDateTime sameMoment = LocalDateTime.of(2026, 11, 1, 9, 0);
        for (int i = 0; i < 45; i++) {
            Prescription past = issued("Passé " + i, sameMoment);
            past.setStatus(com.example.hms.enums.PrescriptionStatus.CANCELLED);
            past.setSignedAt(LocalDateTime.now().minusDays(1)); // QA D1537: a stopped order was signed first
            rows.add(past);
        }
        when(patientChartAccess.requireOwnRecord(patientId)).thenReturn(patient);
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
            .thenAnswer(read -> new org.springframework.data.domain.PageImpl<>(new java.util.ArrayList<>(rows)));

        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (int page = 0; page < 3; page++) {
            // The repository's order is not promised between reads: each page re-reads it reversed.
            java.util.Collections.reverse(rows);
            org.springframework.data.domain.Page<PatientMedicationResponseDTO> slice = service
                .getMedicationsPageForPatientPortal(patientId, org.springframework.data.domain.PageRequest.of(page, 20));
            assertThat(slice.getTotalElements()).isEqualTo(45);
            slice.getContent().forEach(row -> seen.add(row.getId()));
        }
        assertThat(seen).as("a tie on the date never moves a row to another page").hasSize(45);
    }

    @Test
    void d1399_thePagesPutActiveCoursesFirstAndLeaveOutDrafts() {
        Prescription old = issued("Ancien actif", LocalDateTime.now().minusDays(9));
        Prescription recentPast = issued("Récent arrêté", LocalDateTime.now().minusDays(1));
        recentPast.setStatus(com.example.hms.enums.PrescriptionStatus.CANCELLED);
        recentPast.setSignedAt(LocalDateTime.now().minusDays(1)); // QA D1537: a stopped order was signed first
        Prescription draft = issued("Brouillon", LocalDateTime.now());
        draft.setStatus(com.example.hms.enums.PrescriptionStatus.DRAFT);
        when(patientChartAccess.requireOwnRecord(patientId)).thenReturn(patient);
        when(prescriptionRepository.findByPatient_Id(eq(patientId), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(draft, recentPast, old)));

        org.springframework.data.domain.Page<PatientMedicationResponseDTO> page = service
            .getMedicationsPageForPatientPortal(patientId, org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(PatientMedicationResponseDTO::getMedicationName)
            .containsExactly("Ancien actif", "Récent arrêté");
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(service.getMedicationsPageForPatientPortal(patientId,
            org.springframework.data.domain.PageRequest.of(5, 20)).getContent()).as("past the end").isEmpty();
    }

    private static Prescription issued(String name, LocalDateTime createdAt) {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setMedicationName(name);
        rx.setCreatedAt(createdAt);
        rx.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        return rx;
    }
}

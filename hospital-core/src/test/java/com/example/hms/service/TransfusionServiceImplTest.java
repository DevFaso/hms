package com.example.hms.service;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.BloodUnitStatus;
import com.example.hms.enums.RhFactor;
import com.example.hms.enums.TransfusionAdministrationStatus;
import com.example.hms.enums.TransfusionReactionSeverity;
import com.example.hms.enums.TransfusionReactionType;
import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.enums.TransfusionUrgency;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.BloodUnit;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientBloodGroup;
import com.example.hms.model.Staff;
import com.example.hms.model.TransfusionAdministration;
import com.example.hms.model.TransfusionCrossmatch;
import com.example.hms.model.TransfusionReaction;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.payload.dto.transfusion.CrossmatchRequestDTO;
import com.example.hms.payload.dto.transfusion.PatientBloodGroupRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionAdministrationRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionReactionRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestRequestDTO;
import com.example.hms.repository.BloodUnitRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientBloodGroupRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.TransfusionAdministrationRepository;
import com.example.hms.repository.TransfusionCrossmatchRepository;
import com.example.hms.repository.TransfusionReactionRepository;
import com.example.hms.repository.TransfusionRequestRepository;
import com.example.hms.service.impl.TransfusionServiceImpl;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The transfusion loop (Tier 2 item 28).
 *
 * <p>The ABO/Rh matrix itself is pinned exhaustively in {@code AboGroupTest}.
 * What is pinned here is that the SERVICE actually consults it and fails
 * closed — a compatibility table nothing calls is the defect class this whole
 * tier exists to fix.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransfusionServiceImplTest {

    @Mock private PatientBloodGroupRepository bloodGroupRepository;
    @Mock private TransfusionRequestRepository requestRepository;
    @Mock private BloodUnitRepository unitRepository;
    @Mock private TransfusionCrossmatchRepository crossmatchRepository;
    @Mock private TransfusionAdministrationRepository administrationRepository;
    @Mock private TransfusionReactionRepository reactionRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private PatientChartAccess patientChartAccess;
    @Mock private RoleValidator roleValidator;
    @Spy private TransfusionMapper mapper = new TransfusionMapper();

    @InjectMocks private TransfusionServiceImpl service;

    private UUID hospitalId;
    private UUID patientId;
    private UUID callerUserId;
    private Hospital hospital;
    private Patient patient;
    private Staff caller;
    private Staff second;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        callerUserId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        patient = new Patient();
        patient.setId(patientId);

        caller = new Staff();
        caller.setId(UUID.randomUUID());
        caller.setHospital(hospital);

        second = new Staff();
        second.setId(UUID.randomUUID());
        second.setHospital(hospital);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(callerUserId);
        when(staffRepository.findByUserIdAndHospitalId(callerUserId, hospitalId)).thenReturn(Optional.of(caller));
        when(staffRepository.findById(second.getId())).thenReturn(Optional.of(second));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientChartAccess.require(any(), any())).thenReturn(patient);
        when(bloodGroupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(requestRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(unitRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(crossmatchRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(administrationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(reactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(unitRepository.findByRequest_IdOrderByUnitNumberAsc(any())).thenReturn(List.of());
        when(crossmatchRepository.findByRequest_IdOrderByPerformedAtDesc(any())).thenReturn(List.of());
        when(reactionRepository.findByAdministration_IdOrderByOnsetAtDesc(any())).thenReturn(List.of());
    }

    private PatientBloodGroup group(AboGroup abo, RhFactor rh, AntibodyScreenResult screen) {
        return PatientBloodGroup.builder()
            .patient(patient)
            .hospital(hospital)
            .aboGroup(abo)
            .rhFactor(rh)
            .antibodyScreen(screen)
            .performedAt(LocalDateTime.now().minusHours(1))
            .expiresAt(LocalDateTime.now().plusHours(48))
            .superseded(Boolean.FALSE)
            .build();
    }

    private BloodUnit unit(AboGroup abo, RhFactor rh, BloodProductType product, BloodUnitStatus status) {
        BloodUnit u = BloodUnit.builder()
            .hospital(hospital)
            .unitNumber("U-" + abo + rh)
            .productType(product)
            .aboGroup(abo)
            .rhFactor(rh)
            .expiresOn(LocalDate.now().plusDays(20))
            .status(status)
            .build();
        u.setId(UUID.randomUUID());
        when(unitRepository.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    private TransfusionRequest request(PatientBloodGroup grp, TransfusionUrgency urgency) {
        TransfusionRequest r = TransfusionRequest.builder()
            .patient(patient)
            .hospital(hospital)
            .bloodGroup(grp)
            .productType(BloodProductType.PACKED_RED_CELLS)
            .unitsRequested(2)
            .indication("Postpartum haemorrhage")
            .urgency(urgency)
            .status(TransfusionRequestStatus.REQUESTED)
            .requestedAt(LocalDateTime.now())
            .build();
        r.setId(UUID.randomUUID());
        when(requestRepository.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    // ── Type and screen ─────────────────────────────────────────────────

    @Test
    void recordingATypeAndScreenSupersedesThePreviousOne() {
        PatientBloodGroup existing = group(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE);
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patientId, hospitalId))
            .thenReturn(Optional.of(existing));

        service.recordBloodGroup(PatientBloodGroupRequestDTO.builder()
            .patientId(patientId)
            .aboGroup(AboGroup.A)
            .rhFactor(RhFactor.POSITIVE)
            .antibodyScreen(AntibodyScreenResult.NEGATIVE)
            .build());

        assertThat(existing.getSuperseded()).isTrue();
    }

    @Test
    void aChangedBloodGroupIsRefusedWithoutACorrectionReason() {
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patientId, hospitalId))
            .thenReturn(Optional.of(group(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE)));

        assertThatThrownBy(() -> service.recordBloodGroup(PatientBloodGroupRequestDTO.builder()
            .patientId(patientId)
            .aboGroup(AboGroup.B)
            .rhFactor(RhFactor.POSITIVE)
            .antibodyScreen(AntibodyScreenResult.NEGATIVE)
            .build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("A blood group does not change");
    }

    @Test
    void aChangedBloodGroupIsAcceptedWhenDeclaredACorrection() {
        PatientBloodGroup existing = group(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE);
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patientId, hospitalId))
            .thenReturn(Optional.of(existing));

        service.recordBloodGroup(PatientBloodGroupRequestDTO.builder()
            .patientId(patientId)
            .aboGroup(AboGroup.B)
            .rhFactor(RhFactor.POSITIVE)
            .antibodyScreen(AntibodyScreenResult.NEGATIVE)
            .correctionReason("Original specimen was mislabelled")
            .build());

        assertThat(existing.getSuperseded()).isTrue();
    }

    // ── Requests ────────────────────────────────────────────────────────

    @Test
    void aRoutineRequestNeedsATypeAndScreenOnFile() {
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patientId, hospitalId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRequest(TransfusionRequestRequestDTO.builder()
            .patientId(patientId)
            .productType(BloodProductType.PACKED_RED_CELLS)
            .unitsRequested(2)
            .indication("Anaemia")
            .build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("no type and screen on record");
    }

    @Test
    void anEmergencyRequestProceedsWithNoTypeOnFile() {
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patientId, hospitalId))
            .thenReturn(Optional.empty());

        // Refusing this would block a resuscitation, which is the whole reason
        // emergency release exists.
        service.createRequest(TransfusionRequestRequestDTO.builder()
            .patientId(patientId)
            .productType(BloodProductType.PACKED_RED_CELLS)
            .unitsRequested(2)
            .indication("Massive postpartum haemorrhage")
            .urgency(TransfusionUrgency.EMERGENCY)
            .build());

        verify(requestRepository).save(any(TransfusionRequest.class));
    }

    @Test
    void cancellingAReleasesHeldUnits() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit held = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.CROSSMATCHED);
        when(unitRepository.findByRequest_IdOrderByUnitNumberAsc(req.getId())).thenReturn(List.of(held));

        service.cancelRequest(req.getId(), "Patient stabilised");

        assertThat(req.getStatus()).isEqualTo(TransfusionRequestStatus.CANCELLED);
        assertThat(held.getStatus()).isEqualTo(BloodUnitStatus.RETURNED);
    }

    @Test
    void cancellingRequiresAReason() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);

        assertThatThrownBy(() -> service.cancelRequest(req.getId(), "  "))
            .isInstanceOf(BusinessException.class);
    }

    // ── Crossmatch: the rule that matters ───────────────────────────────

    @Test
    void anIncompatiblePairCannotBeRecordedAsCompatible() {
        // Group O recipient, group A donor red cells: acute haemolysis.
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.A, RhFactor.POSITIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);

        assertThatThrownBy(() -> service.recordCrossmatch(req.getId(), CrossmatchRequestDTO.builder()
            .bloodUnitId(donor.getId())
            .compatible(Boolean.TRUE)
            .build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ABO/Rh incompatible");
        verify(crossmatchRepository, never()).save(any());
    }

    @Test
    void anRhPositiveUnitCannotBeMarkedCompatibleForAnRhNegativeRecipient() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.O, RhFactor.POSITIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);

        assertThatThrownBy(() -> service.recordCrossmatch(req.getId(), CrossmatchRequestDTO.builder()
            .bloodUnitId(donor.getId())
            .compatible(Boolean.TRUE)
            .build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("incompatible");
    }

    @Test
    void aCompatiblePairReservesTheUnitAndAdvancesTheRequest() {
        TransfusionRequest req = request(group(AboGroup.AB, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);
        when(crossmatchRepository.findByRequest_IdAndBloodUnit_Id(req.getId(), donor.getId()))
            .thenReturn(Optional.empty());

        service.recordCrossmatch(req.getId(), CrossmatchRequestDTO.builder()
            .bloodUnitId(donor.getId())
            .compatible(Boolean.TRUE)
            .method("Immediate spin")
            .build());

        assertThat(donor.getStatus()).isEqualTo(BloodUnitStatus.CROSSMATCHED);
        assertThat(req.getStatus()).isEqualTo(TransfusionRequestStatus.CROSSMATCHED);
    }

    @Test
    void anIncompatibleVerdictIsRecordedAndDoesNotReserveTheUnit() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.A, RhFactor.POSITIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);
        when(crossmatchRepository.findByRequest_IdAndBloodUnit_Id(req.getId(), donor.getId()))
            .thenReturn(Optional.empty());

        service.recordCrossmatch(req.getId(), CrossmatchRequestDTO.builder()
            .bloodUnitId(donor.getId())
            .compatible(Boolean.FALSE)
            .incompatibilityReason("Agglutination at immediate spin")
            .build());

        verify(crossmatchRepository).save(any(TransfusionCrossmatch.class));
        assertThat(donor.getStatus()).isEqualTo(BloodUnitStatus.AVAILABLE);
    }

    @Test
    void aLapsedAntibodyScreenBlocksCrossmatching() {
        PatientBloodGroup stale = group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE);
        stale.setExpiresAt(LocalDateTime.now().minusHours(1));
        TransfusionRequest req = request(stale, TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);

        assertThatThrownBy(() -> service.recordCrossmatch(req.getId(), CrossmatchRequestDTO.builder()
            .bloodUnitId(donor.getId()).compatible(Boolean.TRUE).build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("antibody screen");
    }

    @Test
    void aNeverPerformedScreenIsNotTreatedAsANegativeOne() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NOT_DONE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);

        assertThatThrownBy(() -> service.recordCrossmatch(req.getId(), CrossmatchRequestDTO.builder()
            .bloodUnitId(donor.getId()).compatible(Boolean.TRUE).build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("antibody screen");
    }

    @Test
    void anExpiredUnitCannotBeCrossmatched() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);
        donor.setExpiresOn(LocalDate.now().minusDays(1));

        assertThatThrownBy(() -> service.recordCrossmatch(req.getId(), CrossmatchRequestDTO.builder()
            .bloodUnitId(donor.getId()).compatible(Boolean.TRUE).build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void theWrongComponentCannotBeCrossmatchedAgainstARequest() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit plasma = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.FRESH_FROZEN_PLASMA,
            BloodUnitStatus.AVAILABLE);

        assertThatThrownBy(() -> service.recordCrossmatch(req.getId(), CrossmatchRequestDTO.builder()
            .bloodUnitId(plasma.getId()).compatible(Boolean.TRUE).build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("but the request is for");
    }

    // ── Issue ───────────────────────────────────────────────────────────

    @Test
    void anUncrossmatchedUnitCannotBeIssuedOnARoutineRequest() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);
        when(crossmatchRepository.findByRequest_IdAndBloodUnit_Id(req.getId(), donor.getId()))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueUnit(req.getId(), donor.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("has not been crossmatched");
    }

    @Test
    void oNegativeMayBeReleasedUncrossmatchedOnAnEmergencyRequest() {
        TransfusionRequest req = request(null, TransfusionUrgency.EMERGENCY);
        BloodUnit oNeg = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);
        when(crossmatchRepository.findByRequest_IdAndBloodUnit_Id(req.getId(), oNeg.getId()))
            .thenReturn(Optional.empty());

        service.issueUnit(req.getId(), oNeg.getId());

        assertThat(oNeg.getStatus()).isEqualTo(BloodUnitStatus.ISSUED);
        assertThat(req.getStatus()).isEqualTo(TransfusionRequestStatus.ISSUED);
    }

    @Test
    void emergencyReleaseIsLimitedToONegative() {
        TransfusionRequest req = request(null, TransfusionUrgency.EMERGENCY);
        BloodUnit aPos = unit(AboGroup.A, RhFactor.POSITIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);
        when(crossmatchRepository.findByRequest_IdAndBloodUnit_Id(req.getId(), aPos.getId()))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueUnit(req.getId(), aPos.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only group O Rh-negative");
    }

    @Test
    void anExpiredCrossmatchBlocksIssue() {
        TransfusionRequest req = request(group(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.CROSSMATCHED);
        TransfusionCrossmatch lapsed = TransfusionCrossmatch.builder()
            .request(req).bloodUnit(donor).hospital(hospital)
            .compatible(Boolean.TRUE)
            .performedAt(LocalDateTime.now().minusDays(5))
            .expiresAt(LocalDateTime.now().minusDays(2))
            .build();
        when(crossmatchRepository.findByRequest_IdAndBloodUnit_Id(req.getId(), donor.getId()))
            .thenReturn(Optional.of(lapsed));

        assertThatThrownBy(() -> service.issueUnit(req.getId(), donor.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("expired");
    }

    // ── Bedside ─────────────────────────────────────────────────────────

    private TransfusionAdministration hang(BloodUnitStatus unitStatus, UUID verifierId) {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS, unitStatus);
        when(administrationRepository.findByBloodUnit_Id(donor.getId())).thenReturn(Optional.empty());
        service.startAdministration(TransfusionAdministrationRequestDTO.builder()
            .requestId(req.getId())
            .bloodUnitId(donor.getId())
            .verifiedByStaffId(verifierId)
            .verificationMethod("Wristband scan")
            .build());
        return null;
    }

    @Test
    void onlyAnIssuedUnitCanBeHung() {
        assertThatThrownBy(() -> hang(BloodUnitStatus.AVAILABLE, second.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only an issued unit can be hung");
    }

    @Test
    void theBedsideCheckRequiresTwoDifferentPeople() {
        // The one administration where a single signature is not accepted
        // practice anywhere.
        when(staffRepository.findById(caller.getId())).thenReturn(Optional.of(caller));

        assertThatThrownBy(() -> hang(BloodUnitStatus.ISSUED, caller.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("requires two people");
    }

    @Test
    void hangingAnIssuedUnitWithASecondVerifierSucceeds() {
        hang(BloodUnitStatus.ISSUED, second.getId());
        verify(administrationRepository).save(any(TransfusionAdministration.class));
    }

    @Test
    void aUnitCannotBeHungTwice() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.ISSUED);
        when(administrationRepository.findByBloodUnit_Id(donor.getId()))
            .thenReturn(Optional.of(new TransfusionAdministration()));

        assertThatThrownBy(() -> service.startAdministration(TransfusionAdministrationRequestDTO.builder()
            .requestId(req.getId()).bloodUnitId(donor.getId()).verifiedByStaffId(second.getId()).build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already been hung");
    }

    // ── Reaction ────────────────────────────────────────────────────────

    private TransfusionAdministration inProgress() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.ISSUED);
        TransfusionAdministration admin = TransfusionAdministration.builder()
            .request(req).bloodUnit(donor).patient(patient).hospital(hospital)
            .status(TransfusionAdministrationStatus.IN_PROGRESS)
            .startedAt(LocalDateTime.now().minusMinutes(10))
            .administeredBy(caller).verifiedBy(second)
            .build();
        admin.setId(UUID.randomUUID());
        when(administrationRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        return admin;
    }

    @Test
    void recordingAReactionStopsTheTransfusionAndQuarantinesTheUnit() {
        TransfusionAdministration admin = inProgress();

        service.recordReaction(admin.getId(), TransfusionReactionRequestDTO.builder()
            .reactionType(TransfusionReactionType.ACUTE_HEMOLYTIC)
            .severity(TransfusionReactionSeverity.LIFE_THREATENING)
            .onsetAt(LocalDateTime.now())
            .signsSymptoms("Fever, loin pain, dark urine")
            .build());

        // Stopping the infusion is the first step of every reaction protocol —
        // the record must not show it still running.
        assertThat(admin.getStatus()).isEqualTo(TransfusionAdministrationStatus.STOPPED);
        assertThat(admin.getStopReason()).contains("ACUTE_HEMOLYTIC");
        // The implicated bag is evidence for the workup, not stock.
        assertThat(admin.getBloodUnit().getStatus()).isEqualTo(BloodUnitStatus.DISCARDED);
        verify(reactionRepository).save(any(TransfusionReaction.class));
    }

    @Test
    void completingATransfusionMarksTheUnitTransfused() {
        TransfusionAdministration admin = inProgress();

        service.completeAdministration(admin.getId(), 280);

        assertThat(admin.getStatus()).isEqualTo(TransfusionAdministrationStatus.COMPLETED);
        assertThat(admin.getVolumeTransfusedMl()).isEqualTo(280);
        assertThat(admin.getBloodUnit().getStatus()).isEqualTo(BloodUnitStatus.TRANSFUSED);
    }

    @Test
    void stoppingRequiresAReason() {
        TransfusionAdministration admin = inProgress();

        assertThatThrownBy(() -> service.stopAdministration(admin.getId(), " "))
            .isInstanceOf(BusinessException.class);
    }

    // ── Units and tenancy ───────────────────────────────────────────────

    @Test
    void anAlreadyExpiredUnitCannotBeReceived() {
        assertThatThrownBy(() -> service.receiveUnit(
            com.example.hms.payload.dto.transfusion.BloodUnitRequestDTO.builder()
                .unitNumber("U-1")
                .productType(BloodProductType.PACKED_RED_CELLS)
                .aboGroup(AboGroup.O)
                .rhFactor(RhFactor.NEGATIVE)
                .expiresOn(LocalDate.now().minusDays(1))
                .build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already expired");
    }

    @Test
    void aDuplicateUnitNumberIsRefused() {
        BloodUnit existing = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);
        when(unitRepository.findByHospital_IdAndUnitNumber(hospitalId, "U-1"))
            .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.receiveUnit(
            com.example.hms.payload.dto.transfusion.BloodUnitRequestDTO.builder()
                .unitNumber("U-1")
                .productType(BloodProductType.PACKED_RED_CELLS)
                .aboGroup(AboGroup.O)
                .rhFactor(RhFactor.NEGATIVE)
                .expiresOn(LocalDate.now().plusDays(10))
                .build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already on record");
    }

    @Test
    void aRequestAtAnotherHospitalIsNotFoundRatherThanForbidden() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        TransfusionRequest foreign = TransfusionRequest.builder().hospital(other).build();
        UUID id = UUID.randomUUID();
        foreign.setId(id);
        when(requestRepository.findById(id)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getRequest(id))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aSuperAdminWithNoActiveHospitalCannotWriteBloodRecords() {
        // Blood is physical stock in a building; an unscoped write has no
        // facility to belong to.
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);

        assertThatThrownBy(() -> service.listAssignableUnits())
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital is required");
    }
}

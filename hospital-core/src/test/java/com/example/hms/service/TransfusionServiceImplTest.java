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
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientBloodGroup;
import com.example.hms.model.Staff;
import com.example.hms.model.TransfusionAdministration;
import com.example.hms.model.TransfusionCrossmatch;
import com.example.hms.model.TransfusionReaction;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.payload.dto.transfusion.BloodUnitRequestDTO;
import com.example.hms.payload.dto.transfusion.BloodUnitResponseDTO;
import com.example.hms.payload.dto.transfusion.CrossmatchRequestDTO;
import com.example.hms.payload.dto.transfusion.PatientBloodGroupRequestDTO;
import com.example.hms.payload.dto.transfusion.PatientBloodGroupResponseDTO;
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
import org.springframework.context.i18n.LocaleContextHolder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
@ExtendWith({MockitoExtension.class, com.example.hms.i18n.EnglishMessageBundles.class})
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
    /**
     * A real system clock, not a mock: the fixtures below build their own
     * timestamps from LocalDateTime.now(), so moving the service's clock away
     * from the system one would make expiry assertions lie. The injection point
     * exists so a future test CAN move it.
     */
    @Spy private Clock clock = Clock.systemDefaultZone();
    @Spy private TransfusionMapper mapper = new TransfusionMapper(Clock.systemDefaultZone());

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
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
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

    /**
     * Argument builders for the assertion lambdas below.
     *
     * <p>These exist so that {@code assertThatThrownBy(() -> ...)} holds exactly
     * ONE call that can throw — the service call under test (java:S5778). With
     * the builder chain and the {@code getId()} calls inline, a failure inside
     * the fixture would be caught by the assertion and read as the service
     * refusing, which is the opposite of what the test claims to prove.
     */
    private CrossmatchRequestDTO compatibleCrossmatch(BloodUnit unit) {
        return CrossmatchRequestDTO.builder()
            .bloodUnitId(unit.getId())
            .compatible(Boolean.TRUE)
            .build();
    }

    private TransfusionAdministrationRequestDTO hangRequest(TransfusionRequest req,
                                                            BloodUnit unit,
                                                            Staff verifier) {
        return TransfusionAdministrationRequestDTO.builder()
            .requestId(req.getId())
            .bloodUnitId(unit.getId())
            .verifiedByStaffId(verifier.getId())
            .build();
    }

    private BloodUnitRequestDTO incomingUnit(String unitNumber, LocalDate expiresOn) {
        return BloodUnitRequestDTO.builder()
            .unitNumber(unitNumber)
            .productType(BloodProductType.PACKED_RED_CELLS)
            .aboGroup(AboGroup.O)
            .rhFactor(RhFactor.NEGATIVE)
            .expiresOn(expiresOn)
            .build();
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

        PatientBloodGroupRequestDTO changed = PatientBloodGroupRequestDTO.builder()
            .patientId(patientId)
            .aboGroup(AboGroup.B)
            .rhFactor(RhFactor.POSITIVE)
            .antibodyScreen(AntibodyScreenResult.NEGATIVE)
            .build();

        assertThatThrownBy(() -> service.recordBloodGroup(changed))
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

        TransfusionRequestRequestDTO routine = TransfusionRequestRequestDTO.builder()
            .patientId(patientId)
            .productType(BloodProductType.PACKED_RED_CELLS)
            .unitsRequested(2)
            .indication("Anaemia")
            .build();

        assertThatThrownBy(() -> service.createRequest(routine))
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

        UUID requestId = req.getId();

        assertThatThrownBy(() -> service.cancelRequest(requestId, "  "))
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

        UUID requestId = req.getId();
        CrossmatchRequestDTO verdict = compatibleCrossmatch(donor);

        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
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

        UUID requestId = req.getId();
        CrossmatchRequestDTO verdict = compatibleCrossmatch(donor);

        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
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

        UUID requestId = req.getId();
        CrossmatchRequestDTO verdict = compatibleCrossmatch(donor);

        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("antibody screen");
    }

    @Test
    void aNeverPerformedScreenIsNotTreatedAsANegativeOne() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NOT_DONE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);

        UUID requestId = req.getId();
        CrossmatchRequestDTO verdict = compatibleCrossmatch(donor);

        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
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

        UUID requestId = req.getId();
        CrossmatchRequestDTO verdict = compatibleCrossmatch(donor);

        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void theWrongComponentCannotBeCrossmatchedAgainstARequest() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit plasma = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.FRESH_FROZEN_PLASMA,
            BloodUnitStatus.AVAILABLE);

        UUID requestId = req.getId();
        CrossmatchRequestDTO verdict = compatibleCrossmatch(plasma);

        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
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

        UUID requestId = req.getId();
        UUID unitId = donor.getId();

        assertThatThrownBy(() -> service.issueUnit(requestId, unitId))
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

        UUID requestId = req.getId();
        UUID unitId = aPos.getId();

        assertThatThrownBy(() -> service.issueUnit(requestId, unitId))
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

        UUID requestId = req.getId();
        UUID unitId = donor.getId();

        assertThatThrownBy(() -> service.issueUnit(requestId, unitId))
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
        UUID verifierId = second.getId();

        assertThatThrownBy(() -> hang(BloodUnitStatus.AVAILABLE, verifierId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only an issued unit can be hung");
    }

    @Test
    void theBedsideCheckRequiresTwoDifferentPeople() {
        // The one administration where a single signature is not accepted
        // practice anywhere.
        when(staffRepository.findById(caller.getId())).thenReturn(Optional.of(caller));

        UUID verifierId = caller.getId();

        assertThatThrownBy(() -> hang(BloodUnitStatus.ISSUED, verifierId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("requires two people");
    }

    @Test
    void theSecondCheckerCannotBeTheCallerUnderAnotherStaffRecord_D503() {
        com.example.hms.model.User same = new com.example.hms.model.User();
        same.setId(callerUserId);
        caller.setUser(same);
        second.setUser(same);
        UUID verifierId = second.getId();

        assertThatThrownBy(() -> hang(BloodUnitStatus.ISSUED, verifierId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("requires two people");
    }

    @Test
    void theSecondCheckerMustBeStaffAtThisHospital_D503() {
        second.setHospital(null);
        UUID verifierId = second.getId();

        assertThatThrownBy(() -> hang(BloodUnitStatus.ISSUED, verifierId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anInactiveSecondCheckerIsRefused_D503() {
        second.setActive(false);
        UUID verifierId = second.getId();

        assertThatThrownBy(() -> hang(BloodUnitStatus.ISSUED, verifierId))
            .isInstanceOf(ResourceNotFoundException.class);
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

        TransfusionAdministrationRequestDTO hang = hangRequest(req, donor, second);

        assertThatThrownBy(() -> service.startAdministration(hang))
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

        UUID administrationId = admin.getId();

        assertThatThrownBy(() -> service.stopAdministration(administrationId, " "))
            .isInstanceOf(BusinessException.class);
    }

    // ── QA round 14: D858, D883, D884, D885 ─────────────────────────────

    /** QA D858: NURSE_A, POST /transfusions/administrations/{id}/reaction, onsetAt = now + 24h. */
    @Test
    void aReactionWithAnOnsetTomorrowIsRefused_D858() {
        TransfusionAdministration admin = inProgress();
        UUID administrationId = admin.getId();
        TransfusionReactionRequestDTO tomorrow = TransfusionReactionRequestDTO.builder()
            .reactionType(TransfusionReactionType.FEBRILE_NON_HEMOLYTIC)
            .severity(TransfusionReactionSeverity.MILD)
            .onsetAt(LocalDateTime.now().plusHours(24))
            .signsSymptoms("Fièvre 38,6 °C")
            .build();

        assertThatThrownBy(() -> service.recordReaction(administrationId, tomorrow))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("onset time cannot be in the future");
        verify(reactionRepository, never()).save(any());
        // Nothing was stopped or quarantined on the strength of a refused report.
        assertThat(admin.getStatus()).isEqualTo(TransfusionAdministrationStatus.IN_PROGRESS);
        assertThat(admin.getBloodUnit().getStatus()).isEqualTo(BloodUnitStatus.ISSUED);
    }

    /** D858 must not refuse the ordinary case: an onset a moment ago, or a minute of clock skew. */
    @Test
    void aReactionNowOrWithinTheClockSkewIsAccepted_D858() {
        TransfusionAdministration admin = inProgress();

        service.recordReaction(admin.getId(), TransfusionReactionRequestDTO.builder()
            .reactionType(TransfusionReactionType.FEBRILE_NON_HEMOLYTIC)
            .severity(TransfusionReactionSeverity.MILD)
            .onsetAt(LocalDateTime.now().plusMinutes(1))
            .signsSymptoms("Frissons")
            .build());

        verify(reactionRepository).save(any(TransfusionReaction.class));
    }

    /**
     * QA D901: administration 36dc2b02 was started at 16:18; a reaction posted
     * with onsetAt ten days earlier was saved (7b374e6a). It is a 400 on onsetAt.
     */
    @Test
    void aReactionBeforeTheTransfusionStartedIsRefusedOnItsField_D901() {
        TransfusionAdministration admin = inProgress();
        UUID administrationId = admin.getId();
        TransfusionReactionRequestDTO tenDaysEarlier = TransfusionReactionRequestDTO.builder()
            .reactionType(TransfusionReactionType.FEBRILE_NON_HEMOLYTIC)
            .severity(TransfusionReactionSeverity.MILD)
            .onsetAt(admin.getStartedAt().minusDays(10))
            .signsSymptoms("Fièvre 38,6 °C")
            .build();

        assertThatThrownBy(() -> service.recordReaction(administrationId, tenDaysEarlier))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "onsetAt")
            .hasMessage("The reaction onset cannot be before the transfusion started.");
        verify(reactionRepository, never()).save(any());
        assertThat(admin.getStatus()).isEqualTo(TransfusionAdministrationStatus.IN_PROGRESS);
        assertThat(admin.getBloodUnit().getStatus()).isEqualTo(BloodUnitStatus.ISSUED);
    }

    /** D901 must not refuse an onset at the start, or a ward clock a minute behind the server's. */
    @Test
    void aReactionAtTheStartOrWithinTheClockSkewIsAccepted_D901() {
        TransfusionAdministration admin = inProgress();

        service.recordReaction(admin.getId(), TransfusionReactionRequestDTO.builder()
            .reactionType(TransfusionReactionType.ALLERGIC)
            .severity(TransfusionReactionSeverity.MILD)
            .onsetAt(admin.getStartedAt().minusMinutes(1))
            .signsSymptoms("Urticaire")
            .build());

        verify(reactionRepository).save(any(TransfusionReaction.class));
    }

    /** D901: a delayed reaction, days after a completed transfusion, is still recorded. */
    @Test
    void aDelayedReactionAfterTheTransfusionEndedIsAccepted_D901() {
        TransfusionAdministration admin = inProgress();
        admin.setStartedAt(LocalDateTime.now().minusDays(6));
        admin.setStatus(TransfusionAdministrationStatus.COMPLETED);
        admin.setCompletedAt(LocalDateTime.now().minusDays(6).plusHours(2));

        service.recordReaction(admin.getId(), TransfusionReactionRequestDTO.builder()
            .reactionType(TransfusionReactionType.DELAYED_HEMOLYTIC)
            .severity(TransfusionReactionSeverity.MODERATE)
            .onsetAt(LocalDateTime.now().minusHours(3))
            .signsSymptoms("Ictère, chute de l'hémoglobine")
            .build());

        verify(reactionRepository).save(any(TransfusionReaction.class));
    }

    /** D901: the future-onset refusal (D858) names its field too. */
    @Test
    void aFutureOnsetIsRefusedOnOnsetAt_D901() {
        TransfusionAdministration admin = inProgress();
        UUID administrationId = admin.getId();
        TransfusionReactionRequestDTO tomorrow = TransfusionReactionRequestDTO.builder()
            .reactionType(TransfusionReactionType.FEBRILE_NON_HEMOLYTIC)
            .severity(TransfusionReactionSeverity.MILD)
            .onsetAt(LocalDateTime.now().plusDays(1))
            .signsSymptoms("Fièvre")
            .build();

        assertThatThrownBy(() -> service.recordReaction(administrationId, tomorrow))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "onsetAt");
    }

    /** D901 sibling ordering: blood cannot be required by a time already past when it is asked for. */
    @Test
    void aRequestRequiredByAPastTimeIsRefused_D901() {
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patientId, hospitalId))
            .thenReturn(Optional.empty());
        TransfusionRequestRequestDTO yesterday = TransfusionRequestRequestDTO.builder()
            .patientId(patientId)
            .productType(BloodProductType.PACKED_RED_CELLS)
            .unitsRequested(1)
            .indication("Anémie sévère")
            .urgency(TransfusionUrgency.EMERGENCY)
            .requiredBy(LocalDateTime.now().minusDays(1))
            .build();

        assertThatThrownBy(() -> service.createRequest(yesterday))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "requiredBy")
            .hasMessage("The time the blood is required by cannot be in the past.");
        verify(requestRepository, never()).save(any());
    }

    @Test
    void aRequestRequiredWithinTheHourIsAccepted_D901() {
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patientId, hospitalId))
            .thenReturn(Optional.empty());

        service.createRequest(TransfusionRequestRequestDTO.builder()
            .patientId(patientId)
            .productType(BloodProductType.PACKED_RED_CELLS)
            .unitsRequested(1)
            .indication("Hémorragie")
            .urgency(TransfusionUrgency.EMERGENCY)
            .requiredBy(LocalDateTime.now().plusMinutes(30))
            .build());

        verify(requestRepository).save(any(TransfusionRequest.class));
    }

    @Test
    void theNewRefusalsAreTranslated_D901() throws java.io.IOException {
        for (String lang : List.of("fr", "es")) {
            java.util.Properties bundle = new java.util.Properties();
            try (var in = new java.io.InputStreamReader(
                    getClass().getResourceAsStream("/i18n/messages-clinical_" + lang + ".properties"),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                bundle.load(in);
            }
            assertThat(bundle.getProperty("transfusion.reaction.onsetBeforeStart")).as(lang).isNotBlank()
                .doesNotContain("cannot");
            assertThat(bundle.getProperty("transfusion.request.requiredByInPast")).as(lang).isNotBlank()
                .doesNotContain("cannot");
        }
    }

    /** D858 sibling: a type-and-screen specimen cannot have been collected tomorrow. */
    @Test
    void aBloodGroupSpecimenCollectedInTheFutureIsRefused_D858() {
        PatientBloodGroupRequestDTO future = PatientBloodGroupRequestDTO.builder()
            .patientId(patientId).aboGroup(AboGroup.O).rhFactor(RhFactor.POSITIVE)
            .antibodyScreen(AntibodyScreenResult.NEGATIVE)
            .specimenCollectedAt(LocalDateTime.now().plusDays(1))
            .build();

        assertThatThrownBy(() -> service.recordBloodGroup(future))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("collection time cannot be in the future");
        verify(bloodGroupRepository, never()).save(any());
    }

    /** QA D883: LAB_TECH_A received a unit with collectedOn 2027-01-01. */
    @Test
    void aUnitCollectedInTheFutureIsRefused_D883() {
        BloodUnitRequestDTO future = incomingUnit("CNTS-FUT-1", LocalDate.now().plusDays(20));
        future.setCollectedOn(LocalDate.now().plusYears(1));

        assertThatThrownBy(() -> service.receiveUnit(future))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("collection date cannot be in the future");
        verify(unitRepository, never()).save(any());
    }

    @Test
    void aUnitCollectedAfterItsOwnExpiryIsRefused_D883() {
        BloodUnitRequestDTO backwards = incomingUnit("CNTS-REV-1", LocalDate.now().plusDays(5));
        backwards.setCollectedOn(LocalDate.now().plusDays(6));

        assertThatThrownBy(() -> service.receiveUnit(backwards))
            .isInstanceOf(BusinessException.class);
        verify(unitRepository, never()).save(any());
    }

    /** The QA's own good unit (collected today, 300 mL) is still received. */
    @Test
    void aUnitCollectedTodayIsReceived_D883() {
        BloodUnitRequestDTO today = incomingUnit("CNTS-OUA-1", LocalDate.now().plusDays(20));
        today.setCollectedOn(LocalDate.now());
        today.setVolumeMl(300);
        when(unitRepository.save(any(BloodUnit.class))).thenAnswer(inv -> inv.getArgument(0));

        service.receiveUnit(today);

        verify(unitRepository).save(any(BloodUnit.class));
    }

    /** D883 sibling: the volume recorded at completion cannot exceed a bag. */
    @Test
    void completingWithAnAbsurdVolumeIsRefused_D883() {
        TransfusionAdministration admin = inProgress();
        UUID administrationId = admin.getId();

        assertThatThrownBy(() -> service.completeAdministration(administrationId, 999_999))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("between 0 and 1000 mL");
        assertThat(admin.getStatus()).isEqualTo(TransfusionAdministrationStatus.IN_PROGRESS);
    }

    /** QA D883 (volume 999999 mL) and D884 (unitsRequested 500): refused at the request boundary. */
    @Test
    void theRequestBoundaryRefusesAbsurdVolumesAndUnitCounts_D883_D884() {
        jakarta.validation.Validator validator = frenchValidator();
        BloodUnitRequestDTO bag = incomingUnit("CNTS-VOL-1", LocalDate.now().plusDays(20));
        bag.setVolumeMl(999_999);
        TransfusionRequestRequestDTO fiveHundred = TransfusionRequestRequestDTO.builder()
            .patientId(patientId).productType(BloodProductType.PACKED_RED_CELLS)
            .unitsRequested(500).indication("x").build();
        TransfusionRequestRequestDTO two = TransfusionRequestRequestDTO.builder()
            .patientId(patientId).productType(BloodProductType.PACKED_RED_CELLS)
            .unitsRequested(2).indication("Anémie sévère").build();
        BloodUnitRequestDTO normal = incomingUnit("CNTS-OUA-2", LocalDate.now().plusDays(20));
        normal.setVolumeMl(300);

        assertThat(validator.validate(bag)).singleElement()
            .satisfies(v -> {
                assertThat(v.getPropertyPath()).hasToString("volumeMl");
                assertThat(v.getMessage()).isEqualTo("Une poche de sang contient au plus 1000 mL.");
            });
        assertThat(validator.validate(fiveHundred)).singleElement()
            .satisfies(v -> {
                assertThat(v.getPropertyPath()).hasToString("unitsRequested");
                assertThat(v.getMessage()).isEqualTo("Une demande ne peut porter que sur 20 poches au plus.");
            });
        assertThat(validator.validate(two)).isEmpty();
        assertThat(validator.validate(normal)).isEmpty();
    }

    /**
     * QA D885: the duplicate-unit, expired-unit and two-person-check refusals
     * reached a French user in English. Asserted on the exact paths the QA hit.
     */
    @Test
    void bloodBankRefusalsSpeakTheCallersLanguage_D885() {
        BloodUnit existing = unit(AboGroup.O, RhFactor.POSITIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);
        when(unitRepository.findByHospital_IdAndUnitNumber(hospitalId, "CNTS-OUA-7"))
            .thenReturn(Optional.of(existing));
        BloodUnitRequestDTO duplicate = incomingUnit("CNTS-OUA-7", LocalDate.now().plusDays(20));
        BloodUnitRequestDTO expired = incomingUnit("CNTS-EXP-7", LocalDate.of(2025, 1, 1));
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit issued = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.ISSUED);
        TransfusionAdministrationRequestDTO selfVerified = hangRequest(req, issued, caller);
        when(staffRepository.findById(caller.getId())).thenReturn(Optional.of(caller));

        LocaleContextHolder.setLocale(Locale.FRENCH);

        assertThatThrownBy(() -> service.receiveUnit(duplicate))
            .hasMessage("La poche CNTS-OUA-7 est déjà enregistrée ici. Deux poches portant le même "
                + "numéro exposent à une erreur de poche.");
        assertThatThrownBy(() -> service.receiveUnit(expired))
            .hasMessage("La poche CNTS-EXP-7 est déjà périmée et ne peut pas entrer en stock.");
        assertThatThrownBy(() -> service.startAdministration(selfVerified))
            .hasMessageStartingWith("Le contrôle au lit du patient exige deux personnes.");
    }

    /** D885: the product names inside a mismatch are the caller's words, not enum codes. */
    @Test
    void aProductMismatchNamesTheProductsInTheCallersLanguage_D885() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit platelets = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PLATELETS,
            BloodUnitStatus.AVAILABLE);
        UUID requestId = req.getId();
        CrossmatchRequestDTO verdict = compatibleCrossmatch(platelets);

        LocaleContextHolder.setLocale(Locale.forLanguageTag("es"));

        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
            .hasMessageContaining("«plaquetas»")
            .hasMessageContaining("«concentrado de hematíes»")
            .hasMessageNotContaining("PLATELETS");
    }

    private static jakarta.validation.Validator frenchValidator() {
        org.springframework.validation.beanvalidation.LocalValidatorFactoryBean factory =
            new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
        factory.setValidationMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        factory.afterPropertiesSet();
        LocaleContextHolder.setLocale(Locale.FRENCH);
        return factory.getValidator();
    }

    // ── Units and tenancy ───────────────────────────────────────────────

    @Test
    void anAlreadyExpiredUnitCannotBeReceived() {
        BloodUnitRequestDTO expired = incomingUnit("U-1", LocalDate.now().minusDays(1));

        assertThatThrownBy(() -> service.receiveUnit(expired))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already expired");
    }

    @Test
    void aDuplicateUnitNumberIsRefused() {
        BloodUnit existing = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);
        when(unitRepository.findByHospital_IdAndUnitNumber(hospitalId, "U-1"))
            .thenReturn(Optional.of(existing));

        BloodUnitRequestDTO duplicate = incomingUnit("U-1", LocalDate.now().plusDays(10));

        assertThatThrownBy(() -> service.receiveUnit(duplicate))
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

    // ── Reads and the rest of the plumbing ──────────────────────────────

    @Test
    void readsTheCurrentTypeAndScreenAndItsHistory() {
        PatientBloodGroup current = group(AboGroup.B, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE);
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patientId, hospitalId))
            .thenReturn(Optional.of(current));
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdOrderByPerformedAtDesc(patientId, hospitalId))
            .thenReturn(List.of(current));

        assertThat(service.getCurrentBloodGroup(patientId).getAboGroup()).isEqualTo(AboGroup.B);
        assertThat(service.getBloodGroupHistory(patientId)).hasSize(1);
    }

    @Test
    void anUntypedPatientHasNoCurrentGroupToRead() {
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patientId, hospitalId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrentBloodGroup(patientId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void receivingAUnitStoresItAsAvailable() {
        when(unitRepository.findByHospital_IdAndUnitNumber(hospitalId, "BU-77")).thenReturn(Optional.empty());

        BloodUnitResponseDTO saved = service.receiveUnit(BloodUnitRequestDTO.builder()
            .unitNumber("BU-77")
            .productType(BloodProductType.PACKED_RED_CELLS)
            .aboGroup(AboGroup.O)
            .rhFactor(RhFactor.NEGATIVE)
            .volumeMl(280)
            .collectedOn(LocalDate.now().minusDays(3))
            .expiresOn(LocalDate.now().plusDays(30))
            .source("Regional blood bank")
            .build());

        assertThat(saved.getStatus()).isEqualTo(BloodUnitStatus.AVAILABLE);
        assertThat(saved.getUnitNumber()).isEqualTo("BU-77");
        assertThat(saved.getExpired()).isFalse();
    }

    @Test
    void aUnitCanBeReceivedAgainstASpecificRequest() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        when(unitRepository.findByHospital_IdAndUnitNumber(hospitalId, "BU-88")).thenReturn(Optional.empty());

        BloodUnitResponseDTO saved = service.receiveUnit(BloodUnitRequestDTO.builder()
            .unitNumber("BU-88")
            .productType(BloodProductType.PACKED_RED_CELLS)
            .aboGroup(AboGroup.O)
            .rhFactor(RhFactor.NEGATIVE)
            .expiresOn(LocalDate.now().plusDays(30))
            .requestId(req.getId())
            .build());

        assertThat(saved.getRequestId()).isEqualTo(req.getId());
    }

    @Test
    void unitsListWithAndWithoutAStatusFilter() {
        BloodUnit u = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);
        when(unitRepository.findByHospital_IdOrderByExpiresOnAsc(hospitalId)).thenReturn(List.of(u));
        when(unitRepository.findByHospital_IdAndStatusOrderByExpiresOnAsc(hospitalId, BloodUnitStatus.AVAILABLE))
            .thenReturn(List.of(u));
        when(unitRepository.findAssignable(eq(hospitalId), any())).thenReturn(List.of(u));

        assertThat(service.listUnits(null)).hasSize(1);
        assertThat(service.listUnits("available")).hasSize(1);
        assertThat(service.listAssignableUnits()).hasSize(1);
    }

    @Test
    void anUnknownStatusFilterIsRefusedRatherThanSilentlyIgnored() {
        assertThatThrownBy(() -> service.listUnits("NOT_A_STATUS"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unknown blood unit status");
        assertThatThrownBy(() -> service.listRequests("NOT_A_STATUS"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unknown transfusion request status");
    }

    @Test
    void discardingAUnitRecordsTheReason() {
        BloodUnit u = unit(AboGroup.A, RhFactor.POSITIVE, BloodProductType.PLATELETS,
            BloodUnitStatus.AVAILABLE);

        BloodUnitResponseDTO discarded = service.discardUnit(u.getId(), "Cold chain breach");

        assertThat(discarded.getStatus()).isEqualTo(BloodUnitStatus.DISCARDED);
        assertThat(discarded.getDiscardReason()).isEqualTo("Cold chain breach");
    }

    @Test
    void discardingRequiresAReasonAndRefusesAnAlreadyTransfusedUnit() {
        BloodUnit fresh = unit(AboGroup.A, RhFactor.POSITIVE, BloodProductType.PLATELETS,
            BloodUnitStatus.AVAILABLE);
        UUID freshId = fresh.getId();

        assertThatThrownBy(() -> service.discardUnit(freshId, " "))
            .isInstanceOf(BusinessException.class);

        BloodUnit given = unit(AboGroup.B, RhFactor.POSITIVE, BloodProductType.PLATELETS,
            BloodUnitStatus.TRANSFUSED);
        UUID givenId = given.getId();

        assertThatThrownBy(() -> service.discardUnit(givenId, "Changed my mind"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already been transfused");
    }

    @Test
    void aDiscardedUnitIsNotDiscardedAgainAndKeepsItsReason_D1186() {
        BloodUnit gone = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PLATELETS,
            BloodUnitStatus.DISCARDED);
        gone.setDiscardReason("Cold chain breach");
        UUID goneId = gone.getId();

        assertThatThrownBy(() -> service.discardUnit(goneId, "Second reason"))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("transfusion.unit.alreadyDiscarded");
        assertThat(gone.getDiscardReason()).isEqualTo("Cold chain breach");
    }

    @Test
    void aRequestMayBeTiedToAnEncounterAtTheSameHospital() {
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patientId, hospitalId))
            .thenReturn(Optional.of(group(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE)));
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setHospital(hospital);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        service.createRequest(TransfusionRequestRequestDTO.builder()
            .patientId(patientId)
            .encounterId(encounterId)
            .productType(BloodProductType.PACKED_RED_CELLS)
            .unitsRequested(1)
            .indication("Symptomatic anaemia")
            .build());

        verify(requestRepository).save(any(TransfusionRequest.class));
    }

    @Test
    void anEncounterAtAnotherHospitalCannotBeAttachedToARequest() {
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patientId, hospitalId))
            .thenReturn(Optional.of(group(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE)));
        UUID encounterId = UUID.randomUUID();
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        Encounter foreign = new Encounter();
        foreign.setId(encounterId);
        foreign.setHospital(other);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(foreign));

        TransfusionRequestRequestDTO withEncounter = TransfusionRequestRequestDTO.builder()
            .patientId(patientId)
            .encounterId(encounterId)
            .productType(BloodProductType.PACKED_RED_CELLS)
            .unitsRequested(1)
            .indication("Symptomatic anaemia")
            .build();

        assertThatThrownBy(() -> service.createRequest(withEncounter))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requestsReadIndividuallyByHospitalAndByPatient() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        when(requestRepository.findByHospital_IdOrderByRequestedAtDesc(hospitalId)).thenReturn(List.of(req));
        when(requestRepository.findByHospital_IdAndStatusOrderByRequestedAtDesc(
            hospitalId, TransfusionRequestStatus.REQUESTED)).thenReturn(List.of(req));
        when(requestRepository.findByPatient_IdAndHospital_IdOrderByRequestedAtDesc(patientId, hospitalId))
            .thenReturn(List.of(req));

        assertThat(service.getRequest(req.getId())).isNotNull();
        assertThat(service.listRequests(null)).hasSize(1);
        assertThat(service.listRequests("REQUESTED")).hasSize(1);
        assertThat(service.listRequestsForPatient(patientId)).hasSize(1);
    }

    @Test
    void aTerminalRequestCannotBeCancelledOrCrossmatchedAgainst() {
        TransfusionRequest done = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        done.setStatus(TransfusionRequestStatus.COMPLETED);
        BloodUnit donor = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);

        UUID requestId = done.getId();
        UUID unitId = donor.getId();
        CrossmatchRequestDTO verdict = compatibleCrossmatch(donor);

        assertThatThrownBy(() -> service.cancelRequest(requestId, "Too late"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("request is closed");
        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("request is closed");
        assertThatThrownBy(() -> service.issueUnit(requestId, unitId))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void aTerminalUnitCannotBeCrossmatched() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit discarded = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.DISCARDED);

        UUID requestId = req.getId();
        CrossmatchRequestDTO verdict = compatibleCrossmatch(discarded);

        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("no longer usable");
    }

    @Test
    void anUntypedPatientCannotBeCrossmatchedAgainstAtAll() {
        // Emergency release issues O negative uncrossmatched instead — there is
        // nothing to match against.
        TransfusionRequest req = request(null, TransfusionUrgency.EMERGENCY);
        BloodUnit donor = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);

        UUID requestId = req.getId();
        CrossmatchRequestDTO verdict = compatibleCrossmatch(donor);

        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("no type and screen on record");
    }

    @Test
    void crossmatchesAreListedForARequest() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.CROSSMATCHED);
        when(crossmatchRepository.findByRequest_IdOrderByPerformedAtDesc(req.getId()))
            .thenReturn(List.of(TransfusionCrossmatch.builder()
                .request(req).bloodUnit(donor).hospital(hospital)
                .compatible(Boolean.TRUE)
                .performedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(48))
                .build()));

        assertThat(service.listCrossmatches(req.getId())).singleElement()
            .satisfies(c -> assertThat(c.getUsable()).isTrue());
    }

    @Test
    void anExpiredUnitIsRefusedAtIssueAndAtTheBedside() {
        TransfusionRequest req = request(null, TransfusionUrgency.EMERGENCY);
        BloodUnit stale = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.ISSUED);
        stale.setExpiresOn(LocalDate.now().minusDays(1));
        when(crossmatchRepository.findByRequest_IdAndBloodUnit_Id(req.getId(), stale.getId()))
            .thenReturn(Optional.empty());
        when(administrationRepository.findByBloodUnit_Id(stale.getId())).thenReturn(Optional.empty());

        UUID requestId = req.getId();
        UUID staleId = stale.getId();
        TransfusionAdministrationRequestDTO hangStale = hangRequest(req, stale, second);

        assertThatThrownBy(() -> service.issueUnit(requestId, staleId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("must not be used");
        assertThatThrownBy(() -> service.startAdministration(hangStale))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("must not be used");
    }

    @Test
    void aCallerWithNoStaffProfileHereCannotTransfuse() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.ISSUED);
        when(administrationRepository.findByBloodUnit_Id(donor.getId())).thenReturn(Optional.empty());
        when(staffRepository.findByUserIdAndHospitalId(callerUserId, hospitalId)).thenReturn(Optional.empty());

        TransfusionAdministrationRequestDTO hang = hangRequest(req, donor, second);

        assertThatThrownBy(() -> service.startAdministration(hang))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anUnauthenticatedActorResolvesToNoStaffRatherThanThrowing() {
        // currentStaff() is best-effort on the recording paths: a null actor
        // leaves the attribution blank rather than losing the clinical write.
        when(roleValidator.getCurrentUserId()).thenReturn(null);
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patientId, hospitalId))
            .thenReturn(Optional.empty());

        PatientBloodGroupResponseDTO saved = service.recordBloodGroup(PatientBloodGroupRequestDTO.builder()
            .patientId(patientId)
            .aboGroup(AboGroup.O)
            .rhFactor(RhFactor.NEGATIVE)
            .antibodyScreen(AntibodyScreenResult.NEGATIVE)
            .build());

        assertThat(saved.getPerformedByName()).isNull();
    }

    @Test
    void aFinishedTransfusionCannotBeCompletedOrStoppedAgain() {
        TransfusionAdministration admin = inProgress();
        admin.setStatus(TransfusionAdministrationStatus.COMPLETED);

        UUID administrationId = admin.getId();

        assertThatThrownBy(() -> service.completeAdministration(administrationId, 200))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already finished");
        assertThatThrownBy(() -> service.stopAdministration(administrationId, "Too late"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already finished");
    }

    @Test
    void stoppingAnInProgressTransfusionRecordsTheReason() {
        TransfusionAdministration admin = inProgress();

        service.stopAdministration(admin.getId(), "Line infiltrated");

        assertThat(admin.getStatus()).isEqualTo(TransfusionAdministrationStatus.STOPPED);
        assertThat(admin.getStopReason()).isEqualTo("Line infiltrated");
        assertThat(admin.getCompletedAt()).isNotNull();
    }

    @Test
    void completingTheLastOutstandingUnitCompletesTheRequest() {
        TransfusionAdministration admin = inProgress();
        BloodUnit unitOnRequest = admin.getBloodUnit();
        // After completion the unit is TRANSFUSED, so nothing is outstanding.
        when(unitRepository.findByRequest_IdOrderByUnitNumberAsc(admin.getRequest().getId()))
            .thenReturn(List.of(unitOnRequest));

        service.completeAdministration(admin.getId(), 300);

        assertThat(admin.getRequest().getStatus()).isEqualTo(TransfusionRequestStatus.COMPLETED);
    }

    @Test
    void aRequestStaysOpenWhileAnyUnitIsStillOutstanding() {
        TransfusionAdministration admin = inProgress();
        BloodUnit stillHeld = unit(AboGroup.O, RhFactor.NEGATIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.CROSSMATCHED);
        when(unitRepository.findByRequest_IdOrderByUnitNumberAsc(admin.getRequest().getId()))
            .thenReturn(List.of(admin.getBloodUnit(), stillHeld));

        service.completeAdministration(admin.getId(), 300);

        assertThat(admin.getRequest().getStatus()).isNotEqualTo(TransfusionRequestStatus.COMPLETED);
    }

    @Test
    void administrationsAndReactionsAreReadableForAPatient() {
        TransfusionAdministration admin = inProgress();
        when(administrationRepository.findByPatient_IdAndHospital_IdOrderByStartedAtDesc(patientId, hospitalId))
            .thenReturn(List.of(admin));
        when(reactionRepository.findByPatient_IdAndHospital_IdOrderByOnsetAtDesc(patientId, hospitalId))
            .thenReturn(List.of(TransfusionReaction.builder()
                .administration(admin).patient(patient).hospital(hospital)
                .reactionType(TransfusionReactionType.ALLERGIC)
                .severity(TransfusionReactionSeverity.MILD)
                .onsetAt(LocalDateTime.now())
                .signsSymptoms("Urticaria")
                .reportedAt(LocalDateTime.now())
                .build()));

        assertThat(service.listAdministrationsForPatient(patientId)).hasSize(1);
        assertThat(service.listReactionsForPatient(patientId)).singleElement()
            .satisfies(r -> assertThat(r.getSevere()).isFalse());
    }

    @Test
    void aUnitAtAnotherHospitalIsNotFoundRatherThanForbidden() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        BloodUnit foreign = BloodUnit.builder().hospital(other).build();
        UUID id = UUID.randomUUID();
        foreign.setId(id);
        when(unitRepository.findById(id)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.discardUnit(id, "Not mine"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Platelets: this facility's protocol, signed off 2026-08-25 ───────
    //
    // Exercised through the service, not just against AboGroup, because the
    // Rh half depends on patient demographics the enum deliberately does not
    // see, and the override path lives here.

    private TransfusionRequest plateletRequest(PatientBloodGroup grp) {
        TransfusionRequest r = TransfusionRequest.builder()
            .patient(patient)
            .hospital(hospital)
            .bloodGroup(grp)
            .productType(BloodProductType.PLATELETS)
            .unitsRequested(1)
            .indication("Thrombocytopenia")
            .urgency(TransfusionUrgency.ROUTINE)
            .status(TransfusionRequestStatus.REQUESTED)
            .requestedAt(LocalDateTime.now())
            .build();
        r.setId(UUID.randomUUID());
        when(requestRepository.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    private CrossmatchRequestDTO crossmatchWithReason(BloodUnit unit, String reason) {
        return CrossmatchRequestDTO.builder()
            .bloodUnitId(unit.getId())
            .compatible(Boolean.TRUE)
            .incompatibilityReason(reason)
            .build();
    }

    @Test
    void oPlateletsCannotBeRecordedCompatibleForAnARecipient() {
        // The one ABO exclusion the protocol keeps.
        TransfusionRequest req = plateletRequest(
            group(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE));
        BloodUnit donor = unit(AboGroup.O, RhFactor.POSITIVE, BloodProductType.PLATELETS,
            BloodUnitStatus.AVAILABLE);
        UUID requestId = req.getId();
        CrossmatchRequestDTO verdict = compatibleCrossmatch(donor);

        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("incompatible");
    }

    @Test
    void aReasonDoesNotOverrideThePlateletAboExclusion() {
        // An override reason is not a substitute for the one ABO rule.
        TransfusionRequest req = plateletRequest(
            group(AboGroup.AB, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE));
        BloodUnit donor = unit(AboGroup.O, RhFactor.POSITIVE, BloodProductType.PLATELETS,
            BloodUnitStatus.AVAILABLE);
        UUID requestId = req.getId();
        CrossmatchRequestDTO verdict = crossmatchWithReason(donor, "No other units held");

        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void aboIncompatiblePlateletsAreAcceptedWhereTheProtocolAllowsThem() {
        // B receiving O platelets: permitted, and the asymmetry with A is
        // the protocol as signed off.
        patient.setGender("M");
        patient.setDateOfBirth(java.time.LocalDate.of(1990, 1, 1));
        TransfusionRequest req = plateletRequest(
            group(AboGroup.B, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE));
        BloodUnit donor = unit(AboGroup.O, RhFactor.POSITIVE, BloodProductType.PLATELETS,
            BloodUnitStatus.AVAILABLE);

        assertThatCode(() -> service.recordCrossmatch(req.getId(), compatibleCrossmatch(donor)))
            .doesNotThrowAnyException();
    }

    @Test
    void aManMayReceiveRhPositivePlateletsWithNoOverrideNeeded() {
        patient.setGender("M");
        patient.setDateOfBirth(java.time.LocalDate.of(1985, 4, 2));
        TransfusionRequest req = plateletRequest(
            group(AboGroup.A, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE));
        BloodUnit donor = unit(AboGroup.A, RhFactor.POSITIVE, BloodProductType.PLATELETS,
            BloodUnitStatus.AVAILABLE);

        assertThatCode(() -> service.recordCrossmatch(req.getId(), compatibleCrossmatch(donor)))
            .doesNotThrowAnyException();
    }

    @Test
    void aWomanUnderFiftyFiveIsRefusedRhPositivePlateletsWithoutAReason() {
        patient.setGender("F");
        patient.setDateOfBirth(java.time.LocalDate.of(1995, 6, 1));
        TransfusionRequest req = plateletRequest(
            group(AboGroup.A, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE));
        BloodUnit donor = unit(AboGroup.A, RhFactor.POSITIVE, BloodProductType.PLATELETS,
            BloodUnitStatus.AVAILABLE);
        UUID requestId = req.getId();
        CrossmatchRequestDTO verdict = compatibleCrossmatch(donor);

        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
            .isInstanceOf(BusinessException.class)
            // The refusal must say how to proceed, not just that it refused.
            .hasMessageContaining("reason to override");
    }

    @Test
    void aRecordedReasonCarriesThePlateletRhRestriction() {
        patient.setGender("F");
        patient.setDateOfBirth(java.time.LocalDate.of(1995, 6, 1));
        TransfusionRequest req = plateletRequest(
            group(AboGroup.A, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE));
        BloodUnit donor = unit(AboGroup.A, RhFactor.POSITIVE, BloodProductType.PLATELETS,
            BloodUnitStatus.AVAILABLE);

        assertThatCode(() -> service.recordCrossmatch(req.getId(),
            crossmatchWithReason(donor, "Only units held; haematologist consulted")))
            .doesNotThrowAnyException();
    }

    @Test
    void anUnrecordedGenderIsProtectedRatherThanPermitted() {
        // gender is free text with no canonical vocabulary, so this is the
        // common case, not the exotic one.
        patient.setGender(null);
        patient.setDateOfBirth(java.time.LocalDate.of(1995, 6, 1));
        TransfusionRequest req = plateletRequest(
            group(AboGroup.A, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE));
        BloodUnit donor = unit(AboGroup.A, RhFactor.POSITIVE, BloodProductType.PLATELETS,
            BloodUnitStatus.AVAILABLE);
        UUID requestId = req.getId();
        CrossmatchRequestDTO verdict = compatibleCrossmatch(donor);

        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void theRhOverrideDoesNotLeakIntoRedCells() {
        // Red cells keep the unconditional rule: a reason must not buy an
        // Rh-positive red cell unit for an Rh-negative recipient.
        patient.setGender("M");
        patient.setDateOfBirth(java.time.LocalDate.of(1985, 4, 2));
        TransfusionRequest req = request(
            group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        BloodUnit donor = unit(AboGroup.O, RhFactor.POSITIVE, BloodProductType.PACKED_RED_CELLS,
            BloodUnitStatus.AVAILABLE);
        UUID requestId = req.getId();
        CrossmatchRequestDTO verdict = crossmatchWithReason(donor, "Nothing else available");

        assertThatThrownBy(() -> service.recordCrossmatch(requestId, verdict))
            .isInstanceOf(BusinessException.class);
    }

    // ── QA D930 class (round 16 siblings): the requester cancels ────────

    private static Staff requester(UUID userId) {
        com.example.hms.model.User user = new com.example.hms.model.User();
        user.setId(userId);
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(user);
        return staff;
    }

    private static void signInAs(UUID userId) {
        com.example.hms.model.User user = new com.example.hms.model.User();
        user.setId(userId);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @Test
    void d930_aColleagueCannotCancelAnotherCliniciansRequest() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        req.setRequestedBy(requester(UUID.randomUUID()));
        UUID requestId = req.getId();
        signInAs(UUID.randomUUID());
        try {
            // Review of #976: the refusal states the requester-only rule (its
            // own key), not the generic identity.notCaller message.
            assertThatThrownBy(() -> service.cancelRequest(requestId, "Not mine"))
                .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class)
                .extracting(Throwable::getMessage)
                .isIn("transfusion.request.cancel.requesterOnly",
                    "Only the clinician who requested this transfusion can cancel it.");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        assertThat(req.getStatus()).isEqualTo(TransfusionRequestStatus.REQUESTED);
        verify(requestRepository, never()).save(any());
    }

    @Test
    void d930_theRequesterCancelsHerRequest() {
        TransfusionRequest req = request(group(AboGroup.O, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE),
            TransfusionUrgency.ROUTINE);
        UUID me = UUID.randomUUID();
        req.setRequestedBy(requester(me));
        signInAs(me);
        try {
            service.cancelRequest(req.getId(), "Patient stabilised");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        assertThat(req.getStatus()).isEqualTo(TransfusionRequestStatus.CANCELLED);
    }
}

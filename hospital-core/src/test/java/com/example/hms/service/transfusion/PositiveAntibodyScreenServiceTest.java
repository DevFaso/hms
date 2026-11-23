package com.example.hms.service.transfusion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.enums.AntibodyWorkupStatus;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.enums.TransfusionUrgency;
import com.example.hms.enums.VitalUrgencyLevel;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.BloodUnit;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAntibodyWorkup;
import com.example.hms.model.PatientBloodGroup;
import com.example.hms.model.TransfusionCrossmatch;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.transfusion.AntibodyStatusResponseDTO;
import com.example.hms.payload.dto.transfusion.AntibodyWorkupRequestDTO;
import com.example.hms.payload.dto.transfusion.CrossmatchRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestRequestDTO;
import com.example.hms.repository.PatientAntibodyWorkupRepository;
import com.example.hms.repository.PatientBloodGroupRepository;
import com.example.hms.repository.TransfusionRequestRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.service.support.PositiveAntibodyScreenPolicy;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeAll;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D3351 (r70), provisional rule TRANSFUSION_POSITIVE_ANTIBODY_SCREEN: every transition the research report asks
 * a guard for: a later negative screen, the routine block and what lifts it, « urgence relative » attempting the
 * emergency path, a super-admin and a technician recording the antibody, and the tasks to the biologist.
 */
class PositiveAntibodyScreenServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 22, 10, 0);

    private final PatientBloodGroupRepository groups = mock(PatientBloodGroupRepository.class);
    private final PatientAntibodyWorkupRepository workups = mock(PatientAntibodyWorkupRepository.class);
    private final TransfusionRequestRepository requests = mock(TransfusionRequestRepository.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final InBasketService inBasket = mock(InBasketService.class);
    private final PositiveAntibodyScreenService service = new PositiveAntibodyScreenService(groups, workups, requests,
        assignments, users, roles, inBasket, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

    private final List<PatientBloodGroup> history = new ArrayList<>();
    private Patient patient;
    private Hospital hospital;
    private UUID biologist;

    @BeforeAll
    static void realBundles() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
    }

    @BeforeEach
    void wire() {
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Awa");
        patient.setLastName("Sawadogo");
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        biologist = UUID.randomUUID();
        // The patient's positive screens, at every hospital, the newest first: as the repository returns them.
        when(groups.findByPatient_IdAndAntibodyScreenOrderByPerformedAtDesc(patient.getId(), AntibodyScreenResult.POSITIVE))
            .thenAnswer(call -> history.stream()
                .filter(group -> group.getAntibodyScreen() == AntibodyScreenResult.POSITIVE).toList());
        when(roles.getCurrentUserId()).thenReturn(biologist);
        when(assignments.existsActiveWitness(biologist, hospital.getId(), PositiveAntibodyScreenPolicy.BIOLOGIST_ROLE_CODES))
            .thenReturn(true);
        when(workups.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Newest first, as the repository returns them. */
    private PatientBloodGroup screen(AntibodyScreenResult result, LocalDateTime at) {
        PatientBloodGroup group = PatientBloodGroup.builder().patient(patient).hospital(hospital)
            .antibodyScreen(result).performedAt(at).build();
        group.setId(UUID.randomUUID());
        history.add(0, group);
        return group;
    }

    private TransfusionRequest request(TransfusionUrgency urgency, BloodProductType product) {
        TransfusionRequest request = TransfusionRequest.builder().patient(patient).hospital(hospital)
            .productType(product).urgency(urgency).status(TransfusionRequestStatus.REQUESTED).build();
        request.setId(UUID.randomUUID());
        return request;
    }

    private void identified() {
        when(workups.findFirstByPatient_IdAndStatusOrderByRecordedAtDesc(patient.getId(),
            AntibodyWorkupStatus.IDENTIFIED)).thenReturn(Optional.of(PatientAntibodyWorkup.builder()
                .status(AntibodyWorkupStatus.IDENTIFIED).antibodySpecificities("anti-K").recordedAt(NOW).build()));
    }

    private static CrossmatchRequestDTO.CrossmatchRequestDTOBuilder compatibilised() {
        return CrossmatchRequestDTO.builder().bloodUnitId(UUID.randomUUID()).compatible(true)
            .antiglobulinMethod(true).antigenNegative(true).rhKellMatched(true).sampleCollectedAt(NOW.minusHours(10));
    }

    /** The rule's call alone: the result it judges is built before, outside the lambda (Sonar S5778). */
    private ThrowingCallable crossmatched(TransfusionRequest request, CrossmatchRequestDTO result) {
        return () -> service.requireCompatibilised(request, result);
    }

    private static String field(Throwable refusal) {
        return ((FieldBusinessException) refusal).getField();
    }

    // ── The flag ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a positive screen flags the patient, and a later negative screen never clears it")
    void aLaterNegativeScreenNeverClearsTheFlag() {
        assertThat(service.alloImmunised(patient.getId())).isFalse();

        screen(AntibodyScreenResult.POSITIVE, NOW.minusMonths(8));
        assertThat(service.alloImmunised(patient.getId())).isTrue();

        // Two negative screens since: the antibody is no longer detected, and still known.
        screen(AntibodyScreenResult.NEGATIVE, NOW.minusMonths(2));
        screen(AntibodyScreenResult.NEGATIVE, NOW.minusDays(1));
        assertThat(service.alloImmunised(patient.getId())).isTrue();
        AntibodyStatusResponseDTO status = service.status(patient.getId(), hospital.getId());
        assertThat(status.isAlloImmunised()).isTrue();
        assertThat(status.getPositiveScreenAt()).isEqualTo(NOW.minusMonths(8));
        assertThat(status.isIdentified()).isFalse();

        // The routine block still stands on the newest, negative, screen.
        TransfusionRequest routine = request(TransfusionUrgency.ROUTINE, BloodProductType.PACKED_RED_CELLS);
        assertThat(service.holds(routine)).isTrue();
        TransfusionCrossmatch plain = new TransfusionCrossmatch();
        assertThatThrownBy(() -> service.requireRoutineIssueAllowed(routine, plain))
            .isInstanceOf(FieldBusinessException.class);
    }

    @Test
    @DisplayName("an antibody the biologist recorded flags the patient with no positive screen here")
    void aBiologistsRecordFlagsThePatientToo() {
        when(workups.existsByPatient_Id(patient.getId())).thenReturn(true);
        assertThat(service.alloImmunised(patient.getId())).isTrue();
    }

    @Test
    @DisplayName("the rule holds red cells only, and only a flagged patient")
    void plasmaPlateletsAndAnUnflaggedPatientAreNotHeld() {
        TransfusionRequest redCells = request(TransfusionUrgency.ROUTINE, BloodProductType.PACKED_RED_CELLS);
        assertThat(service.holds(redCells)).isFalse();
        assertThatCode(() -> service.requireRoutineIssueAllowed(redCells, null)).doesNotThrowAnyException();
        assertThat(service.requireEmergencyReleaseAllowed(redCells)).isFalse();

        screen(AntibodyScreenResult.POSITIVE, NOW.minusDays(3));
        assertThat(service.holds(redCells)).isTrue();
        assertThat(service.holds(request(TransfusionUrgency.ROUTINE, BloodProductType.WHOLE_BLOOD))).isTrue();
        for (BloodProductType product : BloodProductType.values()) {
            if (!PositiveAntibodyScreenPolicy.appliesTo(product)) {
                assertThat(service.holds(request(TransfusionUrgency.ROUTINE, product))).as(product.name()).isFalse();
            }
        }
    }

    // ── The routine block ───────────────────────────────────────────────

    @Test
    @DisplayName("routine red cells are held until the antibody is identified, then until the crossmatch is « compatibilisé »")
    void theRoutineBlockAndWhatLiftsIt() {
        screen(AntibodyScreenResult.POSITIVE, NOW.minusDays(3));
        TransfusionRequest routine = request(TransfusionUrgency.ROUTINE, BloodProductType.PACKED_RED_CELLS);

        // D3351 as filed: a plain « Compatible » crossmatch, no identification: refused.
        assertThatThrownBy(crossmatched(routine, compatibilised().build()))
            .isInstanceOf(FieldBusinessException.class)
            .hasMessageContaining("allo-immunised").hasMessageContaining("provisional");

        identified();
        assertThatThrownBy(crossmatched(routine,
            compatibilised().antiglobulinMethod(null).build())).satisfies(e -> assertThat(field(e)).isEqualTo("antiglobulinMethod"));
        assertThatThrownBy(crossmatched(routine,
            compatibilised().antigenNegative(false).build())).satisfies(e -> assertThat(field(e)).isEqualTo("antigenNegative"));
        assertThatThrownBy(crossmatched(routine,
            compatibilised().rhKellMatched(null).build())).satisfies(e -> assertThat(field(e)).isEqualTo("rhKellMatched"));
        assertThatThrownBy(crossmatched(routine,
            compatibilised().sampleCollectedAt(null).build())).satisfies(e -> assertThat(field(e)).isEqualTo("sampleCollectedAt"));
        assertThatThrownBy(crossmatched(routine,
            compatibilised().sampleCollectedAt(NOW.plusMinutes(1)).build())).isInstanceOf(FieldBusinessException.class);
        // 72 hours and one minute: a new sample. Exactly 72 hours still stands.
        assertThatThrownBy(crossmatched(routine,
            compatibilised().sampleCollectedAt(NOW.minusHours(72).minusMinutes(1)).build()))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("72");
        assertThatCode(crossmatched(routine,
            compatibilised().sampleCollectedAt(NOW.minusHours(72)).build())).doesNotThrowAnyException();
        assertThatCode(crossmatched(routine, compatibilised().build())).doesNotThrowAnyException();

        // An incompatible result is always written: it stops a bag.
        assertThatCode(crossmatched(routine,
            CrossmatchRequestDTO.builder().compatible(false).build())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("at issue the crossmatch on file is judged NOW: an aged sample or one with no attestation holds the unit")
    void theCrossmatchOnFileIsJudgedAtIssue() {
        screen(AntibodyScreenResult.POSITIVE, NOW.minusDays(5));
        identified();
        TransfusionRequest urgent = request(TransfusionUrgency.URGENT, BloodProductType.PACKED_RED_CELLS);
        TransfusionCrossmatch good = TransfusionCrossmatch.builder().compatible(true).antiglobulinMethod(true)
            .antigenNegative(true).rhKellMatched(true).sampleCollectedAt(NOW.minusHours(70)).build();

        assertThatCode(() -> service.requireRoutineIssueAllowed(urgent, good)).doesNotThrowAnyException();

        good.setSampleCollectedAt(NOW.minusHours(73));
        assertThatThrownBy(() -> service.requireRoutineIssueAllowed(urgent, good))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("72");
        // A crossmatch written before the positive screen carries no attestation at all.
        TransfusionCrossmatch earlier = TransfusionCrossmatch.builder().compatible(true).build();
        assertThatThrownBy(() -> service.requireRoutineIssueAllowed(urgent, earlier))
            .isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> service.requireRoutineIssueAllowed(urgent, null))
            .isInstanceOf(FieldBusinessException.class);
    }

    @Test
    @DisplayName("a referral decision serves the one request it names, and no other")
    void aReferralDecisionServesItsRequestOnly() {
        screen(AntibodyScreenResult.POSITIVE, NOW.minusDays(1));
        TransfusionRequest served = request(TransfusionUrgency.URGENT, BloodProductType.PACKED_RED_CELLS);
        TransfusionRequest other = request(TransfusionUrgency.URGENT, BloodProductType.PACKED_RED_CELLS);
        when(workups.existsByPatient_IdAndHospital_IdAndStatusAndTransfusionRequestId(patient.getId(), hospital.getId(),
            AntibodyWorkupStatus.REFERRED, served.getId())).thenReturn(true);

        assertThat(service.workupServes(served)).isTrue();
        assertThat(service.workupServes(other)).isFalse();
        // The matched unit and the crossmatch are still required under the decision.
        assertThatThrownBy(crossmatched(served, compatibilised().rhKellMatched(false).build()))
            .isInstanceOf(FieldBusinessException.class);
        assertThatCode(crossmatched(served, compatibilised().build())).doesNotThrowAnyException();
        assertThatThrownBy(crossmatched(other, compatibilised().build()))
            .isInstanceOf(FieldBusinessException.class);
    }

    // ── The biologist's record ──────────────────────────────────────────

    @Test
    @DisplayName("only the hospital's biologist records the antibody: a super-admin alone, a technician and a prescriber are refused")
    void onlyTheBiologistRecordsTheAntibody() {
        AntibodyWorkupRequestDTO identified = AntibodyWorkupRequestDTO.builder().patientId(patient.getId())
            .status(AntibodyWorkupStatus.IDENTIFIED).antibodySpecificities(" anti-K, anti-E ").build();

        // A super-admin, a laboratory technician, a doctor: none holds the biologist's role at this hospital.
        UUID somebodyElse = UUID.randomUUID();
        when(roles.getCurrentUserId()).thenReturn(somebodyElse);
        assertThatThrownBy(() -> service.recordWorkup(identified, patient, hospital))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        when(roles.getCurrentUserId()).thenReturn(null);
        assertThatThrownBy(() -> service.recordWorkup(identified, patient, hospital))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        verify(workups, never()).save(any());
        verify(assignments).existsActiveWitness(somebodyElse, hospital.getId(), PositiveAntibodyScreenPolicy.BIOLOGIST_ROLE_CODES);

        when(roles.getCurrentUserId()).thenReturn(biologist);
        User director = new User();
        director.setFirstName("Fatou");
        director.setLastName("Kaboré");
        when(users.findById(biologist)).thenReturn(Optional.of(director));
        service.recordWorkup(identified, patient, hospital);
        ArgumentCaptor<PatientAntibodyWorkup> saved = ArgumentCaptor.forClass(PatientAntibodyWorkup.class);
        verify(workups).save(saved.capture());
        assertThat(saved.getValue().getAntibodySpecificities()).isEqualTo("anti-K, anti-E");
        assertThat(saved.getValue().getRecordedByUserId()).isEqualTo(biologist);
        assertThat(saved.getValue().getRecordedByDisplay()).isEqualTo("Fatou Kaboré");
        assertThat(saved.getValue().getRecordedAt()).isEqualTo(NOW);
        assertThat(PositiveAntibodyScreenPolicy.BIOLOGIST_ROLE_CODES).containsExactly("ROLE_LAB_DIRECTOR");
    }

    @Test
    @DisplayName("a record names the antibody, or the centre, the reason and an open red-cell request of this patient")
    void whatARecordMustCarry() {
        assertThatThrownBy(() -> service.recordWorkup(AntibodyWorkupRequestDTO.builder().patientId(patient.getId())
            .status(AntibodyWorkupStatus.IDENTIFIED).antibodySpecificities(" ").build(), patient, hospital))
            .satisfies(e -> assertThat(field(e)).isEqualTo("antibodySpecificities"));

        AntibodyWorkupRequestDTO.AntibodyWorkupRequestDTOBuilder referred = AntibodyWorkupRequestDTO.builder()
            .patientId(patient.getId()).status(AntibodyWorkupStatus.REFERRED);
        assertThatThrownBy(() -> service.recordWorkup(referred.build(), patient, hospital))
            .satisfies(e -> assertThat(field(e)).isEqualTo("referralCentre"));
        assertThatThrownBy(() -> service.recordWorkup(referred.referralCentre("CNTS Ouagadougou").build(), patient, hospital))
            .satisfies(e -> assertThat(field(e)).isEqualTo("decisionReason"));
        referred.decisionReason("Hémorragie active, identification sous 48 h");
        assertThatThrownBy(() -> service.recordWorkup(referred.build(), patient, hospital))
            .satisfies(e -> assertThat(field(e)).isEqualTo("transfusionRequestId"));

        // Another patient's request, a closed one, and plasma are not what a decision serves.
        TransfusionRequest plasma = request(TransfusionUrgency.URGENT, BloodProductType.FRESH_FROZEN_PLASMA);
        when(requests.findById(plasma.getId())).thenReturn(Optional.of(plasma));
        assertThatThrownBy(() -> service.recordWorkup(referred.transfusionRequestId(plasma.getId()).build(), patient, hospital))
            .satisfies(e -> assertThat(field(e)).isEqualTo("transfusionRequestId"));
        TransfusionRequest elsewhere = request(TransfusionUrgency.URGENT, BloodProductType.PACKED_RED_CELLS);
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        elsewhere.setHospital(otherHospital);
        when(requests.findById(elsewhere.getId())).thenReturn(Optional.of(elsewhere));
        assertThatThrownBy(() -> service.recordWorkup(referred.transfusionRequestId(elsewhere.getId()).build(), patient, hospital))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);

        TransfusionRequest open = request(TransfusionUrgency.URGENT, BloodProductType.PACKED_RED_CELLS);
        when(requests.findById(open.getId())).thenReturn(Optional.of(open));
        service.recordWorkup(referred.transfusionRequestId(open.getId()).build(), patient, hospital);
        ArgumentCaptor<PatientAntibodyWorkup> saved = ArgumentCaptor.forClass(PatientAntibodyWorkup.class);
        verify(workups).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AntibodyWorkupStatus.REFERRED);
        assertThat(saved.getValue().getReferralCentre()).isEqualTo("CNTS Ouagadougou");
        assertThat(saved.getValue().getTransfusionRequestId()).isEqualTo(open.getId());
    }

    // ── The emergency path ──────────────────────────────────────────────

    @Test
    @DisplayName("a vital emergency is declared on the request: level, reason, sample drawn first")
    void anEmergencyRequestIsDeclared() {
        screen(AntibodyScreenResult.POSITIVE, NOW.minusDays(3));
        TransfusionRequestRequestDTO.TransfusionRequestRequestDTOBuilder dto = TransfusionRequestRequestDTO.builder()
            .patientId(patient.getId()).productType(BloodProductType.PACKED_RED_CELLS);

        assertThatThrownBy(() -> service.requireEmergencyDeclaration(dto.build(), patient.getId(),
            TransfusionUrgency.EMERGENCY)).satisfies(e -> assertThat(field(e)).isEqualTo("vitalUrgencyLevel"));
        dto.vitalUrgencyLevel(VitalUrgencyLevel.UVI);
        assertThatThrownBy(() -> service.requireEmergencyDeclaration(dto.build(), patient.getId(),
            TransfusionUrgency.EMERGENCY)).satisfies(e -> assertThat(field(e)).isEqualTo("vitalUrgencyReason"));
        dto.vitalUrgencyReason("Hémorragie du post-partum, choc");
        assertThatThrownBy(() -> service.requireEmergencyDeclaration(dto.build(), patient.getId(),
            TransfusionUrgency.EMERGENCY)).satisfies(e -> assertThat(field(e)).isEqualTo("preTransfusionSampleDrawn"));
        dto.preTransfusionSampleDrawn(true);
        assertThatCode(() -> service.requireEmergencyDeclaration(dto.build(), patient.getId(),
            TransfusionUrgency.EMERGENCY)).doesNotThrowAnyException();

        // A routine or an urgent request, and plasma, declare nothing: they are held by the routine rule.
        TransfusionRequestRequestDTO bare = TransfusionRequestRequestDTO.builder().patientId(patient.getId())
            .productType(BloodProductType.PACKED_RED_CELLS).build();
        assertThatCode(() -> service.requireEmergencyDeclaration(bare, patient.getId(),
            TransfusionUrgency.URGENT)).doesNotThrowAnyException();
        assertThatCode(() -> service.requireEmergencyDeclaration(TransfusionRequestRequestDTO.builder()
            .patientId(patient.getId()).productType(BloodProductType.PLATELETS).build(), patient.getId(),
            TransfusionUrgency.EMERGENCY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the emergency path is an EMERGENCY request's; « urgence relative » never opens it")
    void urgenceRelativeNeverOpensTheEmergencyPath() {
        screen(AntibodyScreenResult.POSITIVE, NOW.minusDays(3));

        TransfusionRequest declared = request(TransfusionUrgency.EMERGENCY, BloodProductType.PACKED_RED_CELLS);
        declared.setVitalUrgencyLevel(VitalUrgencyLevel.UV);
        declared.setVitalUrgencyReason("Hémorragie digestive, instabilité");
        declared.setPreTransfusionSampleDrawn(true);
        assertThat(service.requireEmergencyReleaseAllowed(declared)).isTrue();
        // A declared vital emergency is not held by the routine rule either (a crossmatch on file, no attestation).
        assertThatCode(() -> service.requireRoutineIssueAllowed(declared, new TransfusionCrossmatch()))
            .doesNotThrowAnyException();

        // An emergency raised before the screen turned positive carries no declaration: it is not delayed, and
        // the crossmatch afterwards is asked for all the same (the caller files the task on true).
        TransfusionRequest undeclared = request(TransfusionUrgency.EMERGENCY, BloodProductType.PACKED_RED_CELLS);
        assertThat(service.requireEmergencyReleaseAllowed(undeclared)).isTrue();
        // Review M3: it leaves on a plain crossmatch without delay, and the crossmatch afterwards is then owed.
        TransfusionCrossmatch plain = new TransfusionCrossmatch();
        assertThat(service.requireRoutineIssueAllowed(undeclared, plain)).isTrue();

        // UR: an URGENT request carrying the very same declaration is still not an emergency.
        TransfusionRequest relative = request(TransfusionUrgency.URGENT, BloodProductType.PACKED_RED_CELLS);
        relative.setVitalUrgencyLevel(VitalUrgencyLevel.UVI);
        relative.setVitalUrgencyReason("pressé");
        relative.setPreTransfusionSampleDrawn(true);
        assertThatThrownBy(() -> service.requireEmergencyReleaseAllowed(relative))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("allo-immunised");
    }

    @Test
    @DisplayName("a result around an emergency release is recorded as found, declared or raised before the screen turned positive")
    void anEmergencyRaisedBeforeTheScreenTurnedPositiveIsNotDelayed() {
        screen(AntibodyScreenResult.POSITIVE, NOW.minusDays(3));
        TransfusionRequest declared = request(TransfusionUrgency.EMERGENCY, BloodProductType.PACKED_RED_CELLS);
        declared.setVitalUrgencyLevel(VitalUrgencyLevel.UVI);
        declared.setVitalUrgencyReason("Choc hémorragique");
        declared.setPreTransfusionSampleDrawn(true);

        // No work-up yet, no attestation: the result on the retained segment is still written.
        assertThatCode(crossmatched(declared,
            CrossmatchRequestDTO.builder().compatible(true).build())).doesNotThrowAnyException();
        // An emergency raised before the screen turned positive: recorded as found too.
        TransfusionRequest undeclared = request(TransfusionUrgency.EMERGENCY, BloodProductType.PACKED_RED_CELLS);
        assertThatCode(crossmatched(undeclared,
            CrossmatchRequestDTO.builder().compatible(true).build())).doesNotThrowAnyException();
        // « Urgence relative » is not an emergency: the same result is held.
        TransfusionRequest relative = request(TransfusionUrgency.URGENT, BloodProductType.PACKED_RED_CELLS);
        assertThatThrownBy(crossmatched(relative,
            CrossmatchRequestDTO.builder().compatible(true).build())).isInstanceOf(FieldBusinessException.class);
    }

    // ── The biologist's tasks ───────────────────────────────────────────

    @Test
    @DisplayName("a positive screen asks every biologist to identify or refer, and so does a positive screen after an identification")
    void aPositiveScreenAsksTheBiologist() {
        User one = new User();
        one.setId(UUID.randomUUID());
        User two = new User();
        two.setId(UUID.randomUUID());
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(hospital.getId(), PositiveAntibodyScreenPolicy.BIOLOGIST_ROLE_CODES))
            .thenReturn(List.of(one, two));

        service.askBiologistToIdentify(screen(AntibodyScreenResult.NEGATIVE, NOW));
        verify(inBasket, never()).createItemInNewTransaction(any());

        PatientBloodGroup positive = screen(AntibodyScreenResult.POSITIVE, NOW);
        service.askBiologistToIdentify(positive);
        ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket, times(2)).createItemInNewTransaction(items.capture());
        CreateInBasketItemRequest item = items.getAllValues().get(0);
        assertThat(items.getAllValues()).extracting(CreateInBasketItemRequest::getRecipientUserId)
            .containsExactly(one.getId(), two.getId());
        assertThat(item.getItemType()).isEqualTo(InBasketItemType.TASK);
        assertThat(item.getPriority()).isEqualTo(InBasketPriority.URGENT);
        assertThat(item.getHospitalId()).isEqualTo(hospital.getId());
        assertThat(item.getReferenceId()).isEqualTo(positive.getId());
        assertThat(item.getTitleKey()).isEqualTo(PositiveAntibodyScreenService.IDENTIFY_TITLE_KEY);
        // Staff-language copy, with the provisional marker.
        assertThat(item.getTitle()).contains("RAI positive").contains("Awa Sawadogo");
        assertThat(item.getBody()).contains("provisoire");

        // Review L4: an antibody is identified; a new positive screen may be a second one, so it asks again.
        identified();
        service.askBiologistToIdentify(screen(AntibodyScreenResult.POSITIVE, NOW.plusDays(3)));
        verify(inBasket, times(4)).createItemInNewTransaction(any());
    }

    @Test
    @DisplayName("an emergency release to an allo-immunised patient asks the biologist for the crossmatch afterwards, with the haemolysis warning")
    void anEmergencyReleaseAsksForTheCrossmatchAfterwards() {
        User one = new User();
        one.setId(UUID.randomUUID());
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(hospital.getId(), PositiveAntibodyScreenPolicy.BIOLOGIST_ROLE_CODES))
            .thenReturn(List.of(one));
        TransfusionRequest declared = request(TransfusionUrgency.EMERGENCY, BloodProductType.PACKED_RED_CELLS);
        declared.setVitalUrgencyLevel(VitalUrgencyLevel.UVI);
        BloodUnit unit = BloodUnit.builder().unitNumber("O-NEG-0042").build();
        unit.setId(UUID.randomUUID());

        service.askRetrospectiveCrossmatch(declared, unit);

        ArgumentCaptor<CreateInBasketItemRequest> item = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket).createItemInNewTransaction(item.capture());
        assertThat(item.getValue().getRecipientUserId()).isEqualTo(one.getId());
        assertThat(item.getValue().getReferenceId()).isEqualTo(unit.getId());
        assertThat(item.getValue().getTitle()).contains("O-NEG-0042").contains("a posteriori");
        assertThat(item.getValue().getBody()).contains("hémolyse").contains("UVI").contains("Awa Sawadogo");
    }

    @Test
    @DisplayName("with no biologist at the hospital no task is filed, and the block stands all the same")
    void noBiologistNoTaskAndTheBlockStands() {
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of());
        PatientBloodGroup positive = screen(AntibodyScreenResult.POSITIVE, NOW);

        service.askBiologistToIdentify(positive);

        verify(inBasket, never()).createItemInNewTransaction(any());
        TransfusionRequest routine = request(TransfusionUrgency.ROUTINE, BloodProductType.PACKED_RED_CELLS);
        assertThatThrownBy(crossmatched(routine, compatibilised().build()))
            .isInstanceOf(FieldBusinessException.class);
    }

    @Test
    @DisplayName("a filing that fails stops neither the other biologists' tasks nor the caller")
    void aFilingThatFailsStopsNeitherTheOthersNorTheCaller() {
        User one = new User();
        one.setId(UUID.randomUUID());
        User two = new User();
        two.setId(UUID.randomUUID());
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(hospital.getId(), PositiveAntibodyScreenPolicy.BIOLOGIST_ROLE_CODES))
            .thenReturn(List.of(one, two, one));
        when(inBasket.createItemInNewTransaction(any())).thenThrow(new IllegalStateException("in-basket down"))
            .thenReturn(null);
        PatientBloodGroup positive = screen(AntibodyScreenResult.POSITIVE, NOW);

        assertThatCode(() -> service.askBiologistToIdentify(positive)).doesNotThrowAnyException();

        // Each biologist once, the second one still asked after the first filing failed.
        ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket, times(2)).createItemInNewTransaction(items.capture());
        assertThat(items.getAllValues()).extracting(CreateInBasketItemRequest::getRecipientUserId)
            .containsExactly(one.getId(), two.getId());
    }

    @Test
    @DisplayName("an incomplete call flags nobody, holds nothing and asks nobody")
    void anIncompleteCallAsksNothing() {
        screen(AntibodyScreenResult.POSITIVE, NOW);

        assertThat(service.alloImmunised(null)).isFalse();
        assertThat(service.alloImmunisedShownAt(null, hospital.getId())).isFalse();
        assertThat(service.status(null, hospital.getId()).isAlloImmunised()).isFalse();
        assertThat(service.status(null, hospital.getId()).getWorkups()).isEmpty();
        assertThat(service.holdsShown(null)).isFalse();
        assertThat(service.holds(null)).isFalse();
        TransfusionRequest nobody = request(TransfusionUrgency.ROUTINE, BloodProductType.PACKED_RED_CELLS);
        nobody.setPatient(null);
        assertThat(service.holds(nobody)).isFalse();
        TransfusionRequest nowhere = request(TransfusionUrgency.ROUTINE, BloodProductType.PACKED_RED_CELLS);
        nowhere.setHospital(null);
        assertThat(service.holds(nowhere)).isFalse();

        service.askBiologistToIdentify(null);
        PatientBloodGroup noPatient = PatientBloodGroup.builder().hospital(hospital)
            .antibodyScreen(AntibodyScreenResult.POSITIVE).performedAt(NOW).build();
        service.askBiologistToIdentify(noPatient);
        PatientBloodGroup noHospital = PatientBloodGroup.builder().patient(patient)
            .antibodyScreen(AntibodyScreenResult.POSITIVE).performedAt(NOW).build();
        service.askBiologistToIdentify(noHospital);
        verify(inBasket, never()).createItemInNewTransaction(any());
    }

    @Test
    @DisplayName("the status names the patient, the positive screen and what the biologist recorded, newest first")
    void theStatusShowsTheScreenAndTheRecords() {
        PatientBloodGroup positive = screen(AntibodyScreenResult.POSITIVE, NOW.minusDays(40));
        screen(AntibodyScreenResult.NEGATIVE, NOW.minusDays(1));
        PatientAntibodyWorkup referred = PatientAntibodyWorkup.builder().hospital(hospital).status(AntibodyWorkupStatus.REFERRED)
            .referralCentre("CNTS").decisionReason("Hb 5").recordedAt(NOW.minusDays(39)).recordedByDisplay("Dr Kaboré").build();
        PatientAntibodyWorkup named = PatientAntibodyWorkup.builder().hospital(hospital).status(AntibodyWorkupStatus.IDENTIFIED)
            .antibodySpecificities("anti-K").recordedAt(NOW.minusDays(36)).recordedByDisplay("Dr Kaboré").build();
        when(workups.findByPatient_IdOrderByRecordedAtDesc(patient.getId()))
            .thenReturn(List.of(named, referred));

        AntibodyStatusResponseDTO status = service.status(patient.getId(), hospital.getId(), "Awa Sawadogo");

        assertThat(status.getPatientName()).isEqualTo("Awa Sawadogo");
        assertThat(status.isAlloImmunised()).isTrue();
        assertThat(status.getPositiveScreenAt()).isEqualTo(positive.getPerformedAt());
        assertThat(status.isIdentified()).isTrue();
        assertThat(status.getAntibodySpecificities()).isEqualTo("anti-K");
        assertThat(status.getWorkups()).extracting(AntibodyStatusResponseDTO.Workup::getStatus)
            .containsExactly(AntibodyWorkupStatus.IDENTIFIED, AntibodyWorkupStatus.REFERRED);
        assertThat(status.getWorkups().get(1).getReferralCentre()).isEqualTo("CNTS");
        assertThat(status.getWorkups().get(1).getRecordedByDisplay()).isEqualTo("Dr Kaboré");
    }

    // ── Review of #1291: L4 and M3 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("review L4: an identification covers the positive screens before it, not one recorded after it: routine red cells are held again until the biologist records the work-up again")
    void aPositiveScreenAfterAnIdentificationIsNotCoveredByIt() {
        screen(AntibodyScreenResult.POSITIVE, NOW.minusDays(10));
        TransfusionRequest routine = request(TransfusionUrgency.ROUTINE, BloodProductType.PACKED_RED_CELLS);
        PatientAntibodyWorkup known = PatientAntibodyWorkup.builder().status(AntibodyWorkupStatus.IDENTIFIED)
            .antibodySpecificities("anti-K").recordedAt(NOW.minusDays(9)).build();
        when(workups.findFirstByPatient_IdAndStatusOrderByRecordedAtDesc(patient.getId(),
            AntibodyWorkupStatus.IDENTIFIED)).thenReturn(Optional.of(known));
        CrossmatchRequestDTO complete = compatibilised().build();

        // Identified since the screen: served. A later NEGATIVE screen changes nothing.
        assertThat(service.workupServes(routine)).isTrue();
        screen(AntibodyScreenResult.NEGATIVE, NOW.minusDays(5));
        assertThat(service.workupServes(routine)).isTrue();
        assertThatCode(crossmatched(routine, complete)).doesNotThrowAnyException();

        // A new positive screen, after the identification: possibly a second antibody. Held again.
        screen(AntibodyScreenResult.POSITIVE, NOW.minusHours(1));
        assertThat(service.workupServes(routine)).isFalse();
        assertThatThrownBy(crossmatched(routine, complete))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal -> assertThat(refusal.getMessageKey())
                .isEqualTo(PositiveAntibodyScreenPolicy.Missing.WORKUP.messageKey()));

        // The biologist records the work-up of that screen: served again (the same instant counts).
        known.setRecordedAt(NOW.minusHours(1));
        assertThat(service.workupServes(routine)).isTrue();
        assertThatCode(crossmatched(routine, complete)).doesNotThrowAnyException();

        // A record with no time on it covers nothing.
        known.setRecordedAt(null);
        assertThat(service.workupServes(routine)).isFalse();
    }

    @Test
    @DisplayName("review L4: an antibody recorded with no positive screen here (known from elsewhere) serves, until a positive screen comes")
    void anAntibodyKnownFromElsewhereServesUntilAPositiveScreenHere() {
        when(workups.existsByPatient_Id(patient.getId())).thenReturn(true);
        PatientAntibodyWorkup known = PatientAntibodyWorkup.builder().status(AntibodyWorkupStatus.IDENTIFIED)
            .antibodySpecificities("anti-E").recordedAt(NOW.minusDays(30)).build();
        when(workups.findFirstByPatient_IdAndStatusOrderByRecordedAtDesc(patient.getId(),
            AntibodyWorkupStatus.IDENTIFIED)).thenReturn(Optional.of(known));
        TransfusionRequest routine = request(TransfusionUrgency.ROUTINE, BloodProductType.PACKED_RED_CELLS);

        assertThat(service.holds(routine)).isTrue();
        assertThat(service.workupServes(routine)).isTrue();

        screen(AntibodyScreenResult.POSITIVE, NOW.minusHours(2));
        assertThat(service.workupServes(routine)).isFalse();
    }

    @Test
    @DisplayName("review M3: an EMERGENCY request that leaves on a standing crossmatch owes the crossmatch afterwards unless that crossmatch is « compatibilisé »; nothing is owed for a patient the rule does not hold")
    void anEmergencyOnAPlainCrossmatchOwesTheCrossmatchAfterwards() {
        TransfusionRequest emergency = request(TransfusionUrgency.EMERGENCY, BloodProductType.PACKED_RED_CELLS);
        TransfusionCrossmatch plain = TransfusionCrossmatch.builder().compatible(true).build();
        TransfusionCrossmatch complete = TransfusionCrossmatch.builder().compatible(true).antiglobulinMethod(true)
            .antigenNegative(true).rhKellMatched(true).sampleCollectedAt(NOW.minusHours(3)).build();

        // Not allo-immunised: the rule has nothing to ask.
        assertThat(service.requireRoutineIssueAllowed(emergency, plain)).isFalse();

        screen(AntibodyScreenResult.POSITIVE, NOW.minusDays(2));
        // Never refused, never delayed; the plain result is not the antiglobulin crossmatch on a matched unit.
        assertThat(service.requireRoutineIssueAllowed(emergency, plain)).isTrue();
        assertThat(service.requireRoutineIssueAllowed(emergency, null)).isTrue();
        // Complete attestations but no identification yet: still owed.
        assertThat(service.requireRoutineIssueAllowed(emergency, complete)).isTrue();

        identified();
        assertThat(service.requireRoutineIssueAllowed(emergency, complete)).isFalse();
        assertThat(service.requireRoutineIssueAllowed(emergency, plain)).isTrue();

        // A routine request is refused instead, as before, and owes nothing afterwards once it is in order.
        TransfusionRequest routine = request(TransfusionUrgency.ROUTINE, BloodProductType.PACKED_RED_CELLS);
        assertThatThrownBy(() -> service.requireRoutineIssueAllowed(routine, plain))
            .isInstanceOf(FieldBusinessException.class);
        assertThat(service.requireRoutineIssueAllowed(routine, complete)).isFalse();
    }

    // ── Owner decision 2026-11-22: the flag follows the patient across hospitals ───────────────────

    private Hospital anotherHospital(String name) {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        other.setName(name);
        return other;
    }

    @Test
    @DisplayName("positive at hospital A, negative later at hospital B: still flagged at B, red cells held at B, and the read at B is accounted against A")
    void aFlagRecordedAtAnotherHospitalFollowsThePatient() {
        com.example.hms.service.recordaccess.CrossHospitalReachRecorder reach =
            mock(com.example.hms.service.recordaccess.CrossHospitalReachRecorder.class);
        service.setReachRecorder(reach);
        hospital.setName("Hôpital B");
        Hospital hospitalA = anotherHospital("CHU A");
        // The positive screen was recorded at A; the newest screen, at B (this hospital), is negative.
        PatientBloodGroup atA = screen(AntibodyScreenResult.POSITIVE, NOW.minusDays(200));
        atA.setHospital(hospitalA);
        screen(AntibodyScreenResult.NEGATIVE, NOW.minusHours(2));
        TransfusionRequest atB = request(TransfusionUrgency.ROUTINE, BloodProductType.PACKED_RED_CELLS);

        assertThat(service.alloImmunised(patient.getId())).isTrue();
        assertThat(service.holds(atB)).isTrue();
        assertThat(service.workupServes(atB)).isFalse();
        assertThatThrownBy(crossmatched(atB, compatibilised().build()))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal -> assertThat(refusal.getMessageKey())
                .isEqualTo(PositiveAntibodyScreenPolicy.Missing.WORKUP.messageKey()));
        // Enforcing the rule shows nothing to anybody: nothing is accounted for it.
        org.mockito.Mockito.verifyNoInteractions(reach);

        AntibodyStatusResponseDTO shown = service.status(patient.getId(), hospital.getId(), "Awa Sawadogo");

        assertThat(shown.isAlloImmunised()).isTrue();
        assertThat(shown.isIdentified()).isFalse();
        assertThat(shown.getPositiveScreenAt()).isEqualTo(atA.getPerformedAt());
        assertThat(shown.getPositiveScreenHospitalName()).isEqualTo("CHU A");
        assertThat(shown.getOtherHospitalNames()).containsExactly("CHU A");
        assertThat(shown.getWorkups()).isEmpty();
        ArgumentCaptor<java.util.Map<String, Long>> perSource = ArgumentCaptor.captor();
        verify(reach).recordReach(org.mockito.ArgumentMatchers.eq(patient.getId()),
            org.mockito.ArgumentMatchers.eq(hospital.getId()), org.mockito.ArgumentMatchers.eq(biologist),
            org.mockito.ArgumentMatchers.isNull(), perSource.capture(),
            org.mockito.ArgumentMatchers.eq(PositiveAntibodyScreenService.REACH_DESCRIPTION));
        assertThat(perSource.getValue()).containsOnlyKeys(hospitalA.getId().toString());

        // The flag shown on the group and on the request is accounted the same way.
        assertThat(service.alloImmunisedShownAt(patient.getId(), hospital.getId())).isTrue();
        assertThat(service.holdsShown(atB)).isTrue();
        verify(reach, times(3)).recordReach(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an antibody identified at hospital A serves at hospital B; of A's record B sees the antibody, the date and A's name, and nothing of its text")
    void anIdentificationMadeElsewhereServesAndShowsTheMinimum() {
        hospital.setName("Hôpital B");
        Hospital hospitalA = anotherHospital("CHU A");
        PatientBloodGroup atA = screen(AntibodyScreenResult.POSITIVE, NOW.minusDays(200));
        atA.setHospital(hospitalA);
        PatientAntibodyWorkup identifiedAtA = PatientAntibodyWorkup.builder().hospital(hospitalA).patient(patient)
            .status(AntibodyWorkupStatus.IDENTIFIED).antibodySpecificities("anti-K").recordedAt(NOW.minusDays(199))
            .recordedByDisplay("Dr Kaboré (A)").notes("Titre 1/64, panel du 2026-05-04").build();
        identifiedAtA.setId(UUID.randomUUID());
        PatientAntibodyWorkup referredAtA = PatientAntibodyWorkup.builder().hospital(hospitalA).patient(patient)
            .status(AntibodyWorkupStatus.REFERRED).referralCentre("CNTS Ouagadougou").decisionReason("Hb 5 g/dL")
            .transfusionRequestId(UUID.randomUUID()).recordedAt(NOW.minusDays(200)).recordedByDisplay("Dr Kaboré (A)").build();
        PatientAntibodyWorkup referredHere = PatientAntibodyWorkup.builder().hospital(hospital).patient(patient)
            .status(AntibodyWorkupStatus.REFERRED).referralCentre("CRTS Bobo").decisionReason("Hémorragie")
            .recordedAt(NOW.minusDays(1)).recordedByDisplay("Dr Sanou (B)").notes("note d'ici").build();
        when(workups.findByPatient_IdOrderByRecordedAtDesc(patient.getId()))
            .thenReturn(List.of(referredHere, identifiedAtA, referredAtA));
        when(workups.findFirstByPatient_IdAndStatusOrderByRecordedAtDesc(patient.getId(), AntibodyWorkupStatus.IDENTIFIED))
            .thenReturn(Optional.of(identifiedAtA));
        TransfusionRequest atB = request(TransfusionUrgency.ROUTINE, BloodProductType.PACKED_RED_CELLS);

        // B can make a unit compatible: the antibody is known.
        assertThat(service.workupServes(atB)).isTrue();
        assertThatCode(crossmatched(atB, compatibilised().build())).doesNotThrowAnyException();

        AntibodyStatusResponseDTO shown = service.status(patient.getId(), hospital.getId());

        assertThat(shown.isIdentified()).isTrue();
        assertThat(shown.getAntibodySpecificities()).isEqualTo("anti-K");
        assertThat(shown.getIdentifiedHospitalName()).isEqualTo("CHU A");
        assertThat(shown.getIdentifiedAt()).isEqualTo(NOW.minusDays(199));
        assertThat(shown.getOtherHospitalNames()).containsExactly("CHU A");
        // This hospital's own record in full; A's identification with the minimum; A's referral not listed.
        assertThat(shown.getWorkups()).hasSize(2);
        AntibodyStatusResponseDTO.Workup own = shown.getWorkups().get(0);
        assertThat(own.isOtherHospital()).isFalse();
        assertThat(own.getReferralCentre()).isEqualTo("CRTS Bobo");
        assertThat(own.getNotes()).isEqualTo("note d'ici");
        assertThat(own.getHospitalName()).isEqualTo("Hôpital B");
        AntibodyStatusResponseDTO.Workup fromA = shown.getWorkups().get(1);
        assertThat(fromA.isOtherHospital()).isTrue();
        assertThat(fromA.getStatus()).isEqualTo(AntibodyWorkupStatus.IDENTIFIED);
        assertThat(fromA.getAntibodySpecificities()).isEqualTo("anti-K");
        assertThat(fromA.getRecordedAt()).isEqualTo(NOW.minusDays(199));
        assertThat(fromA.getHospitalName()).isEqualTo("CHU A");
        assertThat(fromA.getId()).isNull();
        assertThat(fromA.getNotes()).isNull();
        assertThat(fromA.getRecordedByDisplay()).isNull();
        assertThat(fromA.getReferralCentre()).isNull();
        assertThat(fromA.getDecisionReason()).isNull();
        assertThat(fromA.getTransfusionRequestId()).isNull();
        // Nothing of A's text travels in any field of what is sent.
        assertThat(shown.toString()).doesNotContain("Titre").doesNotContain("Kaboré").doesNotContain("CNTS")
            .doesNotContain("Hb 5");
    }

    @Test
    @DisplayName("information all on record at this hospital names no other hospital and is not accounted as a cross-hospital read; an account that fails never fails the read")
    void aFlagHeldHereIsNoCrossHospitalRead() {
        com.example.hms.service.recordaccess.CrossHospitalReachRecorder reach =
            mock(com.example.hms.service.recordaccess.CrossHospitalReachRecorder.class);
        service.setReachRecorder(reach);
        screen(AntibodyScreenResult.POSITIVE, NOW.minusDays(2));

        AntibodyStatusResponseDTO shown = service.status(patient.getId(), hospital.getId());

        assertThat(shown.isAlloImmunised()).isTrue();
        assertThat(shown.getOtherHospitalNames()).isEmpty();
        ArgumentCaptor<java.util.Map<String, Long>> perSource = ArgumentCaptor.captor();
        verify(reach).recordReach(any(), any(), any(), any(), perSource.capture(), any());
        assertThat(perSource.getValue()).as("nothing surfaced from another hospital").isEmpty();

        org.mockito.Mockito.doThrow(new IllegalStateException("audit down")).when(reach)
            .recordReach(any(), any(), any(), any(), any(), any());
        assertThat(service.alloImmunisedShownAt(patient.getId(), hospital.getId())).isTrue();
        assertThat(service.status(patient.getId(), hospital.getId()).isAlloImmunised()).isTrue();
    }
}

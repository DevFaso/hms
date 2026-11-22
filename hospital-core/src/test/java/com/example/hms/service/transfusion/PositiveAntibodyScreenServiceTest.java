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
        when(groups.findByPatient_IdAndHospital_IdOrderByPerformedAtDesc(patient.getId(), hospital.getId()))
            .thenReturn(history);
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
        when(workups.findFirstByPatient_IdAndHospital_IdAndStatusOrderByRecordedAtDesc(patient.getId(), hospital.getId(),
            AntibodyWorkupStatus.IDENTIFIED)).thenReturn(Optional.of(PatientAntibodyWorkup.builder()
                .status(AntibodyWorkupStatus.IDENTIFIED).antibodySpecificities("anti-K").build()));
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
        assertThat(service.alloImmunised(patient.getId(), hospital.getId())).isFalse();

        screen(AntibodyScreenResult.POSITIVE, NOW.minusMonths(8));
        assertThat(service.alloImmunised(patient.getId(), hospital.getId())).isTrue();

        // Two negative screens since: the antibody is no longer detected, and still known.
        screen(AntibodyScreenResult.NEGATIVE, NOW.minusMonths(2));
        screen(AntibodyScreenResult.NEGATIVE, NOW.minusDays(1));
        assertThat(service.alloImmunised(patient.getId(), hospital.getId())).isTrue();
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
        when(workups.existsByPatient_IdAndHospital_Id(patient.getId(), hospital.getId())).thenReturn(true);
        assertThat(service.alloImmunised(patient.getId(), hospital.getId())).isTrue();
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

        assertThatThrownBy(() -> service.requireEmergencyDeclaration(dto.build(), patient.getId(), hospital.getId(),
            TransfusionUrgency.EMERGENCY)).satisfies(e -> assertThat(field(e)).isEqualTo("vitalUrgencyLevel"));
        dto.vitalUrgencyLevel(VitalUrgencyLevel.UVI);
        assertThatThrownBy(() -> service.requireEmergencyDeclaration(dto.build(), patient.getId(), hospital.getId(),
            TransfusionUrgency.EMERGENCY)).satisfies(e -> assertThat(field(e)).isEqualTo("vitalUrgencyReason"));
        dto.vitalUrgencyReason("Hémorragie du post-partum, choc");
        assertThatThrownBy(() -> service.requireEmergencyDeclaration(dto.build(), patient.getId(), hospital.getId(),
            TransfusionUrgency.EMERGENCY)).satisfies(e -> assertThat(field(e)).isEqualTo("preTransfusionSampleDrawn"));
        dto.preTransfusionSampleDrawn(true);
        assertThatCode(() -> service.requireEmergencyDeclaration(dto.build(), patient.getId(), hospital.getId(),
            TransfusionUrgency.EMERGENCY)).doesNotThrowAnyException();

        // A routine or an urgent request, and plasma, declare nothing: they are held by the routine rule.
        TransfusionRequestRequestDTO bare = TransfusionRequestRequestDTO.builder().patientId(patient.getId())
            .productType(BloodProductType.PACKED_RED_CELLS).build();
        assertThatCode(() -> service.requireEmergencyDeclaration(bare, patient.getId(), hospital.getId(),
            TransfusionUrgency.URGENT)).doesNotThrowAnyException();
        assertThatCode(() -> service.requireEmergencyDeclaration(TransfusionRequestRequestDTO.builder()
            .patientId(patient.getId()).productType(BloodProductType.PLATELETS).build(), patient.getId(),
            hospital.getId(), TransfusionUrgency.EMERGENCY)).doesNotThrowAnyException();
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
        assertThatCode(() -> service.requireRoutineIssueAllowed(undeclared, new TransfusionCrossmatch()))
            .doesNotThrowAnyException();

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
    @DisplayName("a positive screen asks every biologist to identify or refer; an identified antibody asks nobody again")
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

        identified();
        service.askBiologistToIdentify(screen(AntibodyScreenResult.POSITIVE, NOW));
        verify(inBasket, times(2)).createItemInNewTransaction(any());
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

        assertThat(service.alloImmunised(null, hospital.getId())).isFalse();
        assertThat(service.alloImmunised(patient.getId(), null)).isFalse();
        assertThat(service.status(null, hospital.getId()).isAlloImmunised()).isFalse();
        assertThat(service.status(patient.getId(), null).getWorkups()).isEmpty();
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
        PatientAntibodyWorkup referred = PatientAntibodyWorkup.builder().status(AntibodyWorkupStatus.REFERRED)
            .referralCentre("CNTS").decisionReason("Hb 5").recordedAt(NOW.minusDays(39)).recordedByDisplay("Dr Kaboré").build();
        PatientAntibodyWorkup named = PatientAntibodyWorkup.builder().status(AntibodyWorkupStatus.IDENTIFIED)
            .antibodySpecificities("anti-K").recordedAt(NOW.minusDays(36)).recordedByDisplay("Dr Kaboré").build();
        when(workups.findByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patient.getId(), hospital.getId()))
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
}

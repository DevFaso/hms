package com.example.hms.service.transfusion;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.BloodUnitStatus;
import com.example.hms.enums.EmergencyReleaseType;
import com.example.hms.enums.IncompatibleAntibodyStatus;
import com.example.hms.enums.IncompatibleMonitoringCheck;
import com.example.hms.enums.IncompatibleMonitoringOutcome;
import com.example.hms.enums.IncompatibleReleaseBasis;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.ONegativeStatus;
import com.example.hms.enums.PatientInformedStatus;
import com.example.hms.enums.RhFactor;
import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.enums.TransfusionUrgency;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.BloodUnit;
import com.example.hms.model.EmergencyBloodRelease;
import com.example.hms.model.Hospital;
import com.example.hms.model.IncompatibleReleaseDecision;
import com.example.hms.model.OnCallSchedule;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientBloodGroup;
import com.example.hms.model.Staff;
import com.example.hms.model.TransfusionCrossmatch;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseRequestDTO;
import com.example.hms.payload.dto.transfusion.IncompatibleMonitoringRequestDTO;
import com.example.hms.payload.dto.transfusion.IncompatibleReleaseDecisionDTO;
import com.example.hms.payload.dto.transfusion.IncompatibleReleaseProtocolDTO;
import com.example.hms.repository.PatientBloodGroupRepository;
import com.example.hms.repository.TransfusionAdministrationRepository;
import com.example.hms.repository.TransfusionCrossmatchRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.transfusion.OPositiveReleaseAuthorityService.Authorisation;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Owner decision 2026-11-26, option B (V381): each row of the behaviour table of a release despite an incompatible
 * crossmatch, at the one decision service every release path asks; and the two rules above them all, that an
 * ABO-excluded bag never leaves and that, where the hospital enabled the fallback, blood is never held back for
 * want of a physician.
 */
@ExtendWith({MockitoExtension.class, com.example.hms.i18n.EnglishMessageBundles.class})
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("release despite an incompatible crossmatch: named physician, the hospital's fallback, the ABO block")
class IncompatibleReleaseDecisionsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 26, 2, 0);

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private PatientBloodGroupRepository bloodGroupRepository;
    @Mock private TransfusionCrossmatchRepository crossmatchRepository;
    @Mock private TransfusionAdministrationRepository administrationRepository;

    private final Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private OPositiveAuthorityFixture fixture;
    private OPositiveReleaseAuthorityService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID issuer = UUID.randomUUID();
    private final UUID doctor = UUID.randomUUID();
    private final UUID onCallDoctor = UUID.randomUUID();
    private final UUID midwife = UUID.randomUUID();
    private final UUID director = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();
    private final Map<UUID, String> roles = new HashMap<>();
    private final Map<UUID, List<TransfusionCrossmatch>> results = new HashMap<>();
    private Hospital hospital;
    private Patient patient;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Clinique Test");
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        roles.put(issuer, SecurityConstants.ROLE_LAB_SCIENTIST);
        roles.put(doctor, SecurityConstants.ROLE_DOCTOR);
        roles.put(onCallDoctor, SecurityConstants.ROLE_DOCTOR);
        roles.put(requester, SecurityConstants.ROLE_DOCTOR);
        roles.put(midwife, SecurityConstants.ROLE_MIDWIFE);
        roles.put(director, SecurityConstants.ROLE_LAB_DIRECTOR);
        when(roleValidator.getCurrentUserId()).thenReturn(issuer);
        when(assignmentRepository.existsActiveWitness(any(), eq(hospitalId), anySet())).thenAnswer(call -> {
            Set<String> asked = call.getArgument(2);
            String held = roles.get(call.<UUID>getArgument(0));
            return held != null && asked.contains(held);
        });
        when(assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(eq(hospitalId), anySet())).thenAnswer(call -> {
            Set<String> asked = call.getArgument(1);
            return roles.entrySet().stream().filter(entry -> asked.contains(entry.getValue()))
                .map(entry -> user(entry.getKey())).toList();
        });
        when(userRepository.findById(any())).thenAnswer(call -> Optional.of(user(call.getArgument(0))));
        when(userRepository.findAllById(any())).thenAnswer(call -> {
            Iterable<UUID> ids = call.getArgument(0);
            List<User> users = new ArrayList<>();
            ids.forEach(id -> users.add(user(id)));
            return users;
        });
        when(crossmatchRepository.findByRequest_IdOrderByPerformedAtDesc(any()))
            .thenAnswer(call -> results.getOrDefault(call.<UUID>getArgument(0), List.of()));
        fixture = new OPositiveAuthorityFixture(assignmentRepository, userRepository, roleValidator, clock,
            bloodGroupRepository, crossmatchRepository, administrationRepository);
        service = fixture.service;
    }

    private static User user(UUID id) {
        User user = new User();
        user.setId(id);
        user.setUsername("u-" + id);
        return user;
    }

    private void typed(AboGroup abo, RhFactor rh) {
        typed(abo, rh, AntibodyScreenResult.NEGATIVE);
    }

    private void typed(AboGroup abo, RhFactor rh, AntibodyScreenResult screen) {
        PatientBloodGroup group = PatientBloodGroup.builder().patient(patient).hospital(hospital).aboGroup(abo).rhFactor(rh)
            .antibodyScreen(screen).antibodyDetail(screen == AntibodyScreenResult.POSITIVE ? "anti-K" : null)
            .performedAt(NOW.minusHours(1)).expiresAt(NOW.plusHours(48)).superseded(Boolean.FALSE).build();
        group.setId(UUID.randomUUID());
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patient.getId(), hospitalId))
            .thenReturn(Optional.of(group));
    }

    private TransfusionRequest request() {
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(user(requester));
        TransfusionRequest request = TransfusionRequest.builder().patient(patient).hospital(hospital)
            .productType(BloodProductType.PACKED_RED_CELLS).unitsRequested(1).indication("Haemorrhage")
            .urgency(TransfusionUrgency.EMERGENCY).status(TransfusionRequestStatus.REQUESTED)
            .requestedBy(staff).requestedAt(NOW).build();
        request.setId(UUID.randomUUID());
        return request;
    }

    /** A bag with an incompatible result on the request, as the laboratory recorded it. */
    private BloodUnit incompatibleBag(TransfusionRequest request, String number, AboGroup abo, RhFactor rh, Boolean kell,
                                      Boolean antigenNegative) {
        BloodUnit unit = BloodUnit.builder().hospital(hospital).unitNumber(number).productType(BloodProductType.PACKED_RED_CELLS)
            .aboGroup(abo).rhFactor(rh).expiresOn(LocalDate.of(2026, 12, 20)).status(BloodUnitStatus.CROSSMATCHED)
            .request(request).build();
        unit.setId(UUID.randomUUID());
        TransfusionCrossmatch result = TransfusionCrossmatch.builder().request(request).bloodUnit(unit).hospital(hospital)
            .compatible(false).incompatibilityReason("Agglutination, antiglobulin phase").rhKellMatched(kell)
            .antigenNegative(antigenNegative).performedAt(NOW.minusMinutes(10)).build();
        result.setId(UUID.randomUUID());
        when(crossmatchRepository.findByRequest_IdAndBloodUnit_Id(request.getId(), unit.getId())).thenReturn(Optional.of(result));
        results.computeIfAbsent(request.getId(), id -> new ArrayList<>()).add(result);
        return unit;
    }

    private static EmergencyReleaseRequestDTO.EmergencyReleaseRequestDTOBuilder base() {
        return EmergencyReleaseRequestDTO.builder().releaseType(EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE)
            .reason("Massive haemorrhage, no compatible unit")
            .unitChoiceReason("The only ABO-compatible unit crossmatched")
            .patientInformation(PatientInformedStatus.NOT_POSSIBLE);
    }

    private static EmergencyReleaseRequestDTO naming(UUID physician) {
        return base().authoriserUserId(physician).build();
    }

    private static EmergencyReleaseRequestDTO nobodyReachable() {
        return base().physicianUnreachable(Boolean.TRUE)
            .unreachableReason("Dr on call phoned at 01:40 and 01:50, no answer").build();
    }

    /** The whole of a release as the transfusion service runs it: authorise, then record beside the release row. */
    private IncompatibleReleaseDecision release(TransfusionRequest request, BloodUnit unit, EmergencyReleaseRequestDTO input) {
        // The O positive rule (V380) is asked of the same bag when it is O positive: its assessment is made for that bag only.
        OPositiveReleaseAuthorityService.Assessment assessment =
            AboGroup.isEmergencyOPositive(unit.getAboGroup(), unit.getRhFactor(), unit.getProductType())
                ? service.assess(request, RhFactor.NEGATIVE, false) : null;
        Authorisation authorisation = service.authorise(request, unit, EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE, input, assessment);
        assertThat(authorisation.incompatibleAuthorised()).isTrue();
        EmergencyBloodRelease row = EmergencyBloodRelease.builder().request(request).bloodUnit(unit).hospital(hospital)
            .releaseType(EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE).issuedAt(NOW).build();
        row.setId(UUID.randomUUID());
        unit.setStatus(BloodUnitStatus.ISSUED);
        unit.setRequest(request);
        service.record(row, authorisation, request, unit, ONegativeStatus.OUT_OF_STOCK);
        return fixture.lastIncompatible();
    }

    private void refused(TransfusionRequest request, BloodUnit unit, EmergencyReleaseRequestDTO input, String field, String message) {
        assertThatThrownBy(() -> service.authorise(request, unit, EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE, input, null))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal -> assertThat(refusal.getField()).isEqualTo(field))
            .hasMessageContaining(message);
        assertThat(fixture.incompatibleDecisions).as("a refusal writes nothing").isEmpty();
    }

    private List<CreateInBasketItemRequest> items(String referenceType) {
        ArgumentCaptor<CreateInBasketItemRequest> captor = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(fixture.inBasket, atLeastOnce()).createItem(captor.capture());
        return captor.getAllValues().stream().filter(item -> referenceType.equals(item.getReferenceType())).toList();
    }

    // ── The normal path ─────────────────────────────────────────────────

    @Test
    @DisplayName("normal path, unchanged: a named physician who is not the issuer; nothing owed; the record of the release is complete")
    void namedPhysician() {
        typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.POSITIVE);
        TransfusionRequest request = request();
        BloodUnit unit = incompatibleBag(request, "A-1", AboGroup.A, RhFactor.POSITIVE, true, null);

        IncompatibleReleaseDecision decision = release(request, unit, naming(doctor));

        assertThat(decision.getDecisionBasis()).isEqualTo(IncompatibleReleaseBasis.NAMED_PHYSICIAN);
        assertThat(decision.getAuthoriserUserId()).isEqualTo(doctor);
        assertThat(decision.getIssuedByUserId()).isEqualTo(issuer);
        assertThat(decision.getCountersignDueAt()).as("a physician decided before the issue: nothing to countersign").isNull();
        assertThat(decision.getDeclarerRole()).isNull();
        assertThat(decision.getAntibodyStatus()).isEqualTo(IncompatibleAntibodyStatus.ANTIBODY_ON_RECORD);
        assertThat(decision.getAntibodyDetail()).isEqualTo("anti-K");
        assertThat(decision.getCrossmatchFinding()).isEqualTo("Agglutination, antiglobulin phase");
        assertThat(decision.getRecipientAbo()).isEqualTo("A");
        assertThat(decision.getUnitAbo()).isEqualTo("A");
        assertThat(decision.isAboCompatible()).isTrue();
        assertThat(decision.isRhdMatched()).isTrue();
        assertThat(decision.getRhKellMatched()).isTrue();
        assertThat(decision.isLeastIncompatible()).isTrue();
        assertThat(decision.getUnitChoiceReason()).isEqualTo("The only ABO-compatible unit crossmatched");
        assertThat(decision.getPatientInformed()).isEqualTo(PatientInformedStatus.NOT_POSSIBLE);
        assertThat(items(IncompatibleReleaseNotices.COUNTERSIGN_REFERENCE_TYPE)).isEmpty();
        // Every release, normal or fallback: the monitoring task and the haemovigilance prompt.
        assertThat(items(IncompatibleReleaseNotices.MONITORING_REFERENCE_TYPE))
            .extracting(CreateInBasketItemRequest::getRecipientUserId).containsExactlyInAnyOrder(requester, doctor);
        assertThat(items(IncompatibleReleaseNotices.HAEMOVIGILANCE_REFERENCE_TYPE))
            .extracting(CreateInBasketItemRequest::getRecipientUserId).containsExactlyInAnyOrder(requester, doctor, director);
        assertThat(decision.getNoticesFiledAt()).isNotNull();

        IncompatibleReleaseDecisionDTO shown = service.listIncompatible(hospitalId, true).get(0);
        assertThat(shown.getAuthorityTiming()).isEqualTo("BEFORE_ISSUE");
        assertThat(shown.isFallback()).isFalse();
        assertThat(shown.getMonitoringChecklist()).containsExactly(IncompatibleMonitoringCheck.values());
    }

    @Test
    @DisplayName("normal path: the issuer cannot name themselves, nor somebody who is neither a physician nor the laboratory director")
    void theAuthoriserIsAnswerable() {
        typed(AboGroup.A, RhFactor.POSITIVE);
        TransfusionRequest request = request();
        BloodUnit unit = incompatibleBag(request, "A-1", AboGroup.A, RhFactor.POSITIVE, null, null);
        refused(request, unit, naming(issuer), "authoriserUserId", "other than the person issuing");
        refused(request, unit, naming(midwife), "authoriserUserId", "");
        assertThat(release(request, unit, naming(director)).getAuthoriserUserId()).isEqualTo(director);
    }

    @Test
    @DisplayName("every release says why this bag and whether the patient was told")
    void theRecordIsAsked() {
        typed(AboGroup.A, RhFactor.POSITIVE);
        TransfusionRequest request = request();
        BloodUnit unit = incompatibleBag(request, "A-1", AboGroup.A, RhFactor.POSITIVE, null, null);
        EmergencyReleaseRequestDTO noChoice = naming(doctor);
        noChoice.setUnitChoiceReason("  ");
        refused(request, unit, noChoice, "unitChoiceReason", "why this unit was chosen");
        EmergencyReleaseRequestDTO notSaid = naming(doctor);
        notSaid.setPatientInformation(null);
        refused(request, unit, notSaid, "patientInformation", "told of the risk");
    }

    // ── The fallback ────────────────────────────────────────────────────

    @Test
    @DisplayName("fallback OFF (the default): « no physician reachable » releases nothing; a named physician stays required, as today")
    void fallbackOffIsToday() {
        typed(AboGroup.A, RhFactor.POSITIVE);
        TransfusionRequest request = request();
        BloodUnit unit = incompatibleBag(request, "A-1", AboGroup.A, RhFactor.POSITIVE, null, null);

        assertThat(service.incompatibleProtocol(hospitalId).fallbackEnabled()).as("off until the laboratory director switches it on").isFalse();
        refused(request, unit, nobodyReachable(), "physicianUnreachable", "has not enabled");
        refused(request, unit, base().build(), "authoriserUserId", "Name the person");
        // Switched on, then off again: off is off.
        fixture.incompatibleFallback(true).incompatibleFallback(false);
        refused(request, unit, nobodyReachable(), "physicianUnreachable", "has not enabled");
        assertThat(release(request, unit, naming(doctor)).getDecisionBasis()).isEqualTo(IncompatibleReleaseBasis.NAMED_PHYSICIAN);
    }

    @Test
    @DisplayName("fallback ON: the issuer declares nobody reachable with a reason and the bag goes out AT ONCE, authority pending, "
        + "countersign due within the hospital's deadline, a CRITICAL alert to the director and the physicians")
    void fallbackReleasesAtOnce() {
        fixture.protocol(false, 50, false, 6).incompatibleFallback(true);
        typed(AboGroup.A, RhFactor.POSITIVE);
        TransfusionRequest request = request();
        BloodUnit unit = incompatibleBag(request, "A-1", AboGroup.A, RhFactor.POSITIVE, null, null);
        OnCallSchedule rota = new OnCallSchedule();
        Staff onCall = new Staff();
        onCall.setUser(user(onCallDoctor));
        rota.setStaff(onCall);
        when(fixture.onCallRepository.findByHospitalAndWindow(eq(hospitalId), any(), any())).thenReturn(List.of(rota));

        Authorisation authorisation = service.authorise(request, unit, EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE,
            nobodyReachable(), null);
        assertThat(authorisation.incompatibleAuthoriser()).as("the issuer is never recorded as the authoriser").isNull();
        IncompatibleReleaseDecision decision = release(request, unit, nobodyReachable());

        assertThat(decision.getDecisionBasis()).isEqualTo(IncompatibleReleaseBasis.AUTHORITY_PENDING);
        assertThat(decision.getAuthoriserUserId()).isNull();
        assertThat(decision.getIssuedByUserId()).isEqualTo(issuer);
        assertThat(decision.getDeclarerRole()).isEqualTo(SecurityConstants.ROLE_LAB_SCIENTIST);
        assertThat(decision.getUnreachableReason()).isEqualTo("Dr on call phoned at 01:40 and 01:50, no answer");
        assertThat(decision.isFallbackEnabled()).isTrue();
        assertThat(decision.isLeastIncompatible()).isTrue();
        assertThat(decision.getCountersignDueAt()).as("the deadline of the hospital").isEqualTo(NOW.plusHours(6));
        assertThat(decision.getAntibodyStatus()).isEqualTo(IncompatibleAntibodyStatus.NO_ANTIBODY_ON_RECORD);

        List<CreateInBasketItemRequest> alerts = items(IncompatibleReleaseNotices.COUNTERSIGN_REFERENCE_TYPE);
        assertThat(alerts).extracting(CreateInBasketItemRequest::getRecipientUserId)
            .as("the laboratory director, the requesting physician and the physician on call; never the issuer")
            .containsExactlyInAnyOrder(director, requester, onCallDoctor);
        assertThat(alerts).allSatisfy(alert -> {
            assertThat(alert.getPriority()).isEqualTo(InBasketPriority.CRITICAL);
            // The stored copy is the staff-language one (French); the reader's own is rendered from the keys.
            assertThat(alert.getBody()).contains("A-1").contains("aucun médecin n’était joignable").contains("sous 6 h");
        });
        assertThat(items(IncompatibleReleaseNotices.MONITORING_REFERENCE_TYPE)).singleElement().satisfies(task -> {
            assertThat(task.getRecipientUserId()).isEqualTo(requester);
            assertThat(task.getBody()).contains("20 ml").contains("50 ml").contains("contrôle ABO au lit").contains("tubulure")
                .contains("haptoglobine").doesNotContain("{");
        });
        assertThat(items(IncompatibleReleaseNotices.HAEMOVIGILANCE_REFERENCE_TYPE)).allSatisfy(prompt ->
            assertThat(prompt.getBody()).as("no antibody on record: the laboratory is asked to record the work-up")
                .contains("bilan d’anticorps"));

        IncompatibleReleaseDecisionDTO shown = service.listIncompatible(hospitalId, true).get(0);
        assertThat(shown.isFallback()).isTrue();
        assertThat(shown.getAuthorityTiming()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("fallback ON, a physician reachable after all: naming one is the normal path, whatever the box says")
    void aNameWins() {
        fixture.incompatibleFallback(true);
        typed(AboGroup.A, RhFactor.POSITIVE);
        TransfusionRequest request = request();
        BloodUnit unit = incompatibleBag(request, "A-1", AboGroup.A, RhFactor.POSITIVE, null, null);
        EmergencyReleaseRequestDTO both = nobodyReachable();
        both.setAuthoriserUserId(doctor);
        IncompatibleReleaseDecision decision = release(request, unit, both);
        assertThat(decision.getDecisionBasis()).isEqualTo(IncompatibleReleaseBasis.NAMED_PHYSICIAN);
        assertThat(decision.getCountersignDueAt()).isNull();
    }

    @Test
    @DisplayName("fallback ON: the declaration has its reason, and is made under a role the hospital allows (laboratory staff by default)")
    void whoMayDeclare() {
        fixture.incompatibleFallback(true);
        typed(AboGroup.A, RhFactor.POSITIVE);
        TransfusionRequest request = request();
        BloodUnit unit = incompatibleBag(request, "A-1", AboGroup.A, RhFactor.POSITIVE, null, null);
        EmergencyReleaseRequestDTO noReason = nobodyReachable();
        noReason.setUnreachableReason(" ");
        refused(request, unit, noReason, "unreachableReason", "who was called");

        // The hospital narrowed the roles to its laboratory manager: a scientist cannot declare, and is told what to do.
        fixture.incompatibleFallback(true, SecurityConstants.ROLE_LAB_MANAGER);
        refused(request, unit, nobodyReachable(), "physicianUnreachable", "Your role is not among those");
        assertThat(service.incompatibleRule(hospitalId).isCallerMayDeclare()).isFalse();
        roles.put(issuer, SecurityConstants.ROLE_LAB_MANAGER);
        assertThat(service.incompatibleRule(hospitalId).isCallerMayDeclare()).isTrue();
        assertThat(release(request, unit, nobodyReachable()).getDeclarerRole()).isEqualTo(SecurityConstants.ROLE_LAB_MANAGER);
    }

    @Test
    @DisplayName("fallback ON: only the LEAST incompatible bag goes out with no physician; the refusal names the better bag, "
        + "and a physician may still choose otherwise")
    void onlyTheLeastIncompatible() {
        fixture.incompatibleFallback(true);
        typed(AboGroup.A, RhFactor.NEGATIVE);
        TransfusionRequest request = request();
        BloodUnit oPositive = incompatibleBag(request, "O-POS", AboGroup.O, RhFactor.POSITIVE, null, null);
        BloodUnit unmatched = incompatibleBag(request, "A-NEG-PLAIN", AboGroup.A, RhFactor.NEGATIVE, null, null);
        BloodUnit matched = incompatibleBag(request, "A-NEG-KELL", AboGroup.A, RhFactor.NEGATIVE, true, true);

        refused(request, unmatched, nobodyReachable(), "bloodUnitId", "A-NEG-KELL");
        // A physician weighs it: the normal path records that the bag was not the least incompatible, and lets it go.
        IncompatibleReleaseDecision chosenByPhysician = release(request, unmatched, naming(doctor));
        assertThat(chosenByPhysician.isLeastIncompatible()).isFalse();
        assertThat(chosenByPhysician.getCandidatesConsidered()).isEqualTo(3);

        IncompatibleReleaseDecision decision = release(request, matched, nobodyReachable());
        assertThat(decision.getDecisionBasis()).isEqualTo(IncompatibleReleaseBasis.AUTHORITY_PENDING);
        assertThat(decision.isRhdMatched()).isTrue();
        assertThat(decision.getRhKellMatched()).isTrue();
        assertThat(decision.getAntigenNegative()).isTrue();
        assertThat(oPositive.getStatus()).isEqualTo(BloodUnitStatus.CROSSMATCHED);
    }

    @Test
    @DisplayName("NEVER BLOCKS: the only bag there is goes out on the fallback even when nothing about it is matched, "
        + "and a bag that cannot go out never stands in its way")
    void theOnlyBagGoesOut() {
        fixture.incompatibleFallback(true);
        typed(AboGroup.A, RhFactor.NEGATIVE);
        TransfusionRequest request = request();
        BloodUnit only = incompatibleBag(request, "O-POS", AboGroup.O, RhFactor.POSITIVE, null, null);
        // Better on paper, none of them releasable: expired, held for another request, already hung, group B.
        BloodUnit expired = incompatibleBag(request, "EXPIRED", AboGroup.A, RhFactor.NEGATIVE, true, true);
        expired.setExpiresOn(NOW.toLocalDate().minusDays(1));
        BloodUnit held = incompatibleBag(request, "HELD", AboGroup.A, RhFactor.NEGATIVE, true, true);
        TransfusionRequest other = request();
        held.setRequest(other);
        BloodUnit hung = incompatibleBag(request, "HUNG", AboGroup.A, RhFactor.NEGATIVE, true, true);
        when(administrationRepository.findByBloodUnit_Id(hung.getId()))
            .thenReturn(Optional.of(new com.example.hms.model.TransfusionAdministration()));
        incompatibleBag(request, "GROUP-B", AboGroup.B, RhFactor.NEGATIVE, true, true);

        IncompatibleReleaseDecision decision = release(request, only, nobodyReachable());

        assertThat(decision.getDecisionBasis()).isEqualTo(IncompatibleReleaseBasis.AUTHORITY_PENDING);
        assertThat(decision.isLeastIncompatible()).isTrue();
        assertThat(decision.isRhdMatched()).as("recorded as it is: RhD positive to a patient typed negative").isFalse();
        assertThat(decision.getCandidatesConsidered()).isEqualTo(1);
        // The same bag is O positive for a patient at risk: the O positive rule keeps its own decision, owed too.
        assertThat(fixture.last().getDecisionBasis()).isEqualTo(com.example.hms.enums.OPositiveDecisionBasis.AUTHORITY_PENDING);
        assertThat(fixture.last().getDecidedByUserId()).isNull();
    }

    @Test
    @DisplayName("NEVER BLOCKS: nothing about the readers of the alerts can fail the release; with nobody to alert the row stays owed for the sweep")
    void aNoticeNeverHoldsARelease() {
        fixture.incompatibleFallback(true);
        typed(AboGroup.A, RhFactor.POSITIVE);
        TransfusionRequest request = request();
        BloodUnit unit = incompatibleBag(request, "A-1", AboGroup.A, RhFactor.POSITIVE, null, null);
        when(fixture.inBasket.createItem(any())).thenThrow(new IllegalStateException("in-basket down"));

        IncompatibleReleaseDecision decision = release(request, unit, nobodyReachable());

        assertThat(decision.getDecisionBasis()).isEqualTo(IncompatibleReleaseBasis.AUTHORITY_PENDING);
        assertThat(decision.getNoticesFiledAt()).as("owed: the sweep delivers it").isNull();
    }

    // ── The never event ─────────────────────────────────────────────────

    @Test
    @DisplayName("NEVER EVENT: an ABO-excluded bag is refused on EVERY path (named physician, fallback on, fallback off, "
        + "laboratory director as issuer), before any name, role or setting is read, and no row is written")
    void aboIncompatibleIsAHardBlockOnEveryPath() {
        int refusedPairs = 0;
        for (boolean fallbackOn : List.of(false, true)) {
            fixture.incompatibleFallback(fallbackOn);
            for (AboGroup recipient : new AboGroup[] {AboGroup.O, AboGroup.A, AboGroup.B, AboGroup.AB, null}) {
                if (recipient == null) {
                    when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patient.getId(), hospitalId))
                        .thenReturn(Optional.empty());
                } else {
                    typed(recipient, RhFactor.POSITIVE);
                }
                for (AboGroup donor : AboGroup.values()) {
                    boolean compatible = donor == AboGroup.O
                        || (recipient != null && recipient.compatibleRedCellDonors().contains(donor));
                    if (compatible) {
                        continue;
                    }
                    TransfusionRequest request = request();
                    BloodUnit unit = incompatibleBag(request, donor + "-to-" + recipient, donor, RhFactor.POSITIVE, true, true);
                    for (UUID caller : List.of(issuer, director)) {
                        when(roleValidator.getCurrentUserId()).thenReturn(caller);
                        for (EmergencyReleaseRequestDTO input : List.of(naming(doctor), naming(director.equals(caller) ? doctor : director),
                                nobodyReachable())) {
                            assertThatThrownBy(() -> service.authorise(request, unit,
                                    EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE, input, null))
                                .as("%s red cells to a group %s patient, fallback %s", donor, recipient, fallbackOn)
                                .isInstanceOfSatisfying(FieldBusinessException.class,
                                    refusal -> assertThat(refusal.getField()).isEqualTo("bloodUnitId"))
                                .hasMessageContaining("blood group excludes it");
                            refusedPairs++;
                        }
                    }
                    // The same block where the bag is put out, whatever path led there.
                    assertThatThrownBy(() -> service.requireAboCompatible(recipient, unit))
                        .isInstanceOf(FieldBusinessException.class).hasMessageContaining("blood group excludes it");
                }
            }
        }
        assertThat(refusedPairs).as("7 excluded pairings typed + 3 untyped, 2 settings, 2 callers, 3 ways of asking").isEqualTo(120);
        assertThat(fixture.incompatibleDecisions).isEmpty();
        verify(assignmentRepository, never()).existsActiveWitness(any(), any(), anySet());
        verify(fixture.protocolRepository, never()).save(any());
    }

    @Test
    @DisplayName("the ABO block judges red cells only, and lets every compatible pairing through")
    void theBlockIsOnlyTheAboBlock() {
        for (AboGroup recipient : AboGroup.values()) {
            for (AboGroup donor : recipient.compatibleRedCellDonors()) {
                BloodUnit unit = BloodUnit.builder().unitNumber("OK").productType(BloodProductType.PACKED_RED_CELLS)
                    .aboGroup(donor).rhFactor(RhFactor.NEGATIVE).build();
                service.requireAboCompatible(recipient, unit);
            }
        }
        BloodUnit plasma = BloodUnit.builder().unitNumber("FFP").productType(BloodProductType.FRESH_FROZEN_PLASMA)
            .aboGroup(AboGroup.A).rhFactor(RhFactor.POSITIVE).build();
        service.requireAboCompatible(AboGroup.O, plasma);
    }

    // ── Countersignature ────────────────────────────────────────────────

    @Test
    @DisplayName("countersignature: a physician or the laboratory director who did not issue the bag, once; the tasks leave the in-baskets")
    void countersign() {
        fixture.incompatibleFallback(true);
        typed(AboGroup.A, RhFactor.POSITIVE);
        TransfusionRequest request = request();
        IncompatibleReleaseDecision decision = release(request,
            incompatibleBag(request, "A-1", AboGroup.A, RhFactor.POSITIVE, null, null), nobodyReachable());
        UUID id = decision.getId();

        assertThatThrownBy(() -> service.countersignIncompatible(id, hospitalId)).isInstanceOf(ClientSafeAccessDeniedException.class);
        when(roleValidator.getCurrentUserId()).thenReturn(midwife);
        assertThatThrownBy(() -> service.countersignIncompatible(id, hospitalId)).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> service.countersignIncompatible(id, UUID.randomUUID()))
            .as("another hospital does not find the row").isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        assertThat(decision.getCountersignedAt()).isNull();

        when(roleValidator.getCurrentUserId()).thenReturn(doctor);
        IncompatibleReleaseDecisionDTO signed = service.countersignIncompatible(id, hospitalId);
        assertThat(signed.getAuthorityTiming()).isEqualTo("AFTER_ISSUE");
        assertThat(decision.getCountersignedByUserId()).isEqualTo(doctor);
        assertThat(decision.getCountersignedAt()).isEqualTo(NOW);
        verify(fixture.inBasket).removeOpenItemsForReference(id, IncompatibleReleaseNotices.COUNTERSIGN_REFERENCE_TYPE);
        verify(fixture.inBasket).removeOpenItemsForReference(id, IncompatibleReleaseNotices.OVERDUE_REFERENCE_TYPE);
        assertThatThrownBy(() -> service.countersignIncompatible(id, hospitalId)).isInstanceOf(FieldBusinessException.class);
    }

    @Test
    @DisplayName("a countersignature past the hospital's deadline is escalated to the laboratory director, once")
    void overdueCountersignIsEscalated() {
        fixture.incompatibleFallback(true);
        typed(AboGroup.A, RhFactor.POSITIVE);
        TransfusionRequest request = request();
        IncompatibleReleaseDecision decision = release(request,
            incompatibleBag(request, "A-1", AboGroup.A, RhFactor.POSITIVE, null, null), nobodyReachable());

        assertThat(fixture.incompatibleNotices.escalateOverdueCountersign(decision.getId())).isEqualTo(1);
        assertThat(decision.getCountersignEscalatedAt()).isEqualTo(NOW);
        assertThat(items(IncompatibleReleaseNotices.OVERDUE_REFERENCE_TYPE)).singleElement().satisfies(alert -> {
            assertThat(alert.getRecipientUserId()).isEqualTo(director);
            assertThat(alert.getPriority()).isEqualTo(InBasketPriority.CRITICAL);
        });
        assertThat(fixture.incompatibleNotices.escalateOverdueCountersign(decision.getId())).as("once").isZero();
    }

    // ── Monitoring ──────────────────────────────────────────────────────

    @Test
    @DisplayName("monitoring: checks add up, the outcome closes it once the bag was hung, the patient told afterwards is recorded")
    void monitoring() {
        typed(AboGroup.A, RhFactor.POSITIVE);
        TransfusionRequest request = request();
        BloodUnit unit = incompatibleBag(request, "A-1", AboGroup.A, RhFactor.POSITIVE, null, null);
        IncompatibleReleaseDecision decision = release(request, unit, naming(doctor));
        UUID id = decision.getId();

        assertThatThrownBy(() -> service.recordIncompatibleMonitoring(id, hospitalId, IncompatibleMonitoringRequestDTO.builder()
            .checksDone(List.of(IncompatibleMonitoringCheck.BEDSIDE_ABO_CHECK)).build()))
            .as("the laboratory issuer is not at the bedside").isInstanceOf(ClientSafeAccessDeniedException.class);
        when(roleValidator.getCurrentUserId()).thenReturn(midwife);
        service.recordIncompatibleMonitoring(id, hospitalId, IncompatibleMonitoringRequestDTO.builder()
            .checksDone(List.of(IncompatibleMonitoringCheck.BEDSIDE_ABO_CHECK, IncompatibleMonitoringCheck.SLOW_START)).build());
        IncompatibleReleaseDecisionDTO shown = service.recordIncompatibleMonitoring(id, hospitalId,
            IncompatibleMonitoringRequestDTO.builder().checksDone(List.of(IncompatibleMonitoringCheck.TUBING_KEPT,
                IncompatibleMonitoringCheck.BEDSIDE_ABO_CHECK)).patientInformed(Boolean.TRUE).build());
        assertThat(shown.getMonitoringChecksDone()).containsExactly(IncompatibleMonitoringCheck.BEDSIDE_ABO_CHECK,
            IncompatibleMonitoringCheck.SLOW_START, IncompatibleMonitoringCheck.TUBING_KEPT);
        assertThat(decision.getPatientInformedAt()).isEqualTo(NOW);
        assertThat(decision.getPatientInformedByUserId()).isEqualTo(midwife);
        assertThat(decision.monitoringOwed()).isTrue();

        IncompatibleMonitoringRequestDTO noReaction = IncompatibleMonitoringRequestDTO.builder()
            .outcome(IncompatibleMonitoringOutcome.NO_REACTION).build();
        assertThatThrownBy(() -> service.recordIncompatibleMonitoring(id, hospitalId, noReaction))
            .as("nobody states the absence of a reaction to a transfusion that is not on record")
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("not on record");
        when(fixture.incompatibleRows.countAdministrations(unit.getId(), request.getId())).thenReturn(1L);
        assertThatThrownBy(() -> service.recordIncompatibleMonitoring(id, hospitalId, IncompatibleMonitoringRequestDTO.builder()
            .outcome(IncompatibleMonitoringOutcome.NOT_TRANSFUSED).build())).isInstanceOf(FieldBusinessException.class);
        service.recordIncompatibleMonitoring(id, hospitalId, noReaction);
        assertThat(decision.getMonitoringOutcome()).isEqualTo(IncompatibleMonitoringOutcome.NO_REACTION);
        assertThat(decision.getMonitoringRecordedByUserId()).isEqualTo(midwife);
        verify(fixture.inBasket).removeOpenItemsForReference(id, IncompatibleReleaseNotices.MONITORING_REFERENCE_TYPE);
        verify(fixture.inBasket).removeOpenItemsForReference(id, IncompatibleReleaseNotices.HAEMOVIGILANCE_REFERENCE_TYPE);
        assertThatThrownBy(() -> service.recordIncompatibleMonitoring(id, hospitalId, noReaction))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("already recorded");
        assertThat(service.listIncompatible(hospitalId, true)).as("nothing is owed any more").isEmpty();
    }

    @Test
    @DisplayName("a bag that comes back unused closes its monitoring by itself; the countersignature of a fallback stays owed")
    void theBagCameBack() {
        fixture.incompatibleFallback(true);
        typed(AboGroup.A, RhFactor.POSITIVE);
        TransfusionRequest request = request();
        BloodUnit unit = incompatibleBag(request, "A-1", AboGroup.A, RhFactor.POSITIVE, null, null);
        IncompatibleReleaseDecision decision = release(request, unit, nobodyReachable());
        when(fixture.incompatibleRows.findOpenForUnit(unit.getId())).thenReturn(List.of(decision));

        service.noteBagBack(unit.getId());

        assertThat(decision.getMonitoringOutcome()).isEqualTo(IncompatibleMonitoringOutcome.NOT_TRANSFUSED);
        assertThat(decision.countersignOwed()).as("the decision was still taken").isTrue();
        verify(fixture.inBasket).removeOpenItemsForReference(decision.getId(), IncompatibleReleaseNotices.MONITORING_REFERENCE_TYPE);
    }

    // ── The setting ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the setting: off by default, laboratory staff by default; only the laboratory director changes it; only laboratory roles")
    void theSettingIsTheDirectors() {
        IncompatibleReleaseProtocolDTO initial = service.incompatibleProtocol(hospitalId);
        assertThat(initial.fallbackEnabled()).isFalse();
        assertThat(initial.declarerRoles()).containsExactly(SecurityConstants.ROLE_LAB_SCIENTIST,
            SecurityConstants.ROLE_LAB_TECHNICIAN, SecurityConstants.ROLE_LAB_MANAGER, SecurityConstants.ROLE_LAB_DIRECTOR);
        IncompatibleReleaseProtocolDTO on = new IncompatibleReleaseProtocolDTO(null, Boolean.TRUE, null, null, null);

        assertThatThrownBy(() -> service.setIncompatibleProtocol(hospital, on)).as("the issuer is not the director")
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        when(roleValidator.getCurrentUserId()).thenReturn(doctor);
        assertThatThrownBy(() -> service.setIncompatibleProtocol(hospital, on)).isInstanceOf(ClientSafeAccessDeniedException.class);

        when(roleValidator.getCurrentUserId()).thenReturn(director);
        assertThatThrownBy(() -> service.setIncompatibleProtocol(hospital,
            new IncompatibleReleaseProtocolDTO(null, null, null, null, null))).isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> service.setIncompatibleProtocol(hospital, new IncompatibleReleaseProtocolDTO(null, Boolean.TRUE,
            List.of(SecurityConstants.ROLE_MIDWIFE), null, null)))
            .as("widening beyond the laboratory waits for the clinical sign-off").isInstanceOf(FieldBusinessException.class);
        verify(fixture.protocolRepository, never()).save(any());

        IncompatibleReleaseProtocolDTO saved = service.setIncompatibleProtocol(hospital, new IncompatibleReleaseProtocolDTO(null,
            Boolean.TRUE, List.of(SecurityConstants.ROLE_LAB_MANAGER, SecurityConstants.ROLE_LAB_SCIENTIST), null, null));
        assertThat(saved.fallbackEnabled()).isTrue();
        assertThat(saved.declarerRoles()).containsExactly(SecurityConstants.ROLE_LAB_SCIENTIST, SecurityConstants.ROLE_LAB_MANAGER);
        ArgumentCaptor<com.example.hms.model.OPositiveReleaseProtocol> row =
            ArgumentCaptor.forClass(com.example.hms.model.OPositiveReleaseProtocol.class);
        verify(fixture.protocolRepository).save(row.capture());
        assertThat(row.getValue().isIncompatibleFallbackEnabled()).isTrue();
        assertThat(row.getValue().isActive()).as("the O positive protocol of the hospital is not switched on by this").isFalse();
        assertThat(row.getValue().getIncompatibleFallbackDeclarerRoles()).isEqualTo("ROLE_LAB_SCIENTIST,ROLE_LAB_MANAGER");
    }
}

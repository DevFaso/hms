package com.example.hms.service.transfusion;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AntiDDecision;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.BloodUnitStatus;
import com.example.hms.enums.EmergencyReleaseType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.ONegativeStatus;
import com.example.hms.enums.OPositiveDecisionBasis;
import com.example.hms.enums.OPositiveReviewPriority;
import com.example.hms.enums.OPositiveRiskTier;
import com.example.hms.enums.OPositiveTierReason;
import com.example.hms.enums.RhFactor;
import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.enums.TransfusionUrgency;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.BloodUnit;
import com.example.hms.model.EmergencyBloodRelease;
import com.example.hms.model.Hospital;
import com.example.hms.model.OPositiveReleaseDecision;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseRequestDTO;
import com.example.hms.payload.dto.transfusion.OPositiveFollowUpRequestDTO;
import com.example.hms.payload.dto.transfusion.OPositiveProtocolDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.transfusion.OPositiveReleaseAuthorityService.Assessment;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Owner decision 2026-11-25 (V380): each row of the decision table at the one service that applies it, and the
 * rule above them all, that no row holds blood back for want of a person.
 */
@ExtendWith({MockitoExtension.class, com.example.hms.i18n.EnglishMessageBundles.class})
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("emergency O RhD positive release: who decides, what is kept, what follows")
class OPositiveReleaseAuthorityServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 22, 0);

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleValidator roleValidator;

    private Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private OPositiveAuthorityFixture fixture;
    private OPositiveReleaseAuthorityService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID issuer = UUID.randomUUID();
    private final UUID doctor = UUID.randomUUID();
    private final UUID otherDoctor = UUID.randomUUID();
    private final UUID midwife = UUID.randomUUID();
    private final UUID director = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();
    private final Map<UUID, String> roles = new HashMap<>();
    private Hospital hospital;
    private Patient patient;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Clinique Test");
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        woman(30);
        roles.put(issuer, SecurityConstants.ROLE_LAB_SCIENTIST);
        roles.put(doctor, SecurityConstants.ROLE_DOCTOR);
        roles.put(otherDoctor, SecurityConstants.ROLE_DOCTOR);
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
            List<User> users = new java.util.ArrayList<>();
            ids.forEach(id -> users.add(user(id)));
            return users;
        });
        fixture = new OPositiveAuthorityFixture(assignmentRepository, userRepository, roleValidator, clock).activeProtocol();
        service = fixture.service;
    }

    private static User user(UUID id) {
        User user = new User();
        user.setId(id);
        user.setUsername("u-" + id);
        return user;
    }

    private void woman(int age) {
        patient.setGender("FEMALE");
        patient.setDateOfBirth(NOW.toLocalDate().minusYears(age));
    }

    private void man(int age) {
        patient.setGender("MALE");
        patient.setDateOfBirth(NOW.toLocalDate().minusYears(age));
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

    private BloodUnit bag(RhFactor rh) {
        BloodUnit unit = BloodUnit.builder().hospital(hospital).unitNumber("U-O" + rh).productType(BloodProductType.PACKED_RED_CELLS)
            .aboGroup(AboGroup.O).rhFactor(rh).expiresOn(LocalDate.of(2026, 12, 20)).status(BloodUnitStatus.AVAILABLE).build();
        unit.setId(UUID.randomUUID());
        return unit;
    }

    private EmergencyReleaseRequestDTO naming(UUID physician) {
        return EmergencyReleaseRequestDTO.builder().releaseType(EmergencyReleaseType.O_POSITIVE_DECISION)
            .reason("O negative out of stock, vital emergency").authoriserUserId(physician).build();
    }

    private EmergencyReleaseRequestDTO nobodyReachable() {
        return EmergencyReleaseRequestDTO.builder().releaseType(EmergencyReleaseType.O_POSITIVE_DECISION)
            .reason("O negative out of stock, vital emergency").physicianUnreachable(Boolean.TRUE).build();
    }

    private Assessment assess(TransfusionRequest request, RhFactor typedRh, boolean antibody) {
        return service.assess(request, typedRh, antibody);
    }

    /** The whole of a release as the transfusion service runs it: authorise, then record beside the release row. */
    private OPositiveReleaseDecision release(TransfusionRequest request, EmergencyReleaseRequestDTO input, RhFactor typedRh,
                                             boolean antibody) {
        BloodUnit unit = bag(RhFactor.POSITIVE);
        EmergencyReleaseType type = input == null ? EmergencyReleaseType.UNCROSSMATCHED : input.getReleaseType();
        Authorisation authorisation = service.authorise(request, unit, type, input, assess(request, typedRh, antibody));
        EmergencyBloodRelease row = EmergencyBloodRelease.builder().request(request).bloodUnit(unit).hospital(hospital)
            .releaseType(type).issuedAt(NOW).build();
        row.setId(UUID.randomUUID());
        return service.record(row, authorisation, request, unit, ONegativeStatus.OUT_OF_STOCK);
    }

    private List<CreateInBasketItemRequest> items() {
        ArgumentCaptor<CreateInBasketItemRequest> captor = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(fixture.inBasket, atLeastOnce()).createItem(captor.capture());
        return captor.getAllValues();
    }

    private List<CreateInBasketItemRequest> items(String referenceType) {
        return items().stream().filter(item -> referenceType.equals(item.getReferenceType())).toList();
    }

    // ── Row 1 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("row 1: an adult man leaves on the plain issue; the issuer decides alone under the protocol; routine review")
    void lowRiskIssuerAlone() {
        man(40);
        OPositiveReleaseDecision decision = release(request(), null, null, false);

        assertThat(decision.getRiskTier()).isEqualTo(OPositiveRiskTier.LOW_RISK);
        assertThat(decision.getTierReason()).isEqualTo(OPositiveTierReason.ADULT_MALE);
        assertThat(decision.getDecisionBasis()).isEqualTo(OPositiveDecisionBasis.PROTOCOL);
        assertThat(decision.getDecidedByUserId()).isEqualTo(issuer);
        assertThat(decision.getIssuedByUserId()).isEqualTo(issuer);
        assertThat(decision.getReviewPriority()).isEqualTo(OPositiveReviewPriority.ROUTINE);
        assertThat(decision.getReviewDueAt()).isEqualTo(NOW.plusDays(7));
        assertThat(decision.getCountersignDueAt()).isNull();
        assertThat(decision.isRhdExposure()).isFalse();
        assertThat(decision.isFollowUpRequired()).isFalse();
        assertThat(decision.getPatientSex()).isEqualTo("MALE");
        assertThat(decision.getPatientAgeYears()).isEqualTo(40);
        assertThat(decision.getNegativeStatus()).isEqualTo(ONegativeStatus.OUT_OF_STOCK);
        assertThat(decision.isProtocolActive()).isTrue();

        // Every release is reviewed (the QA finding): a routine entry for the biologist, nothing else.
        assertThat(items()).singleElement().satisfies(item -> {
            assertThat(item.getRecipientUserId()).isEqualTo(director);
            assertThat(item.getPriority()).isEqualTo(InBasketPriority.NORMAL);
            assertThat(item.getReferenceType()).isEqualTo(OPositiveReleaseNotices.REVIEW_REFERENCE_TYPE);
            assertThat(item.getBodyKey()).isEqualTo(OPositiveReleaseNotices.REVIEW_ROUTINE_BODY_KEY);
        });
    }

    @Test
    @DisplayName("row 1: a woman above the cut-off is low risk too; at the cut-off she is not")
    void womanAboveTheCutoff() {
        woman(51);
        assertThat(release(request(), null, null, false).getTierReason()).isEqualTo(OPositiveTierReason.FEMALE_ABOVE_CUTOFF);
        woman(50);
        TransfusionRequest request = request();
        assertThatThrownBy(() -> release(request, null, null, false))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("documented emergency decision");
    }

    @Test
    @DisplayName("row 1, typed RhD negative: the documented step, and the issuer may still name themself")
    void lowRiskTypedNegativeIssuerDecides() {
        man(40);
        TransfusionRequest request = request();
        assertThatThrownBy(() -> release(request, null, RhFactor.NEGATIVE, false)).isInstanceOf(FieldBusinessException.class);
        OPositiveReleaseDecision decision = release(request, naming(issuer), RhFactor.NEGATIVE, false);
        assertThat(decision.getDecisionBasis()).isEqualTo(OPositiveDecisionBasis.PROTOCOL);
        assertThat(decision.getDecidedByUserId()).isEqualTo(issuer);
        assertThat(decision.getCountersignDueAt()).isNull();
        // Nobody named at all: under the protocol the decision is the issuer's, and the bag goes out.
        assertThat(release(request(), nobodyReachable(), RhFactor.NEGATIVE, false).getDecidedByUserId()).isEqualTo(issuer);
    }

    // ── Row 2 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("row 2: a woman of 30 goes out on a named physician's verbal order: countersignature, urgent review, follow-up, flag")
    void highRiskOnAPhysiciansOrder() {
        OPositiveReleaseDecision decision = release(request(), naming(doctor), null, false);

        assertThat(decision.getRiskTier()).isEqualTo(OPositiveRiskTier.HIGH_RISK);
        assertThat(decision.getTierReason()).isEqualTo(OPositiveTierReason.FEMALE_AT_OR_BELOW_CUTOFF);
        assertThat(decision.getDecisionBasis()).isEqualTo(OPositiveDecisionBasis.PHYSICIAN_ORDER);
        assertThat(decision.getDecidedByUserId()).isEqualTo(doctor);
        assertThat(decision.isVerbalOrder()).isTrue();
        assertThat(decision.getCountersignDueAt()).isEqualTo(NOW.plusHours(24));
        assertThat(decision.getReviewPriority()).isEqualTo(OPositiveReviewPriority.URGENT);
        assertThat(decision.getReviewDueAt()).isEqualTo(NOW.plusHours(24));
        assertThat(decision.isRhdExposure()).isTrue();
        assertThat(decision.isFollowUpRequired()).isTrue();
        assertThat(decision.getAntibodyScreenDueFrom()).isEqualTo(NOW.toLocalDate().plusMonths(3));
        assertThat(decision.getAntibodyScreenDueBy()).isEqualTo(NOW.toLocalDate().plusMonths(6));
        assertThat(decision.getPatientSex()).isEqualTo("FEMALE");
        assertThat(decision.getPatientAgeYears()).isEqualTo(30);

        assertThat(items(OPositiveReleaseNotices.REVIEW_REFERENCE_TYPE)).singleElement().satisfies(item -> {
            assertThat(item.getRecipientUserId()).isEqualTo(director);
            assertThat(item.getPriority()).isEqualTo(InBasketPriority.URGENT);
            assertThat(item.getBodyKey()).isEqualTo(OPositiveReleaseNotices.REVIEW_URGENT_BODY_KEY);
        });
        assertThat(items(OPositiveReleaseNotices.COUNTERSIGN_REFERENCE_TYPE))
            .extracting(CreateInBasketItemRequest::getRecipientUserId).containsExactlyInAnyOrder(doctor, requester);
        assertThat(items(OPositiveReleaseNotices.FOLLOW_UP_REFERENCE_TYPE))
            .extracting(CreateInBasketItemRequest::getRecipientUserId).containsExactlyInAnyOrder(doctor, requester);
        assertThat(items()).allSatisfy(item -> assertThat(item.getBody()).doesNotContain("{"));
    }

    @Test
    @DisplayName("row 2: the issuer is never the physician; a midwife or a technician is not one; the laboratory director may be named")
    void highRiskWhoMayBeNamed() {
        TransfusionRequest request = request();
        assertThatThrownBy(() -> release(request, naming(issuer), null, false))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("not the person issuing");
        assertThatThrownBy(() -> release(request, naming(midwife), null, false))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("active role");
        assertThatThrownBy(() -> release(request, naming(null), null, false))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("no physician can be reached");
        assertThat(fixture.decisions).isEmpty();

        // « The biologist or laboratory director if reachable »: accepted, and recorded as the director's
        // authority, never as a physician's order.
        OPositiveReleaseDecision byTheDirector = release(request, naming(director), null, false);
        assertThat(byTheDirector.getDecisionBasis()).isEqualTo(OPositiveDecisionBasis.BIOLOGIST_AUTHORITY);
        assertThat(byTheDirector.getDecidedByUserId()).isEqualTo(director);
        assertThat(byTheDirector.getCountersignDueAt()).isNotNull();
    }

    @Test
    @DisplayName("row 2: an issuer who is the laboratory director still cannot decide alone")
    void aDirectorIssuingIsStillTheIssuer() {
        when(roleValidator.getCurrentUserId()).thenReturn(director);
        TransfusionRequest request = request();
        assertThatThrownBy(() -> release(request, naming(director), null, false))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("not the person issuing");
    }

    @Test
    @DisplayName("row 2: a girl, a boy, an unknown sex, an unknown age; boys move to row 1 only when the hospital says so")
    void highRiskChildrenAndUnknowns() {
        woman(8);
        assertThat(release(request(), naming(doctor), null, false).getRiskTier()).isEqualTo(OPositiveRiskTier.HIGH_RISK);
        man(8);
        assertThat(release(request(), naming(doctor), null, false).getTierReason()).isEqualTo(OPositiveTierReason.MALE_UNDER_18);
        patient.setGender(null);
        OPositiveReleaseDecision unknownSex = release(request(), naming(doctor), null, false);
        assertThat(unknownSex.getTierReason()).isEqualTo(OPositiveTierReason.UNKNOWN_SEX);
        assertThat(unknownSex.getPatientSex()).isEqualTo("UNKNOWN");
        patient.setGender("MALE");
        patient.setDateOfBirth(null);
        OPositiveReleaseDecision unknownAge = release(request(), naming(doctor), null, false);
        assertThat(unknownAge.getTierReason()).isEqualTo(OPositiveTierReason.UNKNOWN_AGE);
        assertThat(unknownAge.getPatientAgeYears()).isNull();

        fixture.protocol(true, 50, true, 24);
        man(8);
        OPositiveReleaseDecision boy = release(request(), null, null, false);
        assertThat(boy.getTierReason()).isEqualTo(OPositiveTierReason.BOY_LOW_RISK_BY_PROTOCOL);
        assertThat(boy.isBoysLowRisk()).isTrue();
    }

    @Test
    @DisplayName("row 2: the hospital's deadline sets the countersignature and the urgent review")
    void theDeadlineIsTheHospitals() {
        fixture.protocol(true, 50, false, 12);
        OPositiveReleaseDecision decision = release(request(), naming(doctor), null, false);
        assertThat(decision.getCountersignDueAt()).isEqualTo(NOW.plusHours(12));
        assertThat(decision.getReviewDueAt()).isEqualTo(NOW.plusHours(12));
        assertThat(decision.getReviewDeadlineHours()).isEqualTo(12);
    }

    @Test
    @DisplayName("row 2, typed RhD positive by the laboratory: the physician still decides, and no exposure is flagged")
    void typedPositiveIsNoExposure() {
        OPositiveReleaseDecision decision = release(request(), naming(doctor), RhFactor.POSITIVE, false);
        assertThat(decision.getRiskTier()).isEqualTo(OPositiveRiskTier.HIGH_RISK);
        assertThat(decision.isRhdExposure()).isFalse();
        assertThat(decision.isFollowUpRequired()).isFalse();
        assertThat(decision.getPatientRh()).isEqualTo("POSITIVE");
    }

    // ── Never blocks ────────────────────────────────────────────────────

    @Test
    @DisplayName("NEVER BLOCKS: in every tier, with nobody to name, the issuer alone releases the bag; the authority is recorded as owed")
    void neverBlocksIssue() {
        record Case(String gender, Integer age, boolean antibody, boolean protocol) {
        }
        List<Case> cases = List.of(
            new Case("FEMALE", 30, false, true), new Case("FEMALE", 8, false, true), new Case("MALE", 8, false, true),
            new Case(null, 30, false, true), new Case("MALE", null, false, true), new Case("MALE", 40, true, true),
            new Case("FEMALE", 30, true, true), new Case("MALE", 40, false, false), new Case("FEMALE", 30, false, false),
            new Case("FEMALE", 30, true, false));
        // The worst night: no doctor, no laboratory director, nobody but the technician at the bench.
        roles.keySet().removeIf(id -> !id.equals(issuer));
        for (Case one : cases) {
            patient.setGender(one.gender());
            patient.setDateOfBirth(one.age() == null ? null : NOW.toLocalDate().minusYears(one.age()));
            if (one.protocol()) {
                fixture.activeProtocol();
            } else {
                fixture.noProtocol();
            }
            TransfusionRequest request = request();
            request.setRequestedBy(null);
            int before = fixture.decisions.size();
            assertThatCode(() -> release(request, nobodyReachable(), null, one.antibody())).as(one.toString())
                .doesNotThrowAnyException();
            assertThat(fixture.decisions).as(one.toString()).hasSize(before + 1);
            OPositiveReleaseDecision decision = fixture.last();
            assertThat(decision.getDecisionBasis()).as(one.toString()).isEqualTo(OPositiveDecisionBasis.AUTHORITY_PENDING);
            assertThat(decision.getDecidedByUserId()).as(one.toString()).isNull();
            assertThat(decision.getCountersignDueAt()).as(one.toString()).isEqualTo(NOW.plusHours(24));
        }
    }

    @Test
    @DisplayName("NEVER BLOCKS: a reader that cannot be read, an item that cannot be filed, an audit that cannot be written: the release stands, and the notices stay owed until delivered once")
    void aFailedNoticeDoesNotUndoTheReleaseAndIsDeliveredLater() {
        when(fixture.inBasket.createItem(any())).thenThrow(new IllegalStateException("in-basket down"));
        org.mockito.Mockito.doThrow(new IllegalStateException("audit down")).when(fixture.audit).logEvent(any());
        assertThatCode(() -> release(request(), naming(doctor), null, false)).doesNotThrowAnyException();
        assertThat(fixture.decisions).hasSize(1);
        OPositiveReleaseDecision decision = fixture.last();
        assertThat(decision.getNoticesFiledAt()).as("nothing was delivered: the row still owes its notices").isNull();

        // The recipients cannot be read either (the database hiccups): the release still stands.
        org.mockito.Mockito.reset(fixture.inBasket);
        when(assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(eq(hospitalId), anySet()))
            .thenThrow(new IllegalStateException("recipients unreadable"));
        assertThatCode(() -> release(request(), naming(doctor), null, false)).doesNotThrowAnyException();
        assertThat(fixture.decisions).hasSize(2);
        assertThat(fixture.last().getNoticesFiledAt()).isNull();
        UUID second = fixture.last().getId();
        assertThatThrownBy(() -> fixture.notices.deliver(second)).as("the sweep's own attempt fails loudly, and retries")
            .isInstanceOf(IllegalStateException.class);

        // The sweep delivers what is owed, once.
        org.mockito.Mockito.doAnswer(call -> {
            Set<String> asked = call.getArgument(1);
            return roles.entrySet().stream().filter(entry -> asked.contains(entry.getValue()))
                .map(entry -> user(entry.getKey())).toList();
        }).when(assignmentRepository).findLiveUsersAtHospitalWithRoleCodes(eq(hospitalId), anySet());
        when(fixture.decisionRepository.findPendingNoticeIds()).thenReturn(List.of(decision.getId(), second));
        assertThat(fixture.notices.pendingIds()).containsExactly(decision.getId(), second);
        assertThat(fixture.notices.deliver(decision.getId())).isEqualTo(5);
        assertThat(decision.getNoticesFiledAt()).isEqualTo(NOW);
        assertThat(fixture.notices.deliver(decision.getId())).as("filed once").isZero();
        verify(fixture.inBasket, times(5)).createItem(any());
        verify(fixture.inBasket, never()).createItemInNewTransaction(any());
    }

    @Test
    @DisplayName("nobody reachable: the review tells the biologist at once that the authority is owed, and the requester is asked to sign")
    void pendingAuthorityIsEscalatedAtOnce() {
        OPositiveReleaseDecision decision = release(request(), nobodyReachable(), null, false);
        assertThat(decision.getDecisionBasis()).isEqualTo(OPositiveDecisionBasis.AUTHORITY_PENDING);
        assertThat(items(OPositiveReleaseNotices.REVIEW_REFERENCE_TYPE)).singleElement().satisfies(item -> {
            assertThat(item.getRecipientUserId()).isEqualTo(director);
            assertThat(item.getPriority()).isEqualTo(InBasketPriority.URGENT);
            assertThat(item.getBodyKey()).isEqualTo(OPositiveReleaseNotices.REVIEW_PENDING_BODY_KEY);
        });
        assertThat(items(OPositiveReleaseNotices.COUNTERSIGN_REFERENCE_TYPE)).singleElement()
            .satisfies(item -> assertThat(item.getRecipientUserId()).isEqualTo(requester));
    }

    @Test
    @DisplayName("stating nobody is reachable while naming a real physician keeps the physician's name")
    void aNamedPhysicianWinsOverTheStatement() {
        EmergencyReleaseRequestDTO input = naming(doctor);
        input.setPhysicianUnreachable(Boolean.TRUE);
        assertThat(release(request(), input, null, false).getDecisionBasis()).isEqualTo(OPositiveDecisionBasis.PHYSICIAN_ORDER);
    }

    // ── Row 3 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("row 3: a known antibody needs a physician and a biologist together; the biologist is alerted at once")
    void knownAntibodyPhysicianAndBiologist() {
        man(40);
        EmergencyReleaseRequestDTO input = naming(doctor);
        input.setBiologistUserId(director);
        OPositiveReleaseDecision decision = release(request(), input, null, true);

        assertThat(decision.getRiskTier()).isEqualTo(OPositiveRiskTier.KNOWN_ANTIBODY);
        assertThat(decision.getDecisionBasis()).isEqualTo(OPositiveDecisionBasis.PHYSICIAN_AND_BIOLOGIST);
        assertThat(decision.getDecidedByUserId()).isEqualTo(doctor);
        assertThat(decision.getBiologistUserId()).isEqualTo(director);
        assertThat(decision.getReviewPriority()).isEqualTo(OPositiveReviewPriority.IMMEDIATE);
        assertThat(decision.getReviewDueAt()).isEqualTo(NOW);
        assertThat(decision.getCountersignDueAt()).isEqualTo(NOW.plusHours(24));
        assertThat(items(OPositiveReleaseNotices.REVIEW_REFERENCE_TYPE)).singleElement().satisfies(item -> {
            assertThat(item.getPriority()).isEqualTo(InBasketPriority.CRITICAL);
            assertThat(item.getBodyKey()).isEqualTo(OPositiveReleaseNotices.REVIEW_IMMEDIATE_BODY_KEY);
        });
    }

    @Test
    @DisplayName("row 3: never the issuer alone, never one person in both seats; a biologist at the bench counts as the biologist")
    void knownAntibodyNeverOnePerson() {
        man(40);
        TransfusionRequest request = request();
        assertThatThrownBy(() -> release(request, null, null, true)).isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> release(request, naming(issuer), null, true))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("not the person issuing");
        // The laboratory director is the biologist, not the physician of this pair.
        assertThatThrownBy(() -> release(request, naming(director), null, true))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("active role");
        EmergencyReleaseRequestDTO same = naming(doctor);
        same.setBiologistUserId(doctor);
        assertThatThrownBy(() -> release(request, same, null, true))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("two different people");
        EmergencyReleaseRequestDTO notABiologist = naming(doctor);
        notABiologist.setBiologistUserId(midwife);
        assertThatThrownBy(() -> release(request, notABiologist, null, true))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("laboratory director");
        assertThat(fixture.decisions).isEmpty();

        // The physician alone: the bag goes out, the biologist's part is owed and they are alerted at once.
        OPositiveReleaseDecision physicianOnly = release(request, naming(doctor), null, true);
        assertThat(physicianOnly.getDecisionBasis()).isEqualTo(OPositiveDecisionBasis.AUTHORITY_PENDING);
        assertThat(physicianOnly.getDecidedByUserId()).isEqualTo(doctor);
        assertThat(physicianOnly.getBiologistUserId()).isNull();

        when(roleValidator.getCurrentUserId()).thenReturn(director);
        OPositiveReleaseDecision atTheBench = release(request(), naming(doctor), null, true);
        assertThat(atTheBench.getDecisionBasis()).isEqualTo(OPositiveDecisionBasis.PHYSICIAN_AND_BIOLOGIST);
        assertThat(atTheBench.getBiologistUserId()).isEqualTo(director);
    }

    @Test
    @DisplayName("row 3, incompatible release: a laboratory director who authorises it sits as the biologist, never as the physician")
    void aDirectorIsNeverThePhysicianOfAKnownAntibody() {
        man(40);
        UUID secondDirector = UUID.randomUUID();
        roles.put(secondDirector, SecurityConstants.ROLE_LAB_DIRECTOR);
        TransfusionRequest request = request();

        // Authorised by a director, a second director named as biologist: two biologists are not a physician.
        EmergencyReleaseRequestDTO twoDirectors = naming(director);
        twoDirectors.setReleaseType(EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE);
        twoDirectors.setBiologistUserId(secondDirector);
        OPositiveReleaseDecision decision = release(request, twoDirectors, null, true);
        assertThat(decision.getDecisionBasis()).isEqualTo(OPositiveDecisionBasis.AUTHORITY_PENDING);
        assertThat(decision.getDecidedByUserId()).as("no physician decided").isNull();
        assertThat(decision.getBiologistUserId()).isEqualTo(secondDirector);
        assertThat(decision.getCountersignDueAt()).isNotNull();

        // Authorised by a director alone: the director is the biologist of the record, the physician is owed.
        EmergencyReleaseRequestDTO oneDirector = naming(director);
        oneDirector.setReleaseType(EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE);
        OPositiveReleaseDecision alone = release(request, oneDirector, null, true);
        assertThat(alone.getDecisionBasis()).isEqualTo(OPositiveDecisionBasis.AUTHORITY_PENDING);
        assertThat(alone.getDecidedByUserId()).isNull();
        assertThat(alone.getBiologistUserId()).isEqualTo(director);

        // Authorised by a physician, with the biologist: the pair the rule asks for.
        EmergencyReleaseRequestDTO pair = naming(doctor);
        pair.setReleaseType(EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE);
        pair.setBiologistUserId(director);
        OPositiveReleaseDecision together = release(request, pair, null, true);
        assertThat(together.getDecisionBasis()).isEqualTo(OPositiveDecisionBasis.PHYSICIAN_AND_BIOLOGIST);
        assertThat(together.getDecidedByUserId()).isEqualTo(doctor);
    }

    // ── Row 4 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("row 4: with no protocol, or an inactive one, an adult man is handled as the high-risk row")
    void noProtocolIsHighRisk() {
        man(40);
        for (Runnable setting : List.<Runnable>of(fixture::noProtocol, () -> fixture.protocol(false, 50, true, 24))) {
            setting.run();
            TransfusionRequest request = request();
            assertThatThrownBy(() -> release(request, null, null, false))
                .isInstanceOf(FieldBusinessException.class).hasMessageContaining("documented emergency decision");
            assertThatThrownBy(() -> release(request, naming(issuer), null, false)).isInstanceOf(FieldBusinessException.class);
            OPositiveReleaseDecision decision = release(request, naming(doctor), null, false);
            assertThat(decision.getRiskTier()).isEqualTo(OPositiveRiskTier.HIGH_RISK);
            assertThat(decision.getTierReason()).isEqualTo(OPositiveTierReason.NO_ACTIVE_PROTOCOL);
            assertThat(decision.isProtocolActive()).isFalse();
            assertThat(decision.getDecisionBasis()).isEqualTo(OPositiveDecisionBasis.PHYSICIAN_ORDER);
            assertThat(decision.isRhdExposure()).isTrue();
        }
    }

    // ── What the rule is not about ──────────────────────────────────────

    @Test
    @DisplayName("O negative is not this rule's: nothing is asked and nothing is written")
    void oNegativeIsUntouched() {
        TransfusionRequest request = request();
        BloodUnit unit = bag(RhFactor.NEGATIVE);
        Authorisation authorisation = service.authorise(request, unit, EmergencyReleaseType.UNCROSSMATCHED, null,
            assess(request, null, false));
        assertThat(authorisation.applies()).isFalse();
        EmergencyBloodRelease row = EmergencyBloodRelease.builder().request(request).bloodUnit(unit).issuedAt(NOW).build();
        assertThat(service.record(row, authorisation, request, unit, ONegativeStatus.OUT_OF_STOCK)).isNull();
        verify(fixture.decisionRepository, never()).save(any());
        verify(fixture.inBasket, never()).createItemInNewTransaction(any());
    }

    @Test
    @DisplayName("every release is audited, ids only")
    void everyReleaseIsAudited() {
        OPositiveReleaseDecision decision = release(request(), naming(doctor), null, false);
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(fixture.audit).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.O_POSITIVE_RELEASE_DECIDED);
        assertThat(audit.getValue().getUserId()).isEqualTo(issuer);
        assertThat(audit.getValue().getResourceId()).isEqualTo(decision.getId().toString());
        assertThat(audit.getValue().getEventDescription())
            .isEqualTo("Emergency O positive release decision recorded for blood unit " + decision.getBloodUnitId()
                + " against transfusion request " + decision.getRequestId())
            .doesNotContain(patient.getId().toString());
        assertThat(String.valueOf(audit.getValue().getDetails())).contains("riskTier=HIGH_RISK")
            .contains("decisionBasis=PHYSICIAN_ORDER").contains("reviewPriority=URGENT");
    }

    @Test
    @DisplayName("with no biologist at the hospital the review goes to the clinicians, not nowhere")
    void noBiologistTellsTheClinicians() {
        roles.remove(director);
        release(request(), naming(doctor), null, false);
        assertThat(items(OPositiveReleaseNotices.REVIEW_REFERENCE_TYPE))
            .extracting(CreateInBasketItemRequest::getRecipientUserId).containsExactlyInAnyOrder(doctor, requester);
    }

    @Test
    @DisplayName("a midwife's request with nobody reachable: the signature and the follow-up are asked of the hospital's physicians, never of nobody")
    void theTasksAlwaysHaveSomebodyWhoCanDoThem() {
        roles.put(requester, SecurityConstants.ROLE_MIDWIFE);
        release(request(), nobodyReachable(), null, false);
        assertThat(items(OPositiveReleaseNotices.COUNTERSIGN_REFERENCE_TYPE))
            .extracting(CreateInBasketItemRequest::getRecipientUserId).containsExactlyInAnyOrder(doctor, otherDoctor);
        assertThat(items(OPositiveReleaseNotices.FOLLOW_UP_REFERENCE_TYPE))
            .extracting(CreateInBasketItemRequest::getRecipientUserId).containsExactlyInAnyOrder(doctor, otherDoctor);
    }

    // ── Countersignature ────────────────────────────────────────────────

    @Test
    @DisplayName("a physician who did not issue the bag countersigns, once; the issuer never; who signed is kept")
    void countersign() {
        OPositiveReleaseDecision decision = release(request(), naming(doctor), null, false);
        UUID id = decision.getId();

        assertThatThrownBy(() -> service.countersign(id, hospitalId))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class).hasMessageContaining("issued the unit");
        when(roleValidator.getCurrentUserId()).thenReturn(midwife);
        assertThatThrownBy(() -> service.countersign(id, hospitalId))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class).hasMessageContaining("physician");
        assertThatThrownBy(() -> service.countersign(id, UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(decision.getCountersignedAt()).isNull();

        // Another physician answering for the service may sign for the one named.
        when(roleValidator.getCurrentUserId()).thenReturn(otherDoctor);
        assertThat(service.countersign(id, hospitalId).getCountersignedAt()).isEqualTo(NOW);
        assertThat(decision.getCountersignedByUserId()).isEqualTo(otherDoctor);
        assertThat(decision.getDecidedByUserId()).as("what was decided at release is not rewritten").isEqualTo(doctor);
        verify(fixture.inBasket).removeOpenItemsForReference(id, OPositiveReleaseNotices.COUNTERSIGN_REFERENCE_TYPE);

        assertThatThrownBy(() -> service.countersign(id, hospitalId))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("No countersignature is owed");
    }

    @Test
    @DisplayName("a low-risk release owes no countersignature; a known antibody is countersigned by a physician, not the biologist")
    void countersignByTier() {
        man(40);
        UUID low = release(request(), null, null, false).getId();
        when(roleValidator.getCurrentUserId()).thenReturn(doctor);
        assertThatThrownBy(() -> service.countersign(low, hospitalId)).hasMessageContaining("No countersignature is owed");

        when(roleValidator.getCurrentUserId()).thenReturn(issuer);
        UUID antibody = release(request(), naming(doctor), null, true).getId();
        when(roleValidator.getCurrentUserId()).thenReturn(director);
        assertThatThrownBy(() -> service.countersign(antibody, hospitalId))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        when(roleValidator.getCurrentUserId()).thenReturn(doctor);
        assertThat(service.countersign(antibody, hospitalId).getCountersignedByName()).isNotBlank();
    }

    // ── Review ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the biologist reviews, once; nobody else")
    void review() {
        UUID id = release(request(), naming(doctor), null, false).getId();
        when(roleValidator.getCurrentUserId()).thenReturn(doctor);
        assertThatThrownBy(() -> service.review(id, hospitalId, "seen"))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        when(roleValidator.getCurrentUserId()).thenReturn(director);
        assertThat(service.review(id, hospitalId, "  Anti-D immunoglobulin discussed with Dr X  ").getReviewNote())
            .isEqualTo("Anti-D immunoglobulin discussed with Dr X");
        assertThat(fixture.last().getReviewedByUserId()).isEqualTo(director);
        assertThat(fixture.last().getReviewedAt()).isEqualTo(NOW);
        verify(fixture.inBasket).removeOpenItemsForReference(id, OPositiveReleaseNotices.REVIEW_REFERENCE_TYPE);
        assertThatThrownBy(() -> service.review(id, hospitalId, null)).hasMessageContaining("already been reviewed");
    }

    // ── Follow-up and the permanent flag ────────────────────────────────

    @Test
    @DisplayName("the follow-up stays open until a physician has decided on anti-D or exchange AND the patient was told")
    void followUp() {
        OPositiveReleaseDecision decision = release(request(), naming(doctor), null, false);
        UUID id = decision.getId();
        OPositiveFollowUpRequestDTO empty = new OPositiveFollowUpRequestDTO();
        OPositiveFollowUpRequestDTO decides = OPositiveFollowUpRequestDTO.builder().antiDDecision(AntiDDecision.ANTI_D_IMMUNOGLOBULIN).build();
        OPositiveFollowUpRequestDTO informs = OPositiveFollowUpRequestDTO.builder().patientInformed(Boolean.TRUE).note("Told with her husband").build();

        assertThatThrownBy(() -> service.recordFollowUp(id, hospitalId, decides))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        when(roleValidator.getCurrentUserId()).thenReturn(doctor);
        assertThatThrownBy(() -> service.recordFollowUp(id, hospitalId, empty)).isInstanceOf(FieldBusinessException.class);

        service.recordFollowUp(id, hospitalId, decides);
        assertThat(decision.getAntiDDecision()).isEqualTo(AntiDDecision.ANTI_D_IMMUNOGLOBULIN);
        assertThat(decision.followUpOwed()).isTrue();
        verify(fixture.inBasket, never()).removeOpenItemsForReference(id, OPositiveReleaseNotices.FOLLOW_UP_REFERENCE_TYPE);
        assertThatThrownBy(() -> service.recordFollowUp(id, hospitalId, decides)).hasMessageContaining("already recorded");

        service.recordFollowUp(id, hospitalId, informs);
        assertThat(decision.getPatientInformedByUserId()).isEqualTo(doctor);
        assertThat(decision.getFollowUpNote()).isEqualTo("Told with her husband");
        assertThat(decision.followUpOwed()).isFalse();
        verify(fixture.inBasket).removeOpenItemsForReference(id, OPositiveReleaseNotices.FOLLOW_UP_REFERENCE_TYPE);
        assertThat(decision.isRhdExposure()).as("the flag is permanent: closing the follow-up clears nothing").isTrue();
    }

    @Test
    @DisplayName("a low-risk release has no follow-up to record")
    void noFollowUpInTheLowRiskTier() {
        man(40);
        UUID id = release(request(), null, null, false).getId();
        when(roleValidator.getCurrentUserId()).thenReturn(doctor);
        OPositiveFollowUpRequestDTO informs = OPositiveFollowUpRequestDTO.builder().patientInformed(Boolean.TRUE).build();
        assertThatThrownBy(() -> service.recordFollowUp(id, hospitalId, informs)).hasMessageContaining("No exposure follow-up");
    }

    @Test
    @DisplayName("an antibody screen is marked in one statement on its own column, at the hospital that recorded it")
    void theAntibodyScreenIsMarkedAtomically() {
        release(request(), naming(doctor), null, false);
        org.mockito.Mockito.clearInvocations(fixture.decisionRepository);
        service.noteAntibodyScreen(patient.getId(), hospitalId, NOW.plusMonths(4));
        verify(fixture.decisionRepository).markAntibodyScreenDone(patient.getId(), hospitalId, NOW.plusMonths(4),
            NOW.plusMonths(4).toLocalDate());
        verify(fixture.decisionRepository, never()).save(any());
        service.noteAntibodyScreen(null, hospitalId, NOW);
        service.noteAntibodyScreen(patient.getId(), null, NOW);
        service.noteAntibodyScreen(patient.getId(), hospitalId, null);
        org.mockito.Mockito.verifyNoMoreInteractions(fixture.decisionRepository);
    }

    private static com.example.hms.repository.OPositiveReleaseDecisionRepository.ExposureRow exposure(
            LocalDateTime issuedAt, UUID hospital, String name, com.example.hms.enums.BloodUnitStatus status, long hung) {
        var row = org.mockito.Mockito.mock(com.example.hms.repository.OPositiveReleaseDecisionRepository.ExposureRow.class);
        when(row.getIssuedAt()).thenReturn(issuedAt);
        when(row.getHospitalId()).thenReturn(hospital);
        when(row.getHospitalName()).thenReturn(name);
        when(row.getUnitStatus()).thenReturn(status);
        when(row.getAdministrations()).thenReturn(hung);
        return row;
    }

    @Test
    @DisplayName("the flag follows the patient to every hospital, and says what is true: given, out and unconfirmed, or nothing for a bag that came back")
    void theFlagSaysWhatBecameOfTheBag() {
        UUID elsewhere = UUID.randomUUID();
        UUID acting = UUID.randomUUID();
        var returned = exposure(NOW, elsewhere, "Clinique A", com.example.hms.enums.BloodUnitStatus.RETURNED, 0);
        var stillOut = exposure(NOW.minusDays(1), elsewhere, "Clinique A", com.example.hms.enums.BloodUnitStatus.ISSUED, 0);
        var given = exposure(NOW.minusDays(9), elsewhere, "Clinique A", com.example.hms.enums.BloodUnitStatus.DISCARDED, 1);

        // A bag issued and returned unused exposed nobody: no lifelong statement rests on it.
        when(fixture.decisionRepository.findExposures(patient.getId())).thenReturn(List.of(returned));
        assertThat(service.exposureShownAt(patient.getId(), acting)).isNull();

        // Out, with no administration on record: shown, as unconfirmed.
        when(fixture.decisionRepository.findExposures(patient.getId())).thenReturn(List.of(returned, stillOut));
        assertThat(service.exposureShownAt(patient.getId(), acting)).satisfies(shown -> {
            assertThat(shown.confirmed()).isFalse();
            assertThat(shown.issuedAt()).isEqualTo(NOW.minusDays(1));
            assertThat(shown.hospitalName()).isEqualTo("Clinique A");
        });

        // Hung (whatever became of the bag afterwards): confirmed, and it outranks a newer unconfirmed one.
        when(fixture.decisionRepository.findExposures(patient.getId())).thenReturn(List.of(returned, stillOut, given));
        assertThat(service.exposureShownAt(patient.getId(), acting)).satisfies(shown -> {
            assertThat(shown.confirmed()).isTrue();
            assertThat(shown.issuedAt()).isEqualTo(NOW.minusDays(9));
        });
        assertThat(service.exposureShownAt(null, acting)).isNull();
        assertThat(service.exposureShownAt(UUID.randomUUID(), acting)).isNull();
    }

    @Test
    @DisplayName("the queue says what became of each bag, and a covering signature is shown as one")
    void theQueueSaysWhatIsTrue() {
        OPositiveReleaseDecision decision = release(request(), naming(doctor), null, false);
        decision.getRelease().getBloodUnit().setStatus(com.example.hms.enums.BloodUnitStatus.ISSUED);
        when(fixture.decisionRepository.findByHospital_IdOrderByIssuedAtDesc(hospitalId)).thenReturn(List.of(decision));
        assertThat(service.list(hospitalId, true)).singleElement().satisfies(row -> {
            assertThat(row.getExposureState()).isEqualTo("UNCONFIRMED");
            assertThat(row.isCountersignCovering()).isFalse();
        });

        when(fixture.decisionRepository.countAdministrations(decision.getBloodUnitId())).thenReturn(1L);
        when(roleValidator.getCurrentUserId()).thenReturn(otherDoctor);
        assertThat(service.countersign(decision.getId(), hospitalId)).satisfies(row -> {
            assertThat(row.getExposureState()).isEqualTo("CONFIRMED");
            assertThat(row.isCountersignCovering()).as("signed by another physician than the one named").isTrue();
        });

        // Reviewed and countersigned; the bag came back unused: nothing is owed on it any more.
        when(roleValidator.getCurrentUserId()).thenReturn(director);
        service.review(decision.getId(), hospitalId, null);
        when(fixture.decisionRepository.countAdministrations(decision.getBloodUnitId())).thenReturn(0L);
        decision.getRelease().getBloodUnit().setStatus(com.example.hms.enums.BloodUnitStatus.RETURNED);
        assertThat(service.list(hospitalId, false)).singleElement()
            .satisfies(row -> assertThat(row.getExposureState()).isEqualTo("NOT_GIVEN"));
        assertThat(service.list(hospitalId, true)).isEmpty();
    }

    // ── The overdue countersignature ────────────────────────────────────

    private List<CreateInBasketItemRequest> escalated() {
        ArgumentCaptor<CreateInBasketItemRequest> captor = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(fixture.inBasket, org.mockito.Mockito.atLeast(0)).createItem(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("an overdue countersignature is escalated to the laboratory director, once, and audited")
    void overdueCountersignIsEscalated() {
        OPositiveReleaseDecision decision = release(request(), naming(doctor), null, false);
        decision.getRelease().getBloodUnit().setUnitNumber("U-OVERDUE");
        when(fixture.decisionRepository.findOverdueCountersignIds(NOW)).thenReturn(List.of(decision.getId()));
        when(fixture.patientRepository.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        org.mockito.Mockito.clearInvocations(fixture.inBasket, fixture.audit);

        assertThat(fixture.notices.overdueCountersignIds()).containsExactly(decision.getId());
        assertThat(fixture.notices.escalateOverdueCountersign(decision.getId())).isEqualTo(1);
        assertThat(decision.getCountersignEscalatedAt()).isEqualTo(NOW);
        assertThat(escalated()).singleElement().satisfies(item -> {
            assertThat(item.getRecipientUserId()).isEqualTo(director);
            assertThat(item.getPriority()).isEqualTo(InBasketPriority.URGENT);
            assertThat(item.getReferenceType()).isEqualTo(OPositiveReleaseNotices.OVERDUE_REFERENCE_TYPE);
            assertThat(item.getTitle()).contains("U-OVERDUE");
        });
        // The alert is filed in the transaction of the mark, never after it.
        verify(fixture.inBasket, never()).createItemInNewTransaction(any());
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(fixture.audit).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.O_POSITIVE_COUNTERSIGN_OVERDUE);

        // The row is still listed by a stale read: it is not escalated twice.
        assertThat(fixture.notices.escalateOverdueCountersign(decision.getId())).isZero();
        verify(fixture.inBasket, times(1)).createItem(any());
    }

    @Test
    @DisplayName("an alert that cannot be delivered fails the row's transaction: the mark is not kept and the next sweep tries again")
    void anUndeliveredEscalationIsNotMarkedAsTold() {
        OPositiveReleaseDecision decision = release(request(), naming(doctor), null, false);
        when(fixture.patientRepository.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(fixture.inBasket.createItem(any())).thenThrow(new IllegalStateException("in-basket down"));
        UUID id = decision.getId();
        // The exception leaves the transactional method: Spring rolls the mark back (on PostgreSQL in the IT).
        assertThatThrownBy(() -> fixture.notices.escalateOverdueCountersign(id)).isInstanceOf(IllegalStateException.class);
        verify(fixture.audit, never()).logEvent(org.mockito.ArgumentMatchers.argThat(
            event -> event.getEventType() == AuditEventType.O_POSITIVE_COUNTERSIGN_OVERDUE));
    }

    @Test
    @DisplayName("a countersigned release is not escalated; with no laboratory director the row waits for the next sweep")
    void whatTheSweepLeaves() {
        OPositiveReleaseDecision signed = release(request(), naming(doctor), null, false);
        when(roleValidator.getCurrentUserId()).thenReturn(doctor);
        service.countersign(signed.getId(), hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(issuer);
        OPositiveReleaseDecision orphan = release(request(), naming(doctor), null, false);
        roles.remove(director);

        assertThat(fixture.notices.escalateOverdueCountersign(signed.getId())).isZero();
        assertThat(fixture.notices.escalateOverdueCountersign(orphan.getId())).isZero();
        assertThat(signed.getCountersignEscalatedAt()).isNull();
        assertThat(orphan.getCountersignEscalatedAt()).as("nobody was told: it is not marked as told").isNull();
        assertThat(fixture.notices.escalateOverdueCountersign(UUID.randomUUID())).as("a row that is gone").isZero();
    }

    // ── The protocol ────────────────────────────────────────────────────

    @Test
    @DisplayName("only the hospital's biologist sets the protocol, within its bounds; the change is audited with its values")
    void theProtocol() {
        assertThat(service.protocol(hospitalId)).isEqualTo(new OPositiveProtocolDTO(hospitalId, true, 50, false, 24));
        fixture.noProtocol();
        assertThat(service.protocol(hospitalId)).isEqualTo(new OPositiveProtocolDTO(hospitalId, false, 50, false, 24));

        OPositiveProtocolDTO update = new OPositiveProtocolDTO(null, true, 45, true, 12);
        assertThatThrownBy(() -> service.setProtocol(hospital, update))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        when(roleValidator.getCurrentUserId()).thenReturn(director);
        assertThatThrownBy(() -> service.setProtocol(hospital, new OPositiveProtocolDTO(null, true, 39, false, 24)))
            .isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> service.setProtocol(hospital, new OPositiveProtocolDTO(null, true, 61, false, 24)))
            .isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> service.setProtocol(hospital, new OPositiveProtocolDTO(null, true, 50, false, 0)))
            .isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> service.setProtocol(hospital, new OPositiveProtocolDTO(null, true, 50, false, 73)))
            .isInstanceOf(FieldBusinessException.class);
        verify(fixture.protocolRepository, never()).save(any());

        assertThat(service.setProtocol(hospital, update)).isEqualTo(new OPositiveProtocolDTO(hospitalId, true, 45, true, 12));
        ArgumentCaptor<com.example.hms.model.OPositiveReleaseProtocol> saved =
            ArgumentCaptor.forClass(com.example.hms.model.OPositiveReleaseProtocol.class);
        verify(fixture.protocolRepository).save(saved.capture());
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(saved.getValue().getFemaleAgeCutoff()).isEqualTo(45);
        assertThat(saved.getValue().isBoysLowRisk()).isTrue();
        assertThat(saved.getValue().getReviewDeadlineHours()).isEqualTo(12);
        assertThat(saved.getValue().getUpdatedByUserId()).isEqualTo(director);
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(fixture.audit).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.CONFIGURATION_CHANGED);
        assertThat(String.valueOf(audit.getValue().getDetails())).contains("femaleAgeCutoff=45").contains("active=true");

        // Nothing sent but the switch: the defaults. An empty body switches nothing off by accident.
        assertThat(service.setProtocol(hospital, new OPositiveProtocolDTO(null, false, null, null, null)))
            .isEqualTo(new OPositiveProtocolDTO(hospitalId, false, 50, false, 24));
        assertThatThrownBy(() -> service.setProtocol(hospital, new OPositiveProtocolDTO(null, null, null, null, null)))
            .isInstanceOf(FieldBusinessException.class).hasMessageContaining("written protocol");
    }

    @Test
    @DisplayName("the form is told the tier and offers the people the server will accept: never the caller as the physician")
    void theFormIsTold() {
        TransfusionRequest request = request();
        var high = service.ruleFor(assess(request, null, false), hospitalId);
        assertThat(high.getRiskTier()).isEqualTo(OPositiveRiskTier.HIGH_RISK);
        assertThat(high.isIssuerMayDecide()).isFalse();
        assertThat(high.isPlainIssueAllowed()).isFalse();
        assertThat(high.getPhysicians()).extracting(candidate -> candidate.userId())
            .containsExactlyInAnyOrder(doctor, otherDoctor, requester, director).doesNotContain(issuer);
        assertThat(high.getBiologists()).isEmpty();

        var antibody = service.ruleFor(assess(request, null, true), hospitalId);
        assertThat(antibody.getPhysicians()).extracting(candidate -> candidate.userId()).doesNotContain(director, issuer);
        assertThat(antibody.getBiologists()).extracting(candidate -> candidate.userId()).containsExactly(director);

        man(40);
        var low = service.ruleFor(assess(request, null, false), hospitalId);
        assertThat(low.isIssuerMayDecide()).isTrue();
        assertThat(low.isPlainIssueAllowed()).isTrue();
        assertThat(low.getPhysicians()).isEmpty();
    }
}

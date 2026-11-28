package com.example.hms.service;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.BloodUnitReturnOutcome;
import com.example.hms.enums.BloodUnitStatus;
import com.example.hms.enums.EmergencyReleaseType;
import com.example.hms.enums.RhFactor;
import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.enums.TransfusionUrgency;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.BloodUnit;
import com.example.hms.model.EmergencyBloodRelease;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.TransfusionCrossmatch;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseRequestDTO;
import com.example.hms.repository.BloodUnitRepository;
import com.example.hms.repository.EmergencyBloodReleaseRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientBloodGroupRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.TransfusionAdministrationRepository;
import com.example.hms.repository.TransfusionCrossmatchRepository;
import com.example.hms.repository.TransfusionReactionRepository;
import com.example.hms.repository.TransfusionRequestRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.service.impl.TransfusionServiceImpl;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D3906 (r77, still open in r81): an emergency request for one unit took two bags, O positive on the documented
 * decision and then O negative by a plain issue, with no prompt and no « units requested » check. A bag beyond
 * the request now leaves only on the deliberate « ajouter une unité » step, on every way out of the laboratory,
 * and the step is audited. Within the request nothing changes: the emergency release rules are tested in
 * {@link TransfusionEmergencyReleaseTest}, and are never blocked by this check.
 */
@ExtendWith({MockitoExtension.class, com.example.hms.i18n.EnglishMessageBundles.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class TransfusionAddedUnitTest {

    @Mock private PatientBloodGroupRepository bloodGroupRepository;
    @Mock private PatientRepository patientRepository;
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
    @Mock private InBasketService inBasketService;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private EmergencyBloodReleaseRepository releaseRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private UserRepository userRepository;
    @Spy private Clock clock = Clock.systemDefaultZone();
    @Spy private TransfusionMapper mapper = new TransfusionMapper(Clock.systemDefaultZone());

    @InjectMocks private TransfusionServiceImpl service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID issuer = UUID.randomUUID();
    private final UUID doctor = UUID.randomUUID();
    private Hospital hospital;
    private Patient patient;
    private final List<BloodUnit> bags = new ArrayList<>();
    private final List<EmergencyBloodRelease> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(hospitalId);
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        // P04 of r77: a woman of childbearing age, so O positive takes the documented decision.
        patient.setGender("FEMALE");
        patient.setDateOfBirth(LocalDate.now().minusYears(30));
        service.setPositiveAuthority(new com.example.hms.service.transfusion.OPositiveAuthorityFixture(
            assignmentRepository, userRepository, roleValidator, clock, bloodGroupRepository, crossmatchRepository,
            administrationRepository).activeProtocol().service);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(issuer);
        when(unitRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(requestRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(releaseRepository.save(any())).thenAnswer(call -> {
            EmergencyBloodRelease row = call.getArgument(0);
            row.setId(UUID.randomUUID());
            rows.add(row);
            return row;
        });
        when(userRepository.getReferenceById(any())).thenAnswer(call -> {
            User user = new User();
            user.setId(call.getArgument(0));
            return user;
        });
        when(assignmentRepository.existsActiveWitness(any(), eq(hospitalId), anySet())).thenReturn(true);
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdOrderByPerformedAtDesc(any(), any())).thenReturn(List.of());
        when(unitRepository.findStockForPatient(eq(hospitalId), eq(patient.getId()), eq(BloodProductType.PACKED_RED_CELLS),
            eq(AboGroup.O), eq(RhFactor.NEGATIVE), any())).thenReturn(List.of());
        // The bags of a request as the database holds them: whatever the service last left on each bag.
        when(unitRepository.findByRequest_IdOrderByUnitNumberAsc(any())).thenAnswer(call -> bags.stream()
            .filter(bag -> bag.getRequest() != null && Objects.equals(bag.getRequest().getId(), call.getArgument(0)))
            .toList());
    }

    private TransfusionRequest request(TransfusionUrgency urgency, int units) {
        TransfusionRequest request = TransfusionRequest.builder().patient(patient).hospital(hospital)
            .productType(BloodProductType.PACKED_RED_CELLS).unitsRequested(units).indication("Haemorrhage")
            .urgency(urgency).status(TransfusionRequestStatus.REQUESTED).requestedAt(LocalDateTime.now()).build();
        request.setId(UUID.randomUUID());
        when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
        return request;
    }

    private BloodUnit bag(String number, AboGroup abo, RhFactor rh) {
        BloodUnit unit = BloodUnit.builder().hospital(hospital).unitNumber(number).productType(BloodProductType.PACKED_RED_CELLS)
            .aboGroup(abo).rhFactor(rh).expiresOn(LocalDate.now().plusDays(20)).status(BloodUnitStatus.AVAILABLE).build();
        unit.setId(UUID.randomUUID());
        when(unitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
        bags.add(unit);
        return unit;
    }

    private void crossmatched(TransfusionRequest request, BloodUnit unit) {
        TransfusionCrossmatch crossmatch = TransfusionCrossmatch.builder().request(request).bloodUnit(unit).hospital(hospital)
            .compatible(Boolean.TRUE).performedAt(LocalDateTime.now().minusMinutes(30))
            .expiresAt(LocalDateTime.now().plusHours(48)).build();
        crossmatch.setId(UUID.randomUUID());
        unit.setStatus(BloodUnitStatus.CROSSMATCHED);
        unit.setRequest(request);
        when(crossmatchRepository.findByRequest_IdAndBloodUnit_Id(request.getId(), unit.getId())).thenReturn(Optional.of(crossmatch));
    }

    private EmergencyReleaseRequestDTO decision(BloodUnit unit) {
        return EmergencyReleaseRequestDTO.builder().bloodUnitId(unit.getId())
            .releaseType(EmergencyReleaseType.O_POSITIVE_DECISION).reason("Massive haemorrhage")
            .authoriserUserId(doctor).build();
    }

    private List<AuditEventRequestDTO> addedUnitAudits() {
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, atLeast(0)).logEvent(captor.capture());
        return captor.getAllValues().stream()
            .filter(row -> row.getEventType() == AuditEventType.BLOOD_UNIT_ADDED_BEYOND_REQUEST).toList();
    }

    private static void refusedOnTheStep(Runnable issue) {
        assertThatThrownBy(issue::run)
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refusal -> assertThat(refusal.getField()).isEqualTo("additionalUnit"))
            .hasMessageContaining("beyond the request")
            .hasMessageContaining("1 unit requested")
            .hasMessageContaining("1 already out");
    }

    @Test
    @DisplayName("r77 P04: a request for 1 takes O+ on the decision; the O- bag after it needs « ajouter une unité »")
    void theSecondBagOfTheQaCaseNeedsTheStep() {
        TransfusionRequest request = request(TransfusionUrgency.EMERGENCY, 1);
        UUID requestId = request.getId();
        BloodUnit positive = bag("OPOS-1", AboGroup.O, RhFactor.POSITIVE);
        BloodUnit negative = bag("ONEG-2", AboGroup.O, RhFactor.NEGATIVE);
        UUID negativeId = negative.getId();

        service.emergencyRelease(requestId, decision(positive));
        assertThat(positive.getStatus()).isEqualTo(BloodUnitStatus.ISSUED);
        assertThat(addedUnitAudits()).isEmpty();

        refusedOnTheStep(() -> service.issueUnit(requestId, negativeId));
        assertThat(negative.getStatus()).isEqualTo(BloodUnitStatus.AVAILABLE);
        assertThat(addedUnitAudits()).isEmpty();

        // The step taken, the bag goes out at once, on the rules of its own path: nothing else is asked.
        service.issueUnit(requestId, negativeId, true);
        assertThat(negative.getStatus()).isEqualTo(BloodUnitStatus.ISSUED);
        assertThat(addedUnitAudits()).singleElement().satisfies(row -> {
            assertThat(row.getUserId()).isEqualTo(issuer);
            assertThat(row.getHospitalId()).isEqualTo(hospitalId);
            assertThat(row.getResourceId()).isEqualTo(requestId.toString());
            assertThat(row.getEventDescription()).isEqualTo("Blood unit " + negativeId
                + " added beyond the units requested on transfusion request " + requestId + ": unit 2 of 1 requested");
        });
    }

    @Test
    @DisplayName("the documented O+ decision past the request needs the step too, and keeps its own record")
    void aDecisionBeyondTheRequestNeedsTheStep() {
        TransfusionRequest request = request(TransfusionUrgency.EMERGENCY, 1);
        UUID requestId = request.getId();
        BloodUnit negative = bag("ONEG-1", AboGroup.O, RhFactor.NEGATIVE);
        BloodUnit positive = bag("OPOS-2", AboGroup.O, RhFactor.POSITIVE);
        service.issueUnit(requestId, negative.getId());
        rows.clear();

        EmergencyReleaseRequestDTO plain = decision(positive);
        refusedOnTheStep(() -> service.emergencyRelease(requestId, plain));
        assertThat(rows).isEmpty();
        assertThat(positive.getStatus()).isEqualTo(BloodUnitStatus.AVAILABLE);

        EmergencyReleaseRequestDTO added = decision(positive);
        added.setAdditionalUnit(Boolean.TRUE);
        service.emergencyRelease(requestId, added);
        assertThat(positive.getStatus()).isEqualTo(BloodUnitStatus.ISSUED);
        assertThat(rows).extracting(EmergencyBloodRelease::getReleaseType).containsExactly(EmergencyReleaseType.O_POSITIVE_DECISION);
        assertThat(addedUnitAudits()).hasSize(1);
    }

    @Test
    @DisplayName("a routine crossmatched bag past the request needs the step: the check is on every way out")
    void aCrossmatchedBagBeyondTheRequestNeedsTheStep() {
        TransfusionRequest request = request(TransfusionUrgency.ROUTINE, 1);
        UUID requestId = request.getId();
        BloodUnit first = bag("A-1", AboGroup.O, RhFactor.NEGATIVE);
        BloodUnit second = bag("A-2", AboGroup.O, RhFactor.NEGATIVE);
        UUID secondId = second.getId();
        crossmatched(request, first);
        crossmatched(request, second);

        service.issueUnit(requestId, first.getId());
        refusedOnTheStep(() -> service.issueUnit(requestId, secondId));
        assertThat(second.getStatus()).isEqualTo(BloodUnitStatus.CROSSMATCHED);
        service.issueUnit(requestId, secondId, true);
        assertThat(second.getStatus()).isEqualTo(BloodUnitStatus.ISSUED);
    }

    @Test
    @DisplayName("every bag within the request goes out with no step: two of two, no prompt, no added-unit audit")
    void withinTheRequestNothingIsAsked() {
        TransfusionRequest request = request(TransfusionUrgency.EMERGENCY, 2);
        BloodUnit first = bag("ONEG-1", AboGroup.O, RhFactor.NEGATIVE);
        BloodUnit second = bag("ONEG-2", AboGroup.O, RhFactor.NEGATIVE);

        service.issueUnit(request.getId(), first.getId());
        service.issueUnit(request.getId(), second.getId());

        assertThat(first.getStatus()).isEqualTo(BloodUnitStatus.ISSUED);
        assertThat(second.getStatus()).isEqualTo(BloodUnitStatus.ISSUED);
        assertThat(addedUnitAudits()).isEmpty();
    }

    @Test
    @DisplayName("a bag that came back unused does not count; one never recovered, or given, does")
    void whatCountsAgainstTheRequest() {
        TransfusionRequest request = request(TransfusionUrgency.EMERGENCY, 1);
        UUID requestId = request.getId();
        BloodUnit returned = bag("ONEG-1", AboGroup.O, RhFactor.NEGATIVE);
        returned.setRequest(request);
        returned.setStatus(BloodUnitStatus.RETURNED);
        returned.setReturnOutcome(BloodUnitReturnOutcome.FIT_FOR_STOCK);
        BloodUnit destroyed = bag("ONEG-2", AboGroup.O, RhFactor.NEGATIVE);
        destroyed.setRequest(request);
        destroyed.setStatus(BloodUnitStatus.DISCARDED);
        destroyed.setReturnOutcome(BloodUnitReturnOutcome.UNFIT_DESTROYED);
        BloodUnit replacement = bag("ONEG-3", AboGroup.O, RhFactor.NEGATIVE);

        // The replacement of bags that came back is within the request.
        service.issueUnit(requestId, replacement.getId());
        assertThat(replacement.getStatus()).isEqualTo(BloodUnitStatus.ISSUED);

        // The replacement is now given; a bag that never came back counts as well.
        replacement.setStatus(BloodUnitStatus.TRANSFUSED);
        TransfusionRequest other = request(TransfusionUrgency.EMERGENCY, 1);
        UUID otherId = other.getId();
        BloodUnit lost = bag("ONEG-4", AboGroup.O, RhFactor.NEGATIVE);
        lost.setRequest(other);
        lost.setStatus(BloodUnitStatus.DISCARDED);
        lost.setReturnOutcome(BloodUnitReturnOutcome.NOT_RECOVERED);
        BloodUnit next = bag("ONEG-5", AboGroup.O, RhFactor.NEGATIVE);
        UUID nextId = next.getId();
        BloodUnit afterGiven = bag("ONEG-6", AboGroup.O, RhFactor.NEGATIVE);
        UUID afterGivenId = afterGiven.getId();

        refusedOnTheStep(() -> service.issueUnit(otherId, nextId));
        refusedOnTheStep(() -> service.issueUnit(requestId, afterGivenId));
    }
}

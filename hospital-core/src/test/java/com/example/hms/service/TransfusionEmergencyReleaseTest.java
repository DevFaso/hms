package com.example.hms.service;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.BloodUnitStatus;
import com.example.hms.enums.EmergencyReleaseType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.RhFactor;
import com.example.hms.enums.TransfusionAdministrationStatus;
import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.enums.TransfusionUrgency;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.BloodUnit;
import com.example.hms.model.EmergencyBloodRelease;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientBloodGroup;
import com.example.hms.model.Staff;
import com.example.hms.model.TransfusionAdministration;
import com.example.hms.model.TransfusionCrossmatch;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.transfusion.CrossmatchRequestDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseOptionsDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionAdministrationRequestDTO;
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
import com.example.hms.service.impl.TransfusionServiceImpl;
import com.example.hms.service.support.PatientChartAccess;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The emergency release rules (user-supplied guidance 2026-11-06), each at the choke point that enforces it. */
@ExtendWith({MockitoExtension.class, com.example.hms.i18n.EnglishMessageBundles.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class TransfusionEmergencyReleaseTest {

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
    private final UUID director = UUID.randomUUID();
    private Hospital hospital;
    private Patient patient;
    private final List<EmergencyBloodRelease> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(hospitalId);
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setGender("FEMALE");
        patient.setDateOfBirth(LocalDate.now().minusYears(30));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(issuer);
        when(unitRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(requestRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(crossmatchRepository.save(any())).thenAnswer(call -> {
            TransfusionCrossmatch saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(releaseRepository.save(any())).thenAnswer(call -> {
            EmergencyBloodRelease row = call.getArgument(0);
            row.setId(UUID.randomUUID());
            rows.add(row);
            return row;
        });
        when(userRepository.getReferenceById(any())).thenAnswer(call -> user(call.getArgument(0)));
        when(assignmentRepository.existsActiveWitness(any(), eq(hospitalId), anySet())).thenReturn(true);
        when(assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(eq(hospitalId), anySet())).thenReturn(List.of(user(director)));
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdOrderByPerformedAtDesc(any(), any())).thenReturn(List.of());
    }

    private static User user(UUID id) {
        User user = new User();
        user.setId(id);
        user.setUsername("u-" + id);
        return user;
    }

    private void adultMale() {
        patient.setGender("MALE");
        patient.setDateOfBirth(LocalDate.now().minusYears(40));
    }

    private PatientBloodGroup typed(AboGroup abo, RhFactor rh, AntibodyScreenResult screen) {
        PatientBloodGroup group = PatientBloodGroup.builder().patient(patient).hospital(hospital).aboGroup(abo).rhFactor(rh)
            .antibodyScreen(screen).performedAt(LocalDateTime.now().minusHours(1))
            .expiresAt(LocalDateTime.now().plusHours(48)).superseded(Boolean.FALSE).build();
        group.setId(UUID.randomUUID());
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patient.getId(), hospitalId))
            .thenReturn(Optional.of(group));
        return group;
    }

    private TransfusionRequest emergency() {
        return request(TransfusionUrgency.EMERGENCY, BloodProductType.PACKED_RED_CELLS);
    }

    private TransfusionRequest request(TransfusionUrgency urgency, BloodProductType product) {
        TransfusionRequest request = TransfusionRequest.builder().patient(patient).hospital(hospital).productType(product)
            .unitsRequested(1).indication("Haemorrhage").urgency(urgency).status(TransfusionRequestStatus.REQUESTED)
            .requestedAt(LocalDateTime.now()).build();
        request.setId(UUID.randomUUID());
        when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
        return request;
    }

    private BloodUnit bag(AboGroup abo, RhFactor rh, BloodUnitStatus status) {
        return bag(abo, rh, status, BloodProductType.PACKED_RED_CELLS);
    }

    private BloodUnit bag(AboGroup abo, RhFactor rh, BloodUnitStatus status, BloodProductType product) {
        BloodUnit unit = BloodUnit.builder().hospital(hospital).unitNumber("U-" + abo + rh).productType(product)
            .aboGroup(abo).rhFactor(rh).expiresOn(LocalDate.now().plusDays(20)).status(status).build();
        unit.setId(UUID.randomUUID());
        when(unitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
        return unit;
    }

    private void noONegativeInStock() {
        when(unitRepository.existsAvailable(hospitalId, BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE,
            LocalDate.now())).thenReturn(false);
    }

    private void oNegativeInStock() {
        when(unitRepository.existsAvailable(hospitalId, BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE,
            LocalDate.now())).thenReturn(true);
    }

    private TransfusionCrossmatch resultOnFile(TransfusionRequest request, BloodUnit unit, boolean compatible) {
        TransfusionCrossmatch result = TransfusionCrossmatch.builder().request(request).bloodUnit(unit).hospital(hospital)
            .compatible(compatible).incompatibilityReason(compatible ? null : "Anti-K")
            .performedAt(LocalDateTime.now().minusMinutes(10)).expiresAt(LocalDateTime.now().plusHours(72)).build();
        result.setId(UUID.randomUUID());
        when(crossmatchRepository.findByRequest_IdAndBloodUnit_Id(request.getId(), unit.getId())).thenReturn(Optional.of(result));
        return result;
    }

    private EmergencyReleaseRequestDTO decision(BloodUnit unit) {
        return documented(unit, EmergencyReleaseType.O_POSITIVE_DECISION);
    }

    private EmergencyReleaseRequestDTO incompatibleRelease(BloodUnit unit) {
        return documented(unit, EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE);
    }

    private EmergencyReleaseRequestDTO documented(BloodUnit unit, EmergencyReleaseType type) {
        return EmergencyReleaseRequestDTO.builder().bloodUnitId(unit.getId()).releaseType(type)
            .reason("  Massive haemorrhage  ").authoriserUserId(doctor).build();
    }

    private void refused(TransfusionRequest request, BloodUnit unit, String message) {
        UUID requestId = request.getId();
        UUID unitId = unit.getId();
        assertThatThrownBy(() -> service.issueUnit(requestId, unitId))
            .isInstanceOf(BusinessException.class).hasMessageContaining(message);
        assertThat(unit.getStatus()).isNotEqualTo(BloodUnitStatus.ISSUED);
        assertThat(rows).isEmpty();
    }

    // ── A. O positive ───────────────────────────────────────────────────

    @Test
    @DisplayName("an adult male takes O negative or O positive with no extra step, and the row says uncrossmatched")
    void adultMaleTakesEitherWithNoStep() {
        adultMale();
        for (RhFactor rh : RhFactor.values()) {
            TransfusionRequest request = emergency();
            BloodUnit unit = bag(AboGroup.O, rh, BloodUnitStatus.AVAILABLE);
            service.issueUnit(request.getId(), unit.getId());
            assertThat(unit.getStatus()).as(rh.name()).isEqualTo(BloodUnitStatus.ISSUED);
        }
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.getReleaseType()).isEqualTo(EmergencyReleaseType.UNCROSSMATCHED);
            assertThat(row.getReason()).isNull();
            assertThat(row.getAuthoriser()).isNull();
            assertThat(row.getIssuedBy().getId()).isEqualTo(issuer);
        });
    }

    @Test
    @DisplayName("O positive never goes out uncrossmatched on a request that is not an emergency")
    void notOnAnOrdinaryRequest() {
        adultMale();
        refused(request(TransfusionUrgency.URGENT, BloodProductType.PACKED_RED_CELLS),
            bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE), "Only group O red cells");
    }

    @Test
    @DisplayName("plasma and platelets are not widened: an O positive bag of them is refused, decision or not")
    void notPlasmaOrPlatelets() {
        adultMale();
        for (BloodProductType product : List.of(BloodProductType.FRESH_FROZEN_PLASMA, BloodProductType.PLATELETS, BloodProductType.CRYOPRECIPITATE)) {
            TransfusionRequest request = request(TransfusionUrgency.EMERGENCY, product);
            BloodUnit unit = bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE, product);
            refused(request, unit, "Only group O red cells");
            UUID requestId = request.getId();
            EmergencyReleaseRequestDTO release = decision(unit);
            assertThatThrownBy(() -> service.emergencyRelease(requestId, release)).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("a woman: O positive is refused while O negative is available, documented or not")
    void oNegativeFirst() {
        oNegativeInStock();
        TransfusionRequest request = emergency();
        BloodUnit unit = bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE);
        refused(request, unit, "must be used first");
        UUID requestId = request.getId();
        EmergencyReleaseRequestDTO release = decision(unit);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, release)).hasMessageContaining("must be used first");
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("a woman with no O negative left: plain issue asks for the decision, the documented release goes out")
    void noONegativeTakesTheDecision() {
        noONegativeInStock();
        TransfusionRequest request = emergency();
        BloodUnit unit = bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE);
        refused(request, unit, "documented emergency decision");

        service.emergencyRelease(request.getId(), decision(unit));

        assertThat(unit.getStatus()).isEqualTo(BloodUnitStatus.ISSUED);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getReleaseType()).isEqualTo(EmergencyReleaseType.O_POSITIVE_DECISION);
            assertThat(row.getReason()).isEqualTo("Massive haemorrhage");
            assertThat(row.getAuthoriser().getId()).isEqualTo(doctor);
            assertThat(row.getIssuedBy().getId()).isEqualTo(issuer);
            assertThat(row.getIssuedAt()).isNotNull();
            assertThat(row.getBloodUnit()).isSameAs(unit);
        });
    }

    @Test
    @DisplayName("every female takes that path at any age, 70 included, and an unknown sex and a boy too")
    void noAgeLimit() {
        noONegativeInStock();
        patient.setDateOfBirth(LocalDate.now().minusYears(70));
        refused(emergency(), bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE), "documented emergency decision");
        patient.setGender(null);
        refused(emergency(), bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE), "documented emergency decision");
        patient.setGender("MALE");
        patient.setDateOfBirth(LocalDate.now().minusYears(10));
        refused(emergency(), bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE), "documented emergency decision");
    }

    @Test
    @DisplayName("an adult male typed Rh negative takes the same path, and an A negative bag stays refused")
    void typedRhNegative() {
        adultMale();
        typed(AboGroup.A, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE);
        oNegativeInStock();
        refused(emergency(), bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE), "must be used first");
        noONegativeInStock();
        TransfusionRequest request = emergency();
        BloodUnit unit = bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE);
        refused(request, unit, "documented emergency decision");
        service.emergencyRelease(request.getId(), decision(unit));
        assertThat(unit.getStatus()).isEqualTo(BloodUnitStatus.ISSUED);
    }

    @Test
    @DisplayName("a positive screen on an adult male makes the decision mandatory and does not block, and the row keeps the warning")
    void positiveScreenAsksForTheDecision() {
        adultMale();
        typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.POSITIVE);
        oNegativeInStock();
        TransfusionRequest request = emergency();
        BloodUnit unit = bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE);
        refused(request, unit, "documented emergency decision");
        service.emergencyRelease(request.getId(), decision(unit));
        assertThat(rows).singleElement().satisfies(row -> assertThat(row.getAntibodyWarning()).isTrue());
    }

    @Test
    @DisplayName("an antibody found on an earlier, superseded screen is still known")
    void aSupersededPositiveScreenStillCounts() {
        adultMale();
        PatientBloodGroup current = typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE);
        PatientBloodGroup earlier = PatientBloodGroup.builder().patient(patient).hospital(hospital).aboGroup(AboGroup.A)
            .rhFactor(RhFactor.POSITIVE).antibodyScreen(AntibodyScreenResult.POSITIVE).antibodyDetail("Anti-D")
            .performedAt(LocalDateTime.now().minusYears(2)).superseded(Boolean.TRUE).build();
        when(bloodGroupRepository.findByPatient_IdAndHospital_IdOrderByPerformedAtDesc(patient.getId(), hospitalId))
            .thenReturn(List.of(current, earlier));
        refused(emergency(), bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE), "documented emergency decision");
    }

    @Test
    @DisplayName("a decision needs a reason and a named, live person: each missing piece is refused on its field")
    void aDecisionIsDocumented() {
        noONegativeInStock();
        TransfusionRequest request = emergency();
        UUID requestId = request.getId();
        BloodUnit unit = bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE);
        EmergencyReleaseRequestDTO noReason = decision(unit);
        noReason.setReason("   ");
        EmergencyReleaseRequestDTO longReason = decision(unit);
        longReason.setReason("x".repeat(501));
        EmergencyReleaseRequestDTO noName = decision(unit);
        noName.setAuthoriserUserId(null);
        EmergencyReleaseRequestDTO uncrossmatched = documented(unit, EmergencyReleaseType.UNCROSSMATCHED);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, noReason)).isInstanceOf(FieldBusinessException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> service.emergencyRelease(requestId, longReason)).isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, noName)).isInstanceOf(FieldBusinessException.class).hasMessageContaining("Name the person");
        assertThatThrownBy(() -> service.emergencyRelease(requestId, uncrossmatched)).isInstanceOf(FieldBusinessException.class);
        when(assignmentRepository.existsActiveWitness(any(), eq(hospitalId), anySet())).thenReturn(false);
        EmergencyReleaseRequestDTO stranger = decision(unit);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, stranger)).isInstanceOf(FieldBusinessException.class).hasMessageContaining("no active role");
        assertThat(rows).isEmpty();
        assertThat(unit.getStatus()).isEqualTo(BloodUnitStatus.AVAILABLE);
    }

    @Test
    @DisplayName("the decision is for an O positive bag on an emergency request, nothing else")
    void aDecisionOnlyCoversOPositive() {
        noONegativeInStock();
        TransfusionRequest request = emergency();
        UUID requestId = request.getId();
        BloodUnit aPositive = bag(AboGroup.A, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE);
        EmergencyReleaseRequestDTO other = decision(aPositive);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, other)).hasMessageContaining("not O positive");
        TransfusionRequest urgent = request(TransfusionUrgency.URGENT, BloodProductType.PACKED_RED_CELLS);
        UUID urgentId = urgent.getId();
        EmergencyReleaseRequestDTO notEmergency = decision(bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE));
        assertThatThrownBy(() -> service.emergencyRelease(urgentId, notEmergency)).hasMessageContaining("emergency request");
    }

    // ── Hang agrees with issue ──────────────────────────────────────────

    private Staff staff(UUID userId) {
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setHospital(hospital);
        staff.setActive(true);
        staff.setUser(user(userId));
        return staff;
    }

    private void hang(TransfusionRequest request, BloodUnit unit) {
        Staff nurse = staff(UUID.randomUUID());
        Staff second = staff(UUID.randomUUID());
        when(staffRepository.findByUserIdAndHospitalId(issuer, hospitalId)).thenReturn(Optional.of(nurse));
        when(staffRepository.findById(second.getId())).thenReturn(Optional.of(second));
        when(administrationRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        service.startAdministration(TransfusionAdministrationRequestDTO.builder()
            .requestId(request.getId()).bloodUnitId(unit.getId()).verifiedByStaffId(second.getId()).build());
    }

    private void lastRowIs(TransfusionRequest request, BloodUnit unit) {
        when(releaseRepository.findFirstByRequest_IdAndBloodUnit_IdOrderByIssuedAtDesc(request.getId(), unit.getId()))
            .thenAnswer(call -> rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(rows.size() - 1)));
    }

    @Test
    @DisplayName("a documented O positive for a patient typed Rh negative is not refused at the bedside")
    void theBedsideHonoursTheDecision() {
        typed(AboGroup.A, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE);
        noONegativeInStock();
        TransfusionRequest request = emergency();
        BloodUnit unit = bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE);
        lastRowIs(request, unit);
        service.emergencyRelease(request.getId(), decision(unit));

        hang(request, unit);

        verify(administrationRepository).save(any());
    }

    @Test
    @DisplayName("with no documented release the group re-check still stops that bag at the bedside")
    void theGroupRecheckStillStopsEverythingElse() {
        adultMale();
        TransfusionRequest request = emergency();
        BloodUnit unit = bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE);
        lastRowIs(request, unit);
        service.issueUnit(request.getId(), unit.getId());
        typed(AboGroup.A, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE);

        assertThatThrownBy(() -> hang(request, unit)).hasMessageContaining("blood group excludes");
    }

    @Test
    @DisplayName("the bedside still takes two people on a documented release")
    void theTwoPersonCheckStays() {
        noONegativeInStock();
        TransfusionRequest request = emergency();
        BloodUnit unit = bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE);
        lastRowIs(request, unit);
        service.emergencyRelease(request.getId(), decision(unit));
        TransfusionAdministrationRequestDTO noSecond = TransfusionAdministrationRequestDTO.builder()
            .requestId(request.getId()).bloodUnitId(unit.getId()).build();

        assertThatThrownBy(() -> service.startAdministration(noSecond)).isInstanceOf(FieldBusinessException.class);
    }

    // ── B. Serologically incompatible ───────────────────────────────────

    @Test
    @DisplayName("ordinary issue never releases a bag with an incompatible result; the action does, and the result still reads incompatible")
    void theActionIsTheOnlyWayOutAndTheResultStays() {
        typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.POSITIVE);
        TransfusionRequest request = emergency();
        BloodUnit unit = bag(AboGroup.A, RhFactor.POSITIVE, BloodUnitStatus.RETURNED);
        TransfusionCrossmatch result = resultOnFile(request, unit, false);
        lastRowIs(request, unit);
        refused(request, unit, "came back incompatible");

        service.emergencyRelease(request.getId(), incompatibleRelease(unit));

        assertThat(unit.getStatus()).isEqualTo(BloodUnitStatus.ISSUED);
        assertThat(unit.getRequest()).isSameAs(request);
        assertThat(result.getCompatible()).isFalse();
        assertThat(result.getIncompatibilityReason()).isEqualTo("Anti-K");
        verify(crossmatchRepository, never()).save(any());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getReleaseType()).isEqualTo(EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE);
            assertThat(row.getReason()).isEqualTo("Massive haemorrhage");
            assertThat(row.getAuthoriser().getId()).isEqualTo(doctor);
            assertThat(row.getIssuedBy().getId()).isEqualTo(issuer);
            assertThat(row.getIssuedAt()).isNotNull();
            assertThat(row.getBloodUnit()).isSameAs(unit);
        });

        hang(request, unit);
        verify(administrationRepository).save(any());
    }

    @Test
    @DisplayName("two bags on one request are two records, each tied to its unit")
    void eachBagIsItsOwnRecord() {
        typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.POSITIVE);
        TransfusionRequest request = emergency();
        BloodUnit first = bag(AboGroup.A, RhFactor.POSITIVE, BloodUnitStatus.RETURNED);
        BloodUnit second = bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.RETURNED);
        resultOnFile(request, first, false);
        resultOnFile(request, second, false);

        service.emergencyRelease(request.getId(), incompatibleRelease(first));
        service.emergencyRelease(request.getId(), incompatibleRelease(second));

        assertThat(rows).extracting(EmergencyBloodRelease::getBloodUnit).containsExactly(first, second);
        verify(inBasketService, times(2)).createItemInNewTransaction(any());
    }

    @Test
    @DisplayName("an ABO exclusion stays refused by this action; so does a non-O bag for an untyped patient")
    void aboExclusionStaysRefused() {
        typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE);
        TransfusionRequest request = emergency();
        UUID requestId = request.getId();
        BloodUnit bGroup = bag(AboGroup.B, RhFactor.POSITIVE, BloodUnitStatus.RETURNED);
        resultOnFile(request, bGroup, false);
        EmergencyReleaseRequestDTO excluded = incompatibleRelease(bGroup);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, excluded)).hasMessageContaining("group excludes");
        assertThat(bGroup.getStatus()).isEqualTo(BloodUnitStatus.RETURNED);

        when(bloodGroupRepository.findByPatient_IdAndHospital_IdAndSupersededFalse(patient.getId(), hospitalId)).thenReturn(Optional.empty());
        TransfusionRequest untyped = emergency();
        UUID untypedId = untyped.getId();
        BloodUnit aGroup = bag(AboGroup.A, RhFactor.POSITIVE, BloodUnitStatus.RETURNED);
        resultOnFile(untyped, aGroup, false);
        EmergencyReleaseRequestDTO unknown = incompatibleRelease(aGroup);
        assertThatThrownBy(() -> service.emergencyRelease(untypedId, unknown)).hasMessageContaining("group excludes");
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("Rh is not widened: an A positive bag for a patient typed A negative stays refused by this action")
    void rhIsNotWidenedBeyondO() {
        typed(AboGroup.A, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE);
        TransfusionRequest request = emergency();
        UUID requestId = request.getId();
        BloodUnit bag = bag(AboGroup.A, RhFactor.POSITIVE, BloodUnitStatus.RETURNED);
        resultOnFile(request, bag, false);
        EmergencyReleaseRequestDTO release = incompatibleRelease(bag);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, release)).hasMessageContaining("group excludes");
    }

    @Test
    @DisplayName("O negative first holds for an O positive bag under this action too")
    void oNegativeFirstUnderTheAction() {
        oNegativeInStock();
        TransfusionRequest request = emergency();
        UUID requestId = request.getId();
        BloodUnit bag = bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.RETURNED);
        resultOnFile(request, bag, false);
        EmergencyReleaseRequestDTO release = incompatibleRelease(bag);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, release)).hasMessageContaining("must be used first");
    }

    @Test
    @DisplayName("the action needs an incompatible result on file, an emergency request, and red cells")
    void theActionIsNarrow() {
        typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE);
        TransfusionRequest request = emergency();
        UUID requestId = request.getId();
        BloodUnit none = bag(AboGroup.A, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE);
        EmergencyReleaseRequestDTO noResult = incompatibleRelease(none);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, noResult)).hasMessageContaining("no incompatible crossmatch");
        BloodUnit compatible = bag(AboGroup.A, RhFactor.POSITIVE, BloodUnitStatus.CROSSMATCHED);
        resultOnFile(request, compatible, true);
        EmergencyReleaseRequestDTO fine = incompatibleRelease(compatible);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, fine)).hasMessageContaining("no incompatible crossmatch");
    }

    @Test
    @DisplayName("the authoriser is a doctor or lab director here, never the issuer")
    void theAuthoriser() {
        typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE);
        TransfusionRequest request = emergency();
        UUID requestId = request.getId();
        BloodUnit unit = bag(AboGroup.A, RhFactor.POSITIVE, BloodUnitStatus.RETURNED);
        resultOnFile(request, unit, false);
        EmergencyReleaseRequestDTO self = incompatibleRelease(unit);
        self.setAuthoriserUserId(issuer);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, self)).hasMessageContaining("other than the person issuing");

        when(assignmentRepository.existsActiveWitness(eq(doctor), eq(hospitalId),
            eq(com.example.hms.service.support.EmergencyReleasePolicy.INCOMPATIBLE_AUTHORISER_ROLE_CODES))).thenReturn(false);
        EmergencyReleaseRequestDTO notEntitled = incompatibleRelease(unit);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, notEntitled)).hasMessageContaining("no active role");
        assertThat(rows).isEmpty();
        assertThat(com.example.hms.service.support.EmergencyReleasePolicy.INCOMPATIBLE_AUTHORISER_ROLE_CODES)
            .contains("ROLE_DOCTOR", "ROLE_LAB_DIRECTOR").doesNotContain("ROLE_LAB_TECHNICIAN", "ROLE_NURSE", "ROLE_SUPER_ADMIN");
    }

    @Test
    @DisplayName("every use sends each lab director an URGENT item, and a failed notification undoes nothing")
    void theDirectorIsTold() {
        typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE);
        TransfusionRequest request = emergency();
        BloodUnit unit = bag(AboGroup.A, RhFactor.POSITIVE, BloodUnitStatus.RETURNED);
        resultOnFile(request, unit, false);
        when(inBasketService.createItemInNewTransaction(any())).thenThrow(new IllegalStateException("down"));

        service.emergencyRelease(request.getId(), incompatibleRelease(unit));

        assertThat(unit.getStatus()).isEqualTo(BloodUnitStatus.ISSUED);
        ArgumentCaptor<CreateInBasketItemRequest> item = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasketService).createItemInNewTransaction(item.capture());
        assertThat(item.getValue().getRecipientUserId()).isEqualTo(director);
        assertThat(item.getValue().getPriority()).isEqualTo(InBasketPriority.URGENT);
        assertThat(item.getValue().getBodyKey()).isEqualTo(TransfusionServiceImpl.RELEASE_REVIEW_BODY_KEY);
        assertThat(item.getValue().getBody()).doesNotContain("Massive");
    }

    @Test
    @DisplayName("a decision for O positive sends no director item")
    void aDecisionSendsNoReview() {
        noONegativeInStock();
        TransfusionRequest request = emergency();
        service.emergencyRelease(request.getId(), decision(bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE)));
        verify(inBasketService, never()).createItemInNewTransaction(any());
    }

    // ── C. Audit ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the audit row names the release type by ids only, and never the reason")
    void theAuditCarriesTheType() {
        typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE);
        TransfusionRequest request = emergency();
        BloodUnit unit = bag(AboGroup.A, RhFactor.POSITIVE, BloodUnitStatus.RETURNED);
        resultOnFile(request, unit, false);

        service.emergencyRelease(request.getId(), incompatibleRelease(unit));

        ArgumentCaptor<AuditEventRequestDTO> row = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(row.capture());
        assertThat(row.getValue().getEventType()).isEqualTo(AuditEventType.EMERGENCY_BLOOD_RELEASED);
        assertThat(row.getValue().getEventDescription()).contains("serologically incompatible")
            .contains(unit.getId().toString()).contains(request.getId().toString()).doesNotContain("Massive");
    }

    // ── D. The three gaps ───────────────────────────────────────────────

    private TransfusionAdministration ended(TransfusionRequest request, BloodUnit unit, TransfusionAdministrationStatus status, UUID nurse) {
        TransfusionAdministration administration = TransfusionAdministration.builder().request(request).bloodUnit(unit)
            .patient(patient).hospital(hospital).status(status).startedAt(LocalDateTime.now().minusHours(3))
            .administeredBy(staff(nurse)).build();
        administration.setId(UUID.randomUUID());
        when(administrationRepository.findByBloodUnit_Id(unit.getId())).thenReturn(Optional.of(administration));
        return administration;
    }

    private CrossmatchRequestDTO result(BloodUnit unit, boolean compatible) {
        return CrossmatchRequestDTO.builder().bloodUnitId(unit.getId()).compatible(compatible)
            .incompatibilityReason(compatible ? null : "Anti-K").build();
    }

    @Test
    @DisplayName("gap 1: a late incompatible result on a finished or stopped bag is recorded, tells the requester and the nurse, and moves no status")
    void lateResultOnAnEndedBag() {
        for (TransfusionAdministrationStatus status : List.of(TransfusionAdministrationStatus.COMPLETED, TransfusionAdministrationStatus.STOPPED)) {
            org.mockito.Mockito.clearInvocations(inBasketService);
            UUID requester = UUID.randomUUID();
            UUID nurse = UUID.randomUUID();
            TransfusionRequest request = emergency();
            request.setStatus(TransfusionRequestStatus.COMPLETED);
            request.setRequestedBy(staff(requester));
            BloodUnitStatus terminal = status == TransfusionAdministrationStatus.COMPLETED
                ? BloodUnitStatus.TRANSFUSED : BloodUnitStatus.DISCARDED;
            BloodUnit unit = bag(AboGroup.O, RhFactor.NEGATIVE, terminal);
            unit.setExpiresOn(LocalDate.now().minusDays(1));
            ended(request, unit, status, nurse);

            assertThat(service.recordCrossmatch(request.getId(), result(unit, false)).getCompatible()).isFalse();

            assertThat(unit.getStatus()).as(status.name()).isEqualTo(terminal);
            assertThat(request.getStatus()).isEqualTo(TransfusionRequestStatus.COMPLETED);
            ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
            verify(inBasketService, times(2)).createItemInNewTransaction(items.capture());
            assertThat(items.getAllValues()).extracting(CreateInBasketItemRequest::getRecipientUserId)
                .containsExactlyInAnyOrder(requester, nurse);
            assertThat(items.getAllValues()).allSatisfy(item -> {
                assertThat(item.getPriority()).isEqualTo(InBasketPriority.URGENT);
                assertThat(item.getBodyKey()).isEqualTo(TransfusionServiceImpl.INCOMPATIBLE_FINISHED_BODY_KEY);
            });
        }
    }

    @Test
    @DisplayName("gap 1 goes no further: a compatible result on an ended bag stays refused, a closed request with no bag stays closed")
    void lateResultOpensNothingElse() {
        TransfusionRequest request = emergency();
        request.setStatus(TransfusionRequestStatus.COMPLETED);
        UUID requestId = request.getId();
        BloodUnit unit = bag(AboGroup.O, RhFactor.NEGATIVE, BloodUnitStatus.TRANSFUSED);
        ended(request, unit, TransfusionAdministrationStatus.COMPLETED, UUID.randomUUID());
        CrossmatchRequestDTO compatible = result(unit, true);
        assertThatThrownBy(() -> service.recordCrossmatch(requestId, compatible)).isInstanceOf(BusinessException.class);

        BloodUnit notHung = bag(AboGroup.O, RhFactor.NEGATIVE, BloodUnitStatus.AVAILABLE);
        CrossmatchRequestDTO elsewhere = result(notHung, false);
        assertThatThrownBy(() -> service.recordCrossmatch(requestId, elsewhere)).hasMessageContaining("closed");
        verify(crossmatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("a late result leaves a closed request on the screen it was decided on")
    void aClosedRequestKeepsItsScreen() {
        PatientBloodGroup repeat = typed(AboGroup.O, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE);
        TransfusionRequest request = emergency();
        request.setStatus(TransfusionRequestStatus.COMPLETED);
        BloodUnit unit = bag(AboGroup.O, RhFactor.NEGATIVE, BloodUnitStatus.TRANSFUSED);
        ended(request, unit, TransfusionAdministrationStatus.COMPLETED, UUID.randomUUID());

        service.recordCrossmatch(request.getId(), result(unit, false));

        assertThat(request.getBloodGroup()).isNotSameAs(repeat).isNull();
    }

    @Test
    @DisplayName("gap 2: a compatible result cannot replace an incompatible one on a running or finished bag")
    void aCompatibleResultCannotEraseIt() {
        for (TransfusionAdministrationStatus status : List.of(TransfusionAdministrationStatus.IN_PROGRESS, TransfusionAdministrationStatus.COMPLETED)) {
            typed(AboGroup.O, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE);
            TransfusionRequest request = emergency();
            UUID requestId = request.getId();
            BloodUnit unit = bag(AboGroup.O, RhFactor.NEGATIVE,
                status == TransfusionAdministrationStatus.COMPLETED ? BloodUnitStatus.TRANSFUSED : BloodUnitStatus.ISSUED);
            ended(request, unit, status, UUID.randomUUID());
            TransfusionCrossmatch earlier = resultOnFile(request, unit, false);
            CrossmatchRequestDTO overwrite = result(unit, true);

            assertThatThrownBy(() -> service.recordCrossmatch(requestId, overwrite))
                .isInstanceOf(FieldBusinessException.class).hasMessageContaining("result stands");

            assertThat(earlier.getCompatible()).isFalse();
            assertThat(earlier.getIncompatibilityReason()).isEqualTo("Anti-K");
        }
        verify(crossmatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("gap 2: the same goes for a bag out on an incompatible release, and a repeat incompatible rewrites nothing")
    void theResultIsFixedOnceReleasedKnowingIt() {
        typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE);
        TransfusionRequest request = emergency();
        UUID requestId = request.getId();
        BloodUnit unit = bag(AboGroup.A, RhFactor.POSITIVE, BloodUnitStatus.ISSUED);
        unit.setRequest(request);
        TransfusionCrossmatch earlier = resultOnFile(request, unit, false);
        when(releaseRepository.existsByRequest_IdAndBloodUnit_IdAndReleaseType(requestId, unit.getId(),
            EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE)).thenReturn(true);
        CrossmatchRequestDTO overwrite = result(unit, true);
        assertThatThrownBy(() -> service.recordCrossmatch(requestId, overwrite)).hasMessageContaining("result stands");

        CrossmatchRequestDTO repeat = CrossmatchRequestDTO.builder().bloodUnitId(unit.getId()).compatible(false)
            .incompatibilityReason("Another reason").build();
        assertThat(service.recordCrossmatch(requestId, repeat).getIncompatibilityReason()).isEqualTo("Anti-K");

        assertThat(unit.getStatus()).isEqualTo(BloodUnitStatus.ISSUED);
        assertThat(earlier.getIncompatibilityReason()).isEqualTo("Anti-K");
        verify(crossmatchRepository, never()).save(any());
        verify(inBasketService, never()).createItemInNewTransaction(any());
    }

    @Test
    @DisplayName("the laboratory may still repeat a test on a bag that was neither hung nor released knowing the result")
    void aRepeatBeforeTheBagLeavesStillWorks() {
        typed(AboGroup.O, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE);
        TransfusionRequest request = request(TransfusionUrgency.ROUTINE, BloodProductType.PACKED_RED_CELLS);
        BloodUnit unit = bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.RETURNED);
        TransfusionCrossmatch earlier = resultOnFile(request, unit, false);

        service.recordCrossmatch(request.getId(), result(unit, true));

        assertThat(earlier.getCompatible()).isTrue();
        assertThat(unit.getStatus()).isEqualTo(BloodUnitStatus.CROSSMATCHED);
    }

    @Test
    @DisplayName("gap 3: a request with no requester tells the lab directors, whether or not the bag is hanging")
    void noRequesterTellsTheDirector() {
        TransfusionRequest request = emergency();
        BloodUnit unit = bag(AboGroup.O, RhFactor.NEGATIVE, BloodUnitStatus.ISSUED);
        unit.setRequest(request);

        service.recordCrossmatch(request.getId(), result(unit, false));

        ArgumentCaptor<CreateInBasketItemRequest> item = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasketService).createItemInNewTransaction(item.capture());
        assertThat(item.getValue().getRecipientUserId()).isEqualTo(director);
        assertThat(item.getValue().getBodyKey()).isEqualTo(TransfusionServiceImpl.INCOMPATIBLE_ISSUED_BODY_KEY);
        assertThat(unit.getStatus()).isEqualTo(BloodUnitStatus.RETURNED);
    }

    // ── What the form is told ───────────────────────────────────────────

    @Test
    @DisplayName("the options hold O positive back while O negative is available, and say which bags need a decision")
    void optionsAgreeWithTheRules() {
        TransfusionRequest request = emergency();
        BloodUnit negative = bag(AboGroup.O, RhFactor.NEGATIVE, BloodUnitStatus.AVAILABLE);
        BloodUnit positive = bag(AboGroup.O, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE);
        BloodUnit aGroup = bag(AboGroup.A, RhFactor.POSITIVE, BloodUnitStatus.AVAILABLE);
        BloodUnit plasma = bag(AboGroup.O, RhFactor.NEGATIVE, BloodUnitStatus.AVAILABLE, BloodProductType.FRESH_FROZEN_PLASMA);
        when(unitRepository.findAssignable(eq(hospitalId), any())).thenReturn(List.of(negative, positive, aGroup, plasma));

        oNegativeInStock();
        EmergencyReleaseOptionsDTO first = service.emergencyReleaseOptions(request.getId());
        assertThat(first.isONegativeFirst()).isTrue();
        assertThat(first.getUnits()).extracting(EmergencyReleaseOptionsDTO.Unit::getId).containsExactly(negative.getId());

        noONegativeInStock();
        EmergencyReleaseOptionsDTO none = service.emergencyReleaseOptions(request.getId());
        assertThat(none.isONegativeFirst()).isFalse();
        assertThat(none.getUnits()).extracting(EmergencyReleaseOptionsDTO.Unit::isDecisionRequired).containsExactly(false, true);
    }

    @Test
    @DisplayName("the options list the incompatible bags and mark the one the group excludes, and never offer the issuer as authoriser")
    void optionsForTheIncompatibleAction() {
        typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.POSITIVE);
        TransfusionRequest request = emergency();
        BloodUnit same = bag(AboGroup.A, RhFactor.POSITIVE, BloodUnitStatus.RETURNED);
        BloodUnit excluded = bag(AboGroup.B, RhFactor.POSITIVE, BloodUnitStatus.RETURNED);
        List<TransfusionCrossmatch> results = List.of(resultOnFile(request, same, false), resultOnFile(request, excluded, false));
        when(crossmatchRepository.findByRequest_IdOrderByPerformedAtDesc(request.getId())).thenReturn(results);
        when(assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(eq(hospitalId), anySet()))
            .thenReturn(List.of(user(doctor), user(issuer), user(doctor)));

        EmergencyReleaseOptionsDTO options = service.emergencyReleaseOptions(request.getId());

        assertThat(options.isAntibodyWarning()).isTrue();
        assertThat(options.getIncompatibleUnits()).extracting(EmergencyReleaseOptionsDTO.IncompatibleUnit::isReleasable).containsExactly(true, false);
        assertThat(options.getAuthorisers()).extracting(com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO::userId).containsExactly(doctor);
        assertThat(options.getDeciders()).extracting(com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO::userId)
            .containsExactlyInAnyOrder(doctor, issuer);
    }
}

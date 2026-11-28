package com.example.hms.service;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.BedStatus;
import com.example.hms.enums.IsolationPrecautionType;
import com.example.hms.enums.TransferOrderStatus;
import com.example.hms.enums.TransferType;
import com.example.hms.enums.WardType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.TransferOrderMapper;
import com.example.hms.model.Admission;
import com.example.hms.model.Bed;
import com.example.hms.model.Hospital;
import com.example.hms.model.IsolationPrecaution;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.TransferOrder;
import com.example.hms.model.Ward;
import com.example.hms.payload.dto.transfer.TransferCancellationRequestDTO;
import com.example.hms.payload.dto.transfer.TransferCompletionRequestDTO;
import com.example.hms.payload.dto.transfer.TransferOrderRequestDTO;
import com.example.hms.payload.dto.transfer.TransferOrderResponseDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.BedRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.IsolationPrecautionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.TransferOrderRepository;
import com.example.hms.service.impl.TransferServiceImpl;
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

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * In-app transfer orders (Tier 2 item 30).
 *
 * <p>Three things are pinned hardest.
 *
 * <p>THE ISOLATION INTERLOCK, because moving an airborne case into an open bay
 * is the failure this and item 32 exist together to prevent. It refuses by
 * default, and an override is recorded with a reason rather than forbidden —
 * forbidding it moves the decision outside the system where nothing captures
 * it.
 *
 * <p>THE RESERVATION, because the whole value of ordering a transfer ahead of
 * carrying it out is that the destination cannot be given to somebody else in
 * the gap. A refused order must not leave a bed reserved, and a cancelled one
 * must hand it back.
 *
 * <p>ONE WRITER OF THE BED INVARIANT. Completion delegates to
 * BedAssignmentService rather than setting Bed.status itself.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferServiceImplTest {

    @Spy private Clock clock = Clock.systemDefaultZone();
    @Mock private TransferOrderRepository transferOrderRepository;
    @Mock private AdmissionRepository admissionRepository;
    @Mock private BedRepository bedRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private IsolationPrecautionRepository precautionRepository;
    @Mock private BedAssignmentService bedAssignmentService;
    @Spy private TransferOrderMapper mapper = new TransferOrderMapper();
    @Mock private RoleValidator roleValidator;

    @InjectMocks private TransferServiceImpl service;

    private UUID hospitalId;
    private Hospital hospital;
    private Patient patient;
    private Ward generalWard;
    private Ward isolationWard;
    private Bed originBed;
    private Bed destinationBed;
    private Admission admission;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);

        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Aminata");
        patient.setLastName("Diallo");

        generalWard = ward("Maternity A", "MATA", WardType.MATERNITY);
        isolationWard = ward("Isolation", "ISO", WardType.ISOLATION);
        originBed = bed(generalWard, "1", BedStatus.OCCUPIED);
        destinationBed = bed(generalWard, "2", BedStatus.AVAILABLE);

        admission = new Admission();
        admission.setId(UUID.randomUUID());
        admission.setHospital(hospital);
        admission.setPatient(patient);
        admission.setBed(originBed);
        admission.setStatus(AdmissionStatus.ACTIVE);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(admissionRepository.findById(admission.getId())).thenReturn(Optional.of(admission));
        when(bedRepository.findByIdAndWard_Hospital_Id(any(), any()))
            .thenAnswer(i -> Optional.of(destinationBed));
        when(transferOrderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transferOrderRepository.findPendingForAdmission(any())).thenReturn(Optional.empty());
        when(transferOrderRepository.findPendingForDestinationBed(any())).thenReturn(Optional.empty());
        when(precautionRepository.findActiveForPatient(any())).thenReturn(List.of());
    }

    private Ward ward(String name, String code, WardType type) {
        Ward w = Ward.builder()
            .hospital(hospital).name(name).code(code).wardType(type).active(true).build();
        w.setId(UUID.randomUUID());
        return w;
    }

    private Bed bed(Ward ward, String number, BedStatus status) {
        Bed b = Bed.builder().ward(ward).bedNumber(number).status(status).active(true).build();
        b.setId(UUID.randomUUID());
        return b;
    }

    private IsolationPrecaution precaution(IsolationPrecautionType type) {
        return IsolationPrecaution.builder()
            .hospital(hospital).patient(patient).precautionType(type)
            .reason("clinical").startedAt(LocalDateTime.now().minusDays(1)).build();
    }

    private TransferOrderRequestDTO.TransferOrderRequestDTOBuilder base() {
        return TransferOrderRequestDTO.builder()
            .admissionId(admission.getId())
            .toBedId(destinationBed.getId())
            .reason("Needs closer observation");
    }

    private TransferOrder pendingOrder(Bed destination) {
        TransferOrder order = TransferOrder.builder()
            .hospital(hospital)
            .admission(admission)
            .patient(patient)
            .fromBed(originBed)
            .fromWard(generalWard)
            .toBed(destination)
            .toWard(destination.getWard())
            .transferType(TransferType.BED_TO_BED)
            .status(TransferOrderStatus.REQUESTED)
            .reason("Needs closer observation")
            .requestedAt(LocalDateTime.now().minusHours(1))
            .build();
        order.setId(UUID.randomUUID());
        return order;
    }

    // ── Ordering ────────────────────────────────────────────────────────

    /** QA D609: NURSE_A transfers a 6-year-old girl to maternity bed MATA/M1 — refused at order time. */
    @Test
    void d609_requestTransfer_toAMaternityBedForAChild_isRefusedAndReservesNothing() {
        patient.setGender("FEMALE");
        patient.setDateOfBirth(java.time.LocalDate.now().minusYears(6));

        assertThatThrownBy(() -> service.requestTransfer(base().build()))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .satisfies(e -> org.assertj.core.api.Assertions.assertThat(
                ((com.example.hms.exception.BusinessException) e).getMessageKey()).isEqualTo("bed.ward.maternityMismatch"))
            // QA D1366 class (added assertion): named on the destination picker.
            .hasFieldOrPropertyWithValue("field", "toBedId");
        verify(bedAssignmentService, never()).reserveBed(any(), any());
        verify(transferOrderRepository, never()).save(any());
    }

    /** QA D609 path re-verified: an adult woman may still be transferred to the maternity bed. */
    @Test
    void d609_requestTransfer_toAMaternityBedForAnAdultWoman_stillReserves() {
        patient.setGender("FEMALE");
        patient.setDateOfBirth(java.time.LocalDate.now().minusYears(28));

        service.requestTransfer(base().build());

        verify(bedAssignmentService).reserveBed(destinationBed.getId(), hospitalId);
    }


    @Test
    void orderingATransferHoldsTheDestinationBed() {
        TransferOrderResponseDTO result = service.requestTransfer(base().build());

        assertThat(result.getStatus()).isEqualTo(TransferOrderStatus.REQUESTED);
        // The whole point of ordering ahead: nobody else can take the bed.
        verify(bedAssignmentService).reserveBed(destinationBed.getId(), hospitalId);
    }

    @Test
    void theOriginIsSnapshotFromTheAdmissionNotSuppliedByTheCaller() {
        TransferOrderResponseDTO result = service.requestTransfer(base().build());

        assertThat(result.getFromBedId()).isEqualTo(originBed.getId());
        assertThat(result.getFromWardName()).isEqualTo("Maternity A");
    }

    @Test
    void aMoveWithinOneWardIsBedToBed() {
        assertThat(service.requestTransfer(base().build()).getTransferType())
            .isEqualTo(TransferType.BED_TO_BED);
    }

    @Test
    void aMoveToAnotherWardIsWardToWard() {
        destinationBed = bed(isolationWard, "9", BedStatus.AVAILABLE);

        assertThat(service.requestTransfer(base().toBedId(destinationBed.getId()).build())
            .getTransferType()).isEqualTo(TransferType.WARD_TO_WARD);
    }

    @Test
    void aTransferNeedsAReason() {
        TransferOrderRequestDTO request = base().reason("  ").build();

        assertThatThrownBy(() -> service.requestTransfer(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("transfer.r7.reasonRequired"));
        verify(bedAssignmentService, never()).reserveBed(any(), any());
    }

    @Test
    void aPatientCannotBeOnTheirWayToTwoPlacesAtOnce() {
        when(transferOrderRepository.findPendingForAdmission(admission.getId()))
            .thenReturn(Optional.of(pendingOrder(destinationBed)));
        TransferOrderRequestDTO request = base().build();

        assertThatThrownBy(() -> service.requestTransfer(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("transfer.r7.alreadyPending"));
        verify(bedAssignmentService, never()).reserveBed(any(), any());
    }

    @Test
    void twoTransfersCannotRaceForTheSameDestination() {
        // The second patient would arrive to find the bed taken.
        when(transferOrderRepository.findPendingForDestinationBed(destinationBed.getId()))
            .thenReturn(Optional.of(pendingOrder(destinationBed)));
        TransferOrderRequestDTO request = base().build();

        assertThatThrownBy(() -> service.requestTransfer(request))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((com.example.hms.exception.BusinessException) e).getMessageKey()).isEqualTo("transfer.r7.destinationTaken"))
            // QA D1366 class (added assertion): named on the destination picker.
            .hasFieldOrPropertyWithValue("field", "toBedId");
    }

    @Test
    void movingAPatientIntoTheBedTheyAreAlreadyInIsRefused() {
        when(bedRepository.findByIdAndWard_Hospital_Id(any(), any()))
            .thenReturn(Optional.of(originBed));
        TransferOrderRequestDTO request = base().toBedId(originBed.getId()).build();

        assertThatThrownBy(() -> service.requestTransfer(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("transfer.r7.alreadyInBed"))
            // QA D1352 (round 30): on the destination bed the form offers.
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "toBedId");
    }

    @Test
    void aDischargedAdmissionHasNoBedToTransfer() {
        admission.setStatus(AdmissionStatus.DISCHARGED);
        TransferOrderRequestDTO request = base().build();

        assertThatThrownBy(() -> service.requestTransfer(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("transfer.r7.noBedHeld"));
    }

    // ── The isolation interlock ─────────────────────────────────────────

    @Test
    void anAirborneCaseCannotBeMovedIntoAWardThatCannotContainIt() {
        // The failure this and item 32 exist together to prevent.
        when(precautionRepository.findActiveForPatient(patient.getId()))
            .thenReturn(List.of(precaution(IsolationPrecautionType.AIRBORNE)));
        TransferOrderRequestDTO request = base().build();

        assertThatThrownBy(() -> service.requestTransfer(request))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((com.example.hms.exception.BusinessException) e).getMessageKey()).isEqualTo("transfer.r7.isolationRequired"))
            // The refusal must NAME the bed: it travels as the message argument
            // (QA D424), so the bundle in the reader's language places it.
            .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((com.example.hms.exception.BusinessException) e).getArgs())
                .containsExactly(BedAssignmentService.bedLabel(destinationBed)))
            .hasMessageNotContaining("%s");
        // A refused order must not strand the bed it was going to use.
        verify(bedAssignmentService, never()).reserveBed(any(), any());
    }

    @Test
    void anAirborneCaseMovesFreelyIntoAnIsolationWard() {
        destinationBed = bed(isolationWard, "9", BedStatus.AVAILABLE);
        when(precautionRepository.findActiveForPatient(patient.getId()))
            .thenReturn(List.of(precaution(IsolationPrecautionType.AIRBORNE)));

        TransferOrderResponseDTO result =
            service.requestTransfer(base().toBedId(destinationBed.getId()).build());

        assertThat(result.isIsolationOverride()).isFalse();
        assertThat(result.isDestinationIsolationMismatch()).isFalse();
    }

    @Test
    void contactPrecautionsDoNotConstrainTheDestination() {
        // Managed with barrier technique at the bedside, not by moving wards.
        when(precautionRepository.findActiveForPatient(patient.getId()))
            .thenReturn(List.of(precaution(IsolationPrecautionType.CONTACT)));

        TransferOrderResponseDTO result = service.requestTransfer(base().build());

        assertThat(result.getIsolationPrecautions())
            .containsExactly(IsolationPrecautionType.CONTACT);
        assertThat(result.isIsolationOverride()).isFalse();
    }

    @Test
    void protectiveIsolationDoesNotConstrainTheDestinationEither() {
        // It shields the PATIENT from the ward. Treating it like airborne
        // would push a neutropenic patient toward the infectious cases.
        when(precautionRepository.findActiveForPatient(patient.getId()))
            .thenReturn(List.of(precaution(IsolationPrecautionType.PROTECTIVE)));

        assertThat(service.requestTransfer(base().build()).isIsolationOverride()).isFalse();
    }

    @Test
    void theIsolationRequirementCanBeOverriddenWithAReasonAndTheReasonIsKept() {
        // Refusing outright would move the decision outside the system, where
        // nothing records that it was ever made.
        when(precautionRepository.findActiveForPatient(patient.getId()))
            .thenReturn(List.of(precaution(IsolationPrecautionType.AIRBORNE)));

        TransferOrderResponseDTO result = service.requestTransfer(base()
            .isolationOverride(true)
            .isolationOverrideReason("No isolation bed free; patient needs theatre")
            .build());

        assertThat(result.isIsolationOverride()).isTrue();
        assertThat(result.getIsolationOverrideReason())
            .isEqualTo("No isolation bed free; patient needs theatre");
        assertThat(result.isDestinationIsolationMismatch()).isTrue();
    }

    @Test
    void anIsolationOverrideRefusesShortReasonsWithoutReservingTheBed() {
        when(precautionRepository.findActiveForPatient(patient.getId()))
            .thenReturn(List.of(precaution(IsolationPrecautionType.AIRBORNE)));
        for (String reason : List.of("ok", "          ", "123456789")) {
            TransferOrderRequestDTO request = base().isolationOverride(true).isolationOverrideReason(reason).build();
            assertThatThrownBy(() -> service.requestTransfer(request)).isInstanceOf(BusinessException.class);
        }
        verify(bedAssignmentService, never()).reserveBed(any(), any());
    }

    @Test
    void anOverrideWithoutAReasonIsRefused() {
        when(precautionRepository.findActiveForPatient(patient.getId()))
            .thenReturn(List.of(precaution(IsolationPrecautionType.AIRBORNE)));
        TransferOrderRequestDTO request = base().isolationOverride(true).build();

        assertThatThrownBy(() -> service.requestTransfer(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("transfer.r7.overrideReasonRequired"));
    }

    // ── QA D2917 (r66): the midwife's ward rule holds on a transfer as on a bed assignment ──

    private static final UUID MIDWIFE = UUID.randomUUID();

    private com.example.hms.service.InBasketService inBasket;

    /** The caller is a midwife of this hospital and nothing wider there; the real rule decides. */
    private void asAMidwifeOfThisHospital() {
        inBasket = mock(com.example.hms.service.InBasketService.class);
        service.setMidwifeNewbornCare(new com.example.hms.service.maternity.MidwifeNewbornCare(
            mock(com.example.hms.repository.DeliveryNewbornRepository.class), bedRepository,
            roleValidator, inBasket,
            mock(com.example.hms.repository.UserRoleHospitalAssignmentRepository.class)));
        when(roleValidator.getCurrentUserId()).thenReturn(MIDWIFE);
        when(roleValidator.isMidwife(MIDWIFE, hospitalId)).thenReturn(true);
    }

    private void refusedOnHerWards(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.getMessageKey()).isEqualTo("admission.bed.midwifeWards"));
    }

    @Test
    void d2917_aMidwifeOrdersNoTransferIntoABedOfAnotherWard_andNothingIsHeld() {
        // MIDWIFE_A: POST /transfers for a stay in general bed M-01 to general bed M-02 answered 201.
        Ward medicine = ward("Médecine", "MED", WardType.GENERAL);
        admission.setBed(bed(medicine, "M-01", BedStatus.OCCUPIED));
        destinationBed = bed(medicine, "M-02", BedStatus.AVAILABLE);
        asAMidwifeOfThisHospital();

        assertThatThrownBy(() -> service.requestTransfer(base().build()))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class, e -> {
                assertThat(e.getMessageKey()).isEqualTo("admission.bed.midwifeWards");
                assertThat(e.getField()).isEqualTo(BedAssignmentService.FIELD_TO_BED_ID);
            });
        // Out of a maternity bed into a general one, and out of a general one into maternity: neither.
        admission.setBed(originBed);
        refusedOnHerWards(() -> service.requestTransfer(base().build()));
        admission.setBed(bed(medicine, "M-03", BedStatus.OCCUPIED));
        destinationBed = bed(generalWard, "3", BedStatus.AVAILABLE);
        refusedOnHerWards(() -> service.requestTransfer(base().build()));

        verify(bedAssignmentService, never()).reserveBed(any(), any());
        verify(transferOrderRepository, never()).save(any());
    }

    @Test
    void d2917_aMidwifeCarriesOutNoTransferIntoABedOfAnotherWard_whoeverOrderedIt() {
        // A nurse ordered it; POST /transfers/{id}/complete by the midwife answered 200.
        Ward medicine = ward("Médecine", "MED", WardType.GENERAL);
        destinationBed = bed(medicine, "M-02", BedStatus.RESERVED);
        TransferOrder order = pendingOrder(destinationBed);
        when(transferOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        asAMidwifeOfThisHospital();

        refusedOnHerWards(() -> service.completeTransfer(order.getId(), TransferCompletionRequestDTO.builder().build()));

        assertThat(order.getStatus()).isEqualTo(TransferOrderStatus.REQUESTED);
        verify(bedAssignmentService, never()).assignBed(any(), any(), any());
        verify(admissionRepository, never()).save(any());
    }

    /** The order was placed by this staff member of the hospital. */
    private UUID orderedBy(TransferOrder order) {
        UUID requester = UUID.randomUUID();
        com.example.hms.model.User user = new com.example.hms.model.User();
        user.setId(requester);
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(user);
        order.setRequestedBy(staff);
        return requester;
    }

    @Test
    void review1251_aMidwifeReceivesInMaternityAPatientADoctorOrANurseSentFromAnotherWard() {
        // A woman in labour in an emergency bed; a nurse (then a doctor) ordered her move to maternity.
        Ward emergency = ward("Urgences", "URG", WardType.EMERGENCY);
        for (String orderer : new String[] {"nurse", "doctor"}) {
            admission.setBed(bed(emergency, "U-01", BedStatus.OCCUPIED));
            destinationBed = bed(generalWard, "4", BedStatus.RESERVED);
            TransferOrder order = pendingOrder(destinationBed);
            UUID requester = orderedBy(order);
            when(roleValidator.isNurse(requester, hospitalId)).thenReturn("nurse".equals(orderer));
            when(roleValidator.isDoctor(requester, hospitalId)).thenReturn("doctor".equals(orderer));
            when(transferOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
            asAMidwifeOfThisHospital();

            TransferOrderResponseDTO done =
                service.completeTransfer(order.getId(), TransferCompletionRequestDTO.builder().build());

            assertThat(done.getStatus()).as(orderer).isEqualTo(TransferOrderStatus.COMPLETED);
            verify(bedAssignmentService).assignBed(admission, destinationBed.getId(),
                Set.of(BedStatus.AVAILABLE, BedStatus.RESERVED));
        }
    }

    @Test
    void review1251_theSourceWardIsOpenOnlyWhenADoctorOrANurseOrderedTheMove() {
        Ward emergency = ward("Urgences", "URG", WardType.EMERGENCY);
        admission.setBed(bed(emergency, "U-01", BedStatus.OCCUPIED));
        destinationBed = bed(generalWard, "4", BedStatus.RESERVED);
        asAMidwifeOfThisHospital();

        // Nobody recorded as having ordered it.
        TransferOrder unsigned = pendingOrder(destinationBed);
        when(transferOrderRepository.findById(unsigned.getId())).thenReturn(Optional.of(unsigned));
        refusedOnHerWards(() -> service.completeTransfer(unsigned.getId(), TransferCompletionRequestDTO.builder().build()));

        // Ordered by another midwife (neither a nurse nor a physician of the hospital).
        TransferOrder aColleagues = pendingOrder(destinationBed);
        UUID colleague = orderedBy(aColleagues);
        when(roleValidator.isMidwife(colleague, hospitalId)).thenReturn(true);
        when(transferOrderRepository.findById(aColleagues.getId())).thenReturn(Optional.of(aColleagues));
        refusedOnHerWards(() -> service.completeTransfer(aColleagues.getId(), TransferCompletionRequestDTO.builder().build()));

        verify(bedAssignmentService, never()).assignBed(any(), any(), any());
    }

    @Test
    void review1251_theDestinationStaysOneOfHerWardsWhoeverOrderedTheMove_andSheStillOrdersNoneOutOfAnotherWard() {
        Ward emergency = ward("Urgences", "URG", WardType.EMERGENCY);
        Ward medicine = ward("Médecine", "MED", WardType.GENERAL);
        admission.setBed(bed(emergency, "U-01", BedStatus.OCCUPIED));
        asAMidwifeOfThisHospital();

        // A doctor ordered a move into a general bed: not hers to carry out.
        destinationBed = bed(medicine, "M-02", BedStatus.RESERVED);
        TransferOrder toMedicine = pendingOrder(destinationBed);
        when(roleValidator.isDoctor(orderedBy(toMedicine), hospitalId)).thenReturn(true);
        when(transferOrderRepository.findById(toMedicine.getId())).thenReturn(Optional.of(toMedicine));
        refusedOnHerWards(() -> service.completeTransfer(toMedicine.getId(), TransferCompletionRequestDTO.builder().build()));
        verify(bedAssignmentService, never()).assignBed(any(), any(), any());

        // Ordering the move from the emergency bed into maternity herself: still refused.
        destinationBed = bed(generalWard, "5", BedStatus.AVAILABLE);
        refusedOnHerWards(() -> service.requestTransfer(base().build()));
        verify(bedAssignmentService, never()).reserveBed(any(), any());
    }

    @Test
    void d2917_aMidwifeStillMovesHerPatientBetweenMaternityBeds() {
        // setUp: the stay is in maternity bed 1, the destination is maternity bed 2.
        asAMidwifeOfThisHospital();

        service.requestTransfer(base().build());
        verify(bedAssignmentService).reserveBed(destinationBed.getId(), hospitalId);

        TransferOrder order = pendingOrder(destinationBed);
        when(transferOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        service.completeTransfer(order.getId(), TransferCompletionRequestDTO.builder().build());
        verify(bedAssignmentService).assignBed(admission, destinationBed.getId(),
            Set.of(BedStatus.AVAILABLE, BedStatus.RESERVED));
        // A maternity bed is no neonatal cot: nobody is asked to review.
        verifyNoInteractions(inBasket);
    }

    @Test
    void d2917_aNeonatalCotSheGivesANewbornByTransferAsksAPhysicianToReview() {
        // The rule's one exception, as on a direct assignment: allowed, and a physician is asked.
        Ward neonatology = ward("Néonatologie", "NEO", WardType.NICU);
        destinationBed = bed(neonatology, "N1", BedStatus.RESERVED);
        admission.setAdmissionType(com.example.hms.enums.AdmissionType.NEWBORN);
        TransferOrder order = pendingOrder(destinationBed);
        when(transferOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        asAMidwifeOfThisHospital();

        service.completeTransfer(order.getId(), TransferCompletionRequestDTO.builder().build());

        verify(bedAssignmentService).assignBed(admission, destinationBed.getId(),
            Set.of(BedStatus.AVAILABLE, BedStatus.RESERVED));
        verify(inBasket).removeAllItemsForReference(admission.getId(),
            com.example.hms.service.maternity.MidwifeNewbornCare.REFERENCE_TYPE);

        // The same cot for an adult stay is not hers to give.
        admission.setAdmissionType(com.example.hms.enums.AdmissionType.ELECTIVE);
        TransferOrder adult = pendingOrder(destinationBed);
        when(transferOrderRepository.findById(adult.getId())).thenReturn(Optional.of(adult));
        refusedOnHerWards(() -> service.completeTransfer(adult.getId(), TransferCompletionRequestDTO.builder().build()));
    }

    @Test
    void d2917_aNurseOfTheHospitalKeepsEveryWard_evenIfSheIsAlsoAMidwifeThere() {
        Ward medicine = ward("Médecine", "MED", WardType.GENERAL);
        admission.setBed(bed(medicine, "M-01", BedStatus.OCCUPIED));
        destinationBed = bed(medicine, "M-02", BedStatus.AVAILABLE);
        asAMidwifeOfThisHospital();
        when(roleValidator.isNurse(MIDWIFE, hospitalId)).thenReturn(true);

        service.requestTransfer(base().build());

        verify(bedAssignmentService).reserveBed(destinationBed.getId(), hospitalId);
    }

    // ── Completion ──────────────────────────────────────────────────────

    @Test
    void completingATransferMovesThePatientThroughBedAssignmentService() {
        // One writer of the Admission.bed <-> Bed.status invariant.
        TransferOrder order = pendingOrder(destinationBed);
        when(transferOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        TransferOrderResponseDTO result = service.completeTransfer(order.getId(),
            TransferCompletionRequestDTO.builder().build());

        assertThat(result.getStatus()).isEqualTo(TransferOrderStatus.COMPLETED);
        assertThat(result.getCompletedAt()).isNotNull();
        verify(bedAssignmentService).assignBed(admission, destinationBed.getId(),
            Set.of(BedStatus.AVAILABLE, BedStatus.RESERVED));
    }

    @Test
    void aTransferCannotBeCompletedTwice() {
        TransferOrder order = pendingOrder(destinationBed);
        order.setStatus(TransferOrderStatus.COMPLETED);
        when(transferOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        UUID orderId = order.getId();
        TransferCompletionRequestDTO request = TransferCompletionRequestDTO.builder().build();

        assertThatThrownBy(() -> service.completeTransfer(orderId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("transfer.r7.notPending"));
    }

    // ── Cancellation ────────────────────────────────────────────────────

    @Test
    void cancellingATransferHandsTheBedBack() {
        TransferOrder order = pendingOrder(destinationBed);
        when(transferOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        TransferOrderResponseDTO result = service.cancelTransfer(order.getId(),
            TransferCancellationRequestDTO.builder()
                .cancellationReason("Patient improved, staying put").build());

        assertThat(result.getStatus()).isEqualTo(TransferOrderStatus.CANCELLED);
        assertThat(result.getCancellationReason()).isEqualTo("Patient improved, staying put");
        verify(bedAssignmentService).releaseReservation(destinationBed);
    }

    @Test
    void cancellingInAColleaguesNameIsRefusedAndKeepsTheBed_D503() {
        TransferOrder order = pendingOrder(destinationBed);
        when(transferOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        com.example.hms.model.Staff colleague = new com.example.hms.model.Staff();
        colleague.setId(UUID.randomUUID());
        colleague.setHospital(order.getHospital());
        com.example.hms.model.User colleagueUser = new com.example.hms.model.User();
        colleagueUser.setId(UUID.randomUUID());
        colleague.setUser(colleagueUser);
        when(staffRepository.findById(colleague.getId())).thenReturn(Optional.of(colleague));
        UUID orderId = order.getId();
        TransferCancellationRequestDTO request = TransferCancellationRequestDTO.builder()
            .cancellationReason("Patient improved").cancelledByStaffId(colleague.getId()).build();

        assertThatThrownBy(() -> service.cancelTransfer(orderId, request))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(order.getStatus()).isNotEqualTo(TransferOrderStatus.CANCELLED);
        verify(bedAssignmentService, never()).releaseReservation(any());
    }

    @Test
    void cancellingNeedsAReasonBecauseTheBedWasHeldOutOfCirculation() {
        TransferOrder order = pendingOrder(destinationBed);
        when(transferOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        UUID orderId = order.getId();
        TransferCancellationRequestDTO blank =
            TransferCancellationRequestDTO.builder().cancellationReason("  ").build();

        assertThatThrownBy(() -> service.cancelTransfer(orderId, blank))
            .isInstanceOf(BusinessException.class)
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("transfer.r7.heldBedReasonRequired"));
        verify(bedAssignmentService, never()).releaseReservation(any());
    }

    @Test
    void anAlreadyCancelledTransferCannotBeCancelledAgain() {
        TransferOrder order = pendingOrder(destinationBed);
        order.setStatus(TransferOrderStatus.CANCELLED);
        when(transferOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        UUID orderId = order.getId();
        TransferCancellationRequestDTO request =
            TransferCancellationRequestDTO.builder().cancellationReason("Again").build();

        assertThatThrownBy(() -> service.cancelTransfer(orderId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("transfer.r7.notPending"));
    }

    // ── Reads and tenancy ───────────────────────────────────────────────

    @Test
    void theWorklistIsEverythingStillWaitingToBeCarriedOut() {
        when(transferOrderRepository.findByHospitalAndStatus(hospitalId, TransferOrderStatus.REQUESTED))
            .thenReturn(List.of(pendingOrder(destinationBed)));

        List<TransferOrderResponseDTO> pending = service.getPendingTransfers();

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getStatus()).isEqualTo(TransferOrderStatus.REQUESTED);
    }

    @Test
    void theWorklistCarriesPrecautionsSoAPorterSeesThemWithoutOpeningTheChart() {
        when(transferOrderRepository.findByHospitalAndStatus(hospitalId, TransferOrderStatus.REQUESTED))
            .thenReturn(List.of(pendingOrder(destinationBed)));
        when(precautionRepository.findActiveForPatient(patient.getId()))
            .thenReturn(List.of(precaution(IsolationPrecautionType.CONTACT)));

        assertThat(service.getPendingTransfers().get(0).getIsolationPrecautions())
            .containsExactly(IsolationPrecautionType.CONTACT);
    }

    @Test
    void anOrderAtAnotherHospitalIsNotFoundRatherThanForbidden() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        TransferOrder foreign = TransferOrder.builder().hospital(other).build();
        UUID id = UUID.randomUUID();
        foreign.setId(id);
        when(transferOrderRepository.findById(id)).thenReturn(Optional.of(foreign));
        TransferCancellationRequestDTO request =
            TransferCancellationRequestDTO.builder().cancellationReason("Not mine").build();

        assertThatThrownBy(() -> service.cancelTransfer(id, request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anAdmissionAtAnotherHospitalIsNotFoundRatherThanForbidden() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        admission.setHospital(other);
        TransferOrderRequestDTO request = base().build();

        assertThatThrownBy(() -> service.requestTransfer(request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aSuperAdminWithNoActiveHospitalCannotOrderATransfer() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        TransferOrderRequestDTO request = base().build();

        assertThatThrownBy(() -> service.requestTransfer(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("transfer.r7.hospitalRequired"));
    }

    @Test
    void aStaffMemberFromAnotherHospitalCannotBeNamedAsRequesting() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        Staff foreign = new Staff();
        foreign.setId(UUID.randomUUID());
        foreign.setHospital(other);
        when(staffRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        TransferOrderRequestDTO request = base().requestedByStaffId(foreign.getId()).build();

        assertThatThrownBy(() -> service.requestTransfer(request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── QA D713: the server records who requested and who completed ──────

    private Staff signInAsStaffHere() {
        com.example.hms.model.User user = new com.example.hms.model.User();
        user.setId(UUID.randomUUID());
        Staff me = new Staff();
        me.setId(UUID.randomUUID());
        me.setHospital(hospital);
        me.setUser(user);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user, null, List.of()));
        when(staffRepository.findByUserIdAndHospitalId(user.getId(), hospitalId)).thenReturn(Optional.of(me));
        return me;
    }

    @org.junit.jupiter.api.AfterEach
    void clearSession() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void d713_aTransferOrderedFromTheBedBoardRecordsTheCallerAsRequester() {
        // bed-board.ts sends no requestedByStaffId: the row was anonymous.
        Staff me = signInAsStaffHere();

        service.requestTransfer(base().build());

        org.mockito.ArgumentCaptor<TransferOrder> saved = org.mockito.ArgumentCaptor.forClass(TransferOrder.class);
        verify(transferOrderRepository).save(saved.capture());
        assertThat(saved.getValue().getRequestedBy()).isEqualTo(me);
    }

    @Test
    void d713_completingATransferRecordsTheCallerAsCompleter() {
        Staff me = signInAsStaffHere();
        TransferOrder order = pendingOrder(destinationBed);
        when(transferOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        service.completeTransfer(order.getId(), TransferCompletionRequestDTO.builder().build());

        assertThat(order.getCompletedBy()).isEqualTo(me);
    }

    @Test
    void d713_completingInAColleaguesNameIsRefused() {
        signInAsStaffHere();
        TransferOrder order = pendingOrder(destinationBed);
        when(transferOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        Staff colleague = new Staff();
        colleague.setId(UUID.randomUUID());
        colleague.setHospital(hospital);
        com.example.hms.model.User colleagueUser = new com.example.hms.model.User();
        colleagueUser.setId(UUID.randomUUID());
        colleague.setUser(colleagueUser);
        when(staffRepository.findById(colleague.getId())).thenReturn(Optional.of(colleague));
        UUID orderId = order.getId();
        TransferCompletionRequestDTO request = TransferCompletionRequestDTO.builder()
            .completedByStaffId(colleague.getId()).build();

        assertThatThrownBy(() -> service.completeTransfer(orderId, request))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        verify(bedAssignmentService, never()).assignBed(any(), any(), any());
    }

    // ---- QA D3308 (r70): a woman in active labour leaves a maternity ward only knowingly ----

    private com.example.hms.repository.LaborEpisodeRepository inLabour(boolean active) {
        com.example.hms.repository.LaborEpisodeRepository episodes =
            mock(com.example.hms.repository.LaborEpisodeRepository.class);
        when(episodes.existsByPatient_IdAndHospital_IdAndStatus(patient.getId(), hospitalId,
            com.example.hms.enums.LaborStatus.ACTIVE)).thenReturn(active);
        service.setLabourBedMove(new com.example.hms.service.maternity.LabourBedMove(episodes));
        return episodes;
    }

    @Test
    void d3308_orderingALabouringWomanOutOfMaternityNeedsTheAcknowledgementAndHoldsNothingWithoutIt() {
        inLabour(true);
        Ward surgery = ward("Chirurgie", "CHI", WardType.SURGICAL);
        destinationBed = bed(surgery, "C-01", BedStatus.AVAILABLE);
        TransferOrderRequestDTO silent = base().toBedId(destinationBed.getId()).build();

        assertThatThrownBy(() -> service.requestTransfer(silent))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                ex -> assertThat(ex.getField()).isEqualTo("laborAcknowledged"))
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("transfer.labor.acknowledgeRequired"));
        verify(bedAssignmentService, never()).reserveBed(any(), any());

        TransferOrderResponseDTO ordered = service.requestTransfer(
            base().toBedId(destinationBed.getId()).laborAcknowledged(true).build());
        assertThat(ordered.getStatus()).isEqualTo(TransferOrderStatus.REQUESTED);
        // The worklist says it: whoever carries the move out is warned too.
        assertThat(ordered.isLaborAcknowledgementRequired()).isTrue();
        verify(bedAssignmentService).reserveBed(destinationBed.getId(), hospitalId);
    }

    @Test
    void d3308_aMoveBetweenMaternityBedsAndAWomanNotInLabourAskNothing() {
        com.example.hms.repository.LaborEpisodeRepository episodes = inLabour(true);
        // Labour room to delivery room: her ordinary care.
        assertThat(service.requestTransfer(base().build()).isLaborAcknowledgementRequired()).isFalse();
        verifyNoInteractions(episodes);

        inLabour(false);
        Ward surgery = ward("Chirurgie", "CHI", WardType.SURGICAL);
        destinationBed = bed(surgery, "C-01", BedStatus.AVAILABLE);
        assertThat(service.requestTransfer(base().toBedId(destinationBed.getId()).build())
            .isLaborAcknowledgementRequired()).isFalse();
    }

    @Test
    void d3308_carryingTheMoveOutNeedsTheAcknowledgementToo() {
        inLabour(true);
        Ward surgery = ward("Chirurgie", "CHI", WardType.SURGICAL);
        destinationBed = bed(surgery, "C-01", BedStatus.AVAILABLE);
        TransferOrder order = pendingOrder(destinationBed);
        when(transferOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        UUID orderId = order.getId();
        TransferCompletionRequestDTO silent = TransferCompletionRequestDTO.builder().build();

        assertThatThrownBy(() -> service.completeTransfer(orderId, silent))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                ex -> assertThat(ex.getField()).isEqualTo("laborAcknowledged"));
        verify(bedAssignmentService, never()).assignBed(any(), any(), any());

        assertThat(service.completeTransfer(orderId, TransferCompletionRequestDTO.builder().laborAcknowledged(true).build())
            .getStatus()).isEqualTo(TransferOrderStatus.COMPLETED);
    }

    @Test
    void d3308_aDirectBedChangeOutOfMaternityIsSentToTheTransferOrder() {
        com.example.hms.repository.LaborEpisodeRepository episodes =
            mock(com.example.hms.repository.LaborEpisodeRepository.class);
        when(episodes.existsByPatient_IdAndHospital_IdAndStatus(patient.getId(), hospitalId,
            com.example.hms.enums.LaborStatus.ACTIVE)).thenReturn(true);
        com.example.hms.service.maternity.LabourBedMove rule = new com.example.hms.service.maternity.LabourBedMove(episodes);
        Bed surgical = bed(ward("Chirurgie", "CHI", WardType.SURGICAL), "C-01", BedStatus.AVAILABLE);

        assertThatThrownBy(() -> rule.requireTransferOrderForABedChange(admission, surgical, hospitalId, "bedId"))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                ex -> assertThat(ex.getField()).isEqualTo("bedId"))
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("transfer.labor.useTransferOrder"));
        // Her first bed is an admission, not a move; another maternity bed asks nothing.
        admission.setBed(null);
        rule.requireTransferOrderForABedChange(admission, surgical, hospitalId, "bedId");
        admission.setBed(originBed);
        rule.requireTransferOrderForABedChange(admission, destinationBed, hospitalId, "bedId");
    }
}

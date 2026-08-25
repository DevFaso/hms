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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
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
            .hasMessageContaining("reason is required");
        verify(bedAssignmentService, never()).reserveBed(any(), any());
    }

    @Test
    void aPatientCannotBeOnTheirWayToTwoPlacesAtOnce() {
        when(transferOrderRepository.findPendingForAdmission(admission.getId()))
            .thenReturn(Optional.of(pendingOrder(destinationBed)));
        TransferOrderRequestDTO request = base().build();

        assertThatThrownBy(() -> service.requestTransfer(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already has a transfer");
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
            .hasMessageContaining("already on its way");
    }

    @Test
    void movingAPatientIntoTheBedTheyAreAlreadyInIsRefused() {
        when(bedRepository.findByIdAndWard_Hospital_Id(any(), any()))
            .thenReturn(Optional.of(originBed));
        TransferOrderRequestDTO request = base().toBedId(originBed.getId()).build();

        assertThatThrownBy(() -> service.requestTransfer(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already in that bed");
    }

    @Test
    void aDischargedAdmissionHasNoBedToTransfer() {
        admission.setStatus(AdmissionStatus.DISCHARGED);
        TransferOrderRequestDTO request = base().build();

        assertThatThrownBy(() -> service.requestTransfer(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not holding a bed");
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
            .hasMessageContaining("airborne precautions");
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
    void anOverrideWithoutAReasonIsRefused() {
        when(precautionRepository.findActiveForPatient(patient.getId()))
            .thenReturn(List.of(precaution(IsolationPrecautionType.AIRBORNE)));
        TransferOrderRequestDTO request = base().isolationOverride(true).build();

        assertThatThrownBy(() -> service.requestTransfer(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("needs a reason");
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
        verify(bedAssignmentService).assignBed(eq(admission), eq(destinationBed.getId()),
            eq(Set.of(BedStatus.AVAILABLE, BedStatus.RESERVED)));
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
            .hasMessageContaining("no longer waiting");
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
    void cancellingNeedsAReasonBecauseTheBedWasHeldOutOfCirculation() {
        TransferOrder order = pendingOrder(destinationBed);
        when(transferOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        UUID orderId = order.getId();
        TransferCancellationRequestDTO blank =
            TransferCancellationRequestDTO.builder().cancellationReason("  ").build();

        assertThatThrownBy(() -> service.cancelTransfer(orderId, blank))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reason is required");
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
            .hasMessageContaining("no longer waiting");
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
            .hasMessageContaining("active hospital is required");
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
}

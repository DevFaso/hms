package com.example.hms.service.impl;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.BedStatus;
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
import com.example.hms.service.BedAssignmentService;
import com.example.hms.service.TransferService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * In-app transfer orders (Tier 2 item 30).
 *
 * <p>See {@link TransferService}. Two behaviours are worth reading closely.
 *
 * <p><b>The destination is reserved, not merely noted.</b> Between ordering a
 * transfer and carrying it out there is a gap of minutes or hours, and in that
 * gap a ward clerk with a free-bed list will allocate the bed to somebody
 * else. Holding it as RESERVED is what makes ordering a transfer ahead of time
 * mean anything.
 *
 * <p><b>The isolation check refuses by default and records an override.</b> An
 * airborne case must not routinely move into a ward that cannot contain it.
 * But a clinician may have a real reason, and refusing outright would push
 * that decision outside the system, where nothing captures it at all — so the
 * override exists, demands a reason, and is kept on the order.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TransferServiceImpl implements TransferService {

    private static final String MSG_NOT_FOUND = "transfer.order.notFound";

    /** Admission states that hold a bed, and can therefore be moved. */
    private static final Set<AdmissionStatus> TRANSFERABLE_STATUSES = Set.of(
        AdmissionStatus.PENDING, AdmissionStatus.ACTIVE,
        AdmissionStatus.ON_LEAVE, AdmissionStatus.AWAITING_DISCHARGE);

    private final Clock clock;
    private final TransferOrderRepository transferOrderRepository;
    private final AdmissionRepository admissionRepository;
    private final BedRepository bedRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final IsolationPrecautionRepository precautionRepository;
    private final BedAssignmentService bedAssignmentService;
    private final TransferOrderMapper mapper;
    private final RoleValidator roleValidator;

    @Override
    public TransferOrderResponseDTO requestTransfer(TransferOrderRequestDTO request) {
        UUID hospitalId = requireHospital();

        if (!StringUtils.hasText(request.getReason())) {
            throw new BusinessException("A reason is required to move a patient.");
        }
        Admission admission = loadAdmissionScoped(request.getAdmissionId(), hospitalId);

        if (!TRANSFERABLE_STATUSES.contains(admission.getStatus())) {
            throw new BusinessException(
                "This admission is not holding a bed, so there is nothing to transfer.");
        }
        transferOrderRepository.findPendingForAdmission(admission.getId())
            .ifPresent(existing -> {
                throw new BusinessException(
                    "This patient already has a transfer waiting to be carried out. "
                        + "Complete or cancel it first.");
            });

        Bed destination = bedRepository.findByIdAndWard_Hospital_Id(request.getToBedId(), hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("bed.notFound", request.getToBedId()));

        Bed origin = admission.getBed();
        if (origin != null && Objects.equals(origin.getId(), destination.getId())) {
            throw new BusinessException("The patient is already in that bed.");
        }
        transferOrderRepository.findPendingForDestinationBed(destination.getId())
            .ifPresent(existing -> {
                throw new BusinessException(
                    "Another transfer is already on its way to bed %s."
                        .formatted(BedAssignmentService.bedLabel(destination)));
            });

        List<IsolationPrecaution> precautions =
            precautionRepository.findActiveForPatient(admission.getPatient().getId());
        boolean override = evaluateIsolation(precautions, destination, request);

        // Hold the bed. Everything above must pass first — a refused transfer
        // that had already reserved the bed would strand it.
        bedAssignmentService.reserveBed(destination.getId(), hospitalId);

        Ward originWard = origin != null ? origin.getWard() : null;
        TransferOrder order = TransferOrder.builder()
            .hospital(hospitalRef(hospitalId))
            .admission(admission)
            .patient(admission.getPatient())
            .fromBed(origin)
            .fromWard(originWard)
            .toBed(destination)
            .toWard(destination.getWard())
            .transferType(classify(originWard, destination.getWard()))
            .status(TransferOrderStatus.REQUESTED)
            .reason(request.getReason())
            .notes(request.getNotes())
            .requestedBy(resolveStaff(request.getRequestedByStaffId(), hospitalId))
            .requestedAt(LocalDateTime.now(clock))
            .isolationOverride(override)
            .isolationOverrideReason(override ? request.getIsolationOverrideReason() : null)
            .build();

        TransferOrder saved = transferOrderRepository.save(order);
        if (override) {
            log.warn("ISOLATION OVERRIDE on transfer {} — patient {} moving to ward {} which cannot "
                    + "contain their airborne precaution. Reason: {}",
                saved.getId(), admission.getPatient().getId(),
                destination.getWard() != null ? destination.getWard().getId() : null,
                request.getIsolationOverrideReason());
        }
        return mapper.toDto(saved, precautions);
    }

    @Override
    public TransferOrderResponseDTO completeTransfer(UUID orderId,
                                                     TransferCompletionRequestDTO request) {
        TransferOrder order = loadScoped(orderId);
        if (!order.isPending()) {
            throw new BusinessException("This transfer is no longer waiting to be carried out.");
        }
        UUID hospitalId = order.getHospital().getId();

        // The destination has been RESERVED for this order since it was
        // raised, so completion claims it from that state. The bed change
        // still goes through BedAssignmentService: one writer of the invariant.
        bedAssignmentService.assignBed(order.getAdmission(), order.getToBed().getId(),
            Set.of(BedStatus.AVAILABLE, BedStatus.RESERVED));
        admissionRepository.save(order.getAdmission());

        order.setStatus(TransferOrderStatus.COMPLETED);
        order.setCompletedAt(LocalDateTime.now(clock));
        order.setCompletedBy(resolveStaff(request.getCompletedByStaffId(), hospitalId));
        if (StringUtils.hasText(request.getNotes())) {
            order.setNotes(request.getNotes());
        }

        TransferOrder saved = transferOrderRepository.save(order);
        return mapper.toDto(saved, activePrecautions(saved));
    }

    @Override
    public TransferOrderResponseDTO cancelTransfer(UUID orderId,
                                                   TransferCancellationRequestDTO request) {
        if (!StringUtils.hasText(request.getCancellationReason())) {
            throw new BusinessException(
                "A reason is required: the destination bed has been held out of circulation.");
        }
        TransferOrder order = loadScoped(orderId);
        if (!order.isPending()) {
            throw new BusinessException("This transfer is no longer waiting to be carried out.");
        }

        // Hand the bed back. releaseReservation only touches a RESERVED bed,
        // so if somebody has since been put in it this cannot evict them.
        bedAssignmentService.releaseReservation(order.getToBed());

        order.setStatus(TransferOrderStatus.CANCELLED);
        order.setCancelledAt(LocalDateTime.now(clock));
        order.setCancellationReason(request.getCancellationReason());
        order.setCancelledBy(
            resolveStaff(request.getCancelledByStaffId(), order.getHospital().getId()));

        TransferOrder saved = transferOrderRepository.save(order);
        return mapper.toDto(saved, activePrecautions(saved));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransferOrderResponseDTO> getPendingTransfers() {
        UUID hospitalId = requireHospital();
        return transferOrderRepository
            .findByHospitalAndStatus(hospitalId, TransferOrderStatus.REQUESTED).stream()
            .map(order -> mapper.toDto(order, activePrecautions(order)))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransferOrderResponseDTO> getHistoryForAdmission(UUID admissionId) {
        UUID hospitalId = requireHospital();
        loadAdmissionScoped(admissionId, hospitalId);
        return transferOrderRepository.findHistoryForAdmission(admissionId).stream()
            .map(order -> mapper.toDto(order, activePrecautions(order)))
            .toList();
    }

    // ── The isolation interlock ─────────────────────────────────────────

    /**
     * Refuse a move into a ward that cannot contain an active airborne
     * precaution, unless the caller overrides with a reason.
     *
     * @return whether this order is an override
     */
    private boolean evaluateIsolation(List<IsolationPrecaution> precautions,
                                      Bed destination,
                                      TransferOrderRequestDTO request) {
        boolean needsIsolationWard = precautions.stream()
            .anyMatch(IsolationPrecaution::requiresIsolationWard);
        if (!needsIsolationWard || isIsolationCapable(destination.getWard())) {
            return false;
        }
        if (!request.isIsolationOverride()) {
            throw new BusinessException(
                "This patient is on airborne precautions and bed %s is not in an isolation ward. "
                    + "Move them to an isolation ward, or override with a reason."
                    .formatted(BedAssignmentService.bedLabel(destination)));
        }
        if (!StringUtils.hasText(request.getIsolationOverrideReason())) {
            throw new BusinessException(
                "Overriding the isolation requirement needs a reason — it is the only record "
                    + "of why the patient was moved somewhere that cannot contain them.");
        }
        return true;
    }

    private boolean isIsolationCapable(Ward ward) {
        return ward != null && ward.getWardType() == WardType.ISOLATION;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private List<IsolationPrecaution> activePrecautions(TransferOrder order) {
        if (order.getPatient() == null) {
            return List.of();
        }
        return precautionRepository.findActiveForPatient(order.getPatient().getId());
    }

    /** Same ward is a bay move; a different ward changes who is responsible. */
    private TransferType classify(Ward from, Ward to) {
        if (from == null || to == null || !Objects.equals(from.getId(), to.getId())) {
            return TransferType.WARD_TO_WARD;
        }
        return TransferType.BED_TO_BED;
    }

    /** 404-not-403 — another hospital's order reads as a missing one. */
    private TransferOrder loadScoped(UUID orderId) {
        TransferOrder order = transferOrderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND, orderId));
        UUID scope = roleValidator.requireActiveHospitalId();
        if (scope != null && order.getHospital() != null
            && !scope.equals(order.getHospital().getId())) {
            throw new ResourceNotFoundException(MSG_NOT_FOUND, orderId);
        }
        return order;
    }

    private Admission loadAdmissionScoped(UUID admissionId, UUID hospitalId) {
        Admission admission = admissionRepository.findById(admissionId)
            .orElseThrow(() -> new ResourceNotFoundException("admission.notFound", admissionId));
        if (admission.getHospital() == null
            || !Objects.equals(admission.getHospital().getId(), hospitalId)) {
            throw new ResourceNotFoundException("admission.notFound", admissionId);
        }
        return admission;
    }

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException(
                "An active hospital is required: a transfer happens inside a building.");
        }
        return hospitalId;
    }

    private Hospital hospitalRef(UUID hospitalId) {
        return hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", hospitalId));
    }

    private Staff resolveStaff(UUID staffId, UUID hospitalId) {
        if (staffId == null) {
            return null;
        }
        return staffRepository.findById(staffId)
            .filter(s -> s.getHospital() == null || Objects.equals(s.getHospital().getId(), hospitalId))
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", staffId));
    }
}

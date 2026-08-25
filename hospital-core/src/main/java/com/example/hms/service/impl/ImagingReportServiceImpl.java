package com.example.hms.service.impl;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ImagingReportMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.ImagingReportStatusHistory;
import com.example.hms.model.Organization;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingReportStatusUpdateRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportUpsertRequestDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.ImagingReportService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ImagingReportServiceImpl implements ImagingReportService {

    /**
     * Message KEYS, not prose. {@code ResourceNotFoundException}'s first
     * argument is resolved through {@code MessageUtil.resolve}, which falls
     * back to {@code "[Missing translation] " + key} — so the prose strings
     * this class used to pass surfaced to users verbatim with that prefix
     * attached, the same defect PR #490 fixed across the chart reads.
     */
    private static final String MSG_REPORT_NOT_FOUND = "imaging.report.notFound";
    private static final String MSG_ORDER_NOT_FOUND = "imaging.order.notFound";

    private static final String SIGNATURE_ALGORITHM = "SHA-256";

    /**
     * The statuses an author may assert on the content endpoints.
     *
     * <p>FINAL is absent deliberately: it is reachable only through
     * {@link #signReport}. Leaving it assertable would reproduce the
     * TRANSMITTED hole PR #463 closed on prescriptions, where three comments
     * claimed a ceremony was "the only path" to a consumable state while a
     * client-supplied status walked straight past it. CANCELLED and ERROR are
     * absent for the mirror reason — they are administrative outcomes that
     * belong to {@link #updateReportStatus}, which records a reason.
     */
    private static final Set<ImagingReportStatus> AUTHORABLE_STATUSES = Set.of(
        ImagingReportStatus.DRAFT,
        ImagingReportStatus.PRELIMINARY,
        ImagingReportStatus.ADDENDUM,
        ImagingReportStatus.CORRECTED,
        ImagingReportStatus.AMENDED);

    /** The statuses that supersede an already-signed read. */
    private static final Set<ImagingReportStatus> REVISION_STATUSES = Set.of(
        ImagingReportStatus.ADDENDUM,
        ImagingReportStatus.CORRECTED,
        ImagingReportStatus.AMENDED);

    /** Administrative outcomes {@link #updateReportStatus} may write. */
    private static final Set<ImagingReportStatus> ADMINISTRATIVE_STATUSES = Set.of(
        ImagingReportStatus.CANCELLED,
        ImagingReportStatus.ERROR);

    private final ImagingReportRepository imagingReportRepository;
    private final ImagingOrderRepository imagingOrderRepository;
    private final DepartmentRepository departmentRepository;
    private final StaffRepository staffRepository;
    private final ImagingReportMapper imagingReportMapper;
    private final RoleValidator roleValidator;

    // ── Authoring ────────────────────────────────────────────────────────

    @Override
    public ImagingReportResponseDTO createReport(ImagingReportUpsertRequestDTO request) {
        if (request == null) {
            throw new BusinessException("Imaging report payload is required.");
        }
        if (request.getImagingOrderId() == null) {
            throw new BusinessException("Imaging order ID is required to create a report.");
        }

        ImagingOrder imagingOrder = loadOrderScoped(request.getImagingOrderId());
        Hospital hospital = imagingOrder.getHospital();
        if (hospital == null) {
            throw new BusinessException("Imaging order has no hospital context; a report cannot be filed against it.");
        }

        ImagingReport report = new ImagingReport();
        report.setImagingOrder(imagingOrder);
        report.setHospital(hospital);
        report.setOrganization(resolveOrganization(hospital));
        report.setDepartment(resolveDepartment(request.getDepartmentId(), hospital));

        applyStaffAssociations(report, request, hospital);
        imagingReportMapper.updateReportFromRequest(report, request);
        applyAuthoredStatus(report, request, imagingOrder, true);
        applyCriticalFlag(report, request);

        enforceUniqueReportNumber(report.getReportNumber(), hospital.getId(), null);
        assignVersioningForCreate(report, imagingOrder.getId());

        UUID actor = roleValidator.getCurrentUserId();
        report.setCreatedBy(actor);
        report.setUpdatedBy(actor);

        ImagingReport saved = imagingReportRepository.save(report);
        appendStatusHistory(saved, saved.getReportStatus(), "Report authored", currentStaff(hospital), null);
        return imagingReportMapper.toResponseDTO(imagingReportRepository.save(saved));
    }

    @Override
    public ImagingReportResponseDTO updateReport(UUID reportId, ImagingReportUpsertRequestDTO request) {
        if (request == null) {
            throw new BusinessException("Update request payload is required.");
        }

        ImagingReport report = loadReportScoped(reportId);

        // Signing closes the report. Revisions are new versions, which is what
        // report_version / is_latest_version have modelled since V1 — editing
        // the signed row in place would rewrite what a clinician attested to.
        if (report.isSigned()) {
            throw new BusinessException(
                "This report has been signed and can no longer be edited. File an addendum or a "
                    + "corrected report against the same imaging order instead.");
        }

        Hospital hospital = report.getHospital();
        ImagingOrder imagingOrder = report.getImagingOrder();
        if (imagingOrder == null) {
            throw new BusinessException("Imaging report must remain associated with an imaging order.");
        }
        if (request.getImagingOrderId() != null
            && !Objects.equals(imagingOrder.getId(), request.getImagingOrderId())) {
            // Re-pointing a report at a different order would move it between
            // patients. A report belongs to the study it interprets.
            throw new BusinessException("An imaging report cannot be moved to a different imaging order.");
        }

        if (request.getDepartmentId() != null) {
            report.setDepartment(resolveDepartment(request.getDepartmentId(), hospital));
        }
        applyStaffAssociations(report, request, hospital);
        imagingReportMapper.updateReportFromRequest(report, request);
        applyAuthoredStatus(report, request, imagingOrder, false);
        applyCriticalFlag(report, request);

        enforceUniqueReportNumber(report.getReportNumber(), hospital != null ? hospital.getId() : null, report.getId());
        report.setUpdatedBy(roleValidator.getCurrentUserId());

        return imagingReportMapper.toResponseDTO(imagingReportRepository.save(report));
    }

    // ── Ceremonies ───────────────────────────────────────────────────────

    @Override
    public ImagingReportResponseDTO signReport(UUID reportId) {
        ImagingReport report = loadReportScoped(reportId);

        if (report.isSigned()) {
            throw new BusinessException(
                "This report has already been signed. A signature cannot be reissued; file an "
                    + "addendum or a corrected report instead.");
        }
        // A signature over an empty read attests to nothing. The impression is
        // the part the ordering clinician acts on, so that is the floor.
        if (!StringUtils.hasText(report.getImpression())) {
            throw new BusinessException("A report cannot be signed without an impression.");
        }

        Hospital hospital = report.getHospital();
        Staff signer = currentStaff(hospital);
        if (signer == null) {
            throw new AccessDeniedException(
                "Only a clinician with a staff profile at this hospital can sign an imaging report.");
        }

        LocalDateTime signedAt = LocalDateTime.now();
        report.setSignedBy(signer);
        report.setSignedAt(signedAt);
        if (report.getInterpretedAt() == null) {
            report.setInterpretedAt(signedAt);
        }
        if (report.getInterpretingProvider() == null) {
            report.setInterpretingProvider(signer);
        }
        report.setReportStatus(ImagingReportStatus.FINAL);
        report.setLockedForEditing(Boolean.TRUE);
        report.setLockReason("Signed by " + displayName(signer));
        report.setSignatureAlgorithm(SIGNATURE_ALGORITHM);
        report.setSignatureValue(computeSignatureDigest(canonicalSignaturePayload(report, signedAt)));
        report.setUpdatedBy(roleValidator.getCurrentUserId());

        appendStatusHistory(report, ImagingReportStatus.FINAL, "Report signed", signer, null);

        // Close the loop for the ordering clinician: a signed read is what
        // makes results available. Without this the order sits at COMPLETED
        // forever and the requester has no signal that the read landed.
        promoteOrderToResultsAvailable(report.getImagingOrder());

        return imagingReportMapper.toResponseDTO(imagingReportRepository.save(report));
    }

    @Override
    public ImagingReportResponseDTO acknowledgeCriticalResult(UUID reportId) {
        ImagingReport report = loadReportScoped(reportId);

        if (!report.isCriticalFlagged()) {
            throw new BusinessException(
                "This report carries no critical finding to acknowledge.");
        }
        if (report.isCriticalAcknowledged()) {
            throw new BusinessException("This critical finding has already been acknowledged by %s."
                .formatted(displayName(report.getCriticalResultAcknowledgedBy())));
        }

        Staff acknowledger = currentStaff(report.getHospital());
        if (acknowledger == null) {
            throw new AccessDeniedException(
                "Only a clinician with a staff profile at this hospital can acknowledge a critical finding.");
        }

        report.setCriticalResultAcknowledgedBy(acknowledger);
        report.setCriticalResultAcknowledgedAt(LocalDateTime.now());
        report.setUpdatedBy(roleValidator.getCurrentUserId());

        appendStatusHistory(report, report.getReportStatus(), "Critical finding acknowledged", acknowledger, null);

        return imagingReportMapper.toResponseDTO(imagingReportRepository.save(report));
    }

    @Override
    public ImagingReportResponseDTO updateReportStatus(UUID reportId, ImagingReportStatusUpdateRequestDTO request) {
        if (request == null || request.getStatus() == null) {
            throw new BusinessException("Status update payload with status is required.");
        }
        if (!ADMINISTRATIVE_STATUSES.contains(request.getStatus())) {
            throw new BusinessException(
                "%s is not an administrative outcome. Content states are set by authoring the report, and "
                    + "FINAL only by signing it.".formatted(request.getStatus()));
        }
        if (!StringUtils.hasText(request.getStatusReason())) {
            // Voiding a radiology report without saying why leaves the chart
            // with a hole and no account of it.
            throw new BusinessException("A reason is required when cancelling or voiding a report.");
        }

        ImagingReport report = loadReportScoped(reportId);
        if (report.isSigned()) {
            throw new BusinessException(
                "A signed report cannot be voided. Supersede it with a corrected report against the "
                    + "same imaging order.");
        }

        Staff changedBy = currentStaff(report.getHospital());
        report.setReportStatus(request.getStatus());
        report.setLastStatusSyncedAt(LocalDateTime.now());
        report.setUpdatedBy(roleValidator.getCurrentUserId());

        appendStatusHistory(report, request.getStatus(), request.getStatusReason(), changedBy, request.getNotes());

        return imagingReportMapper.toResponseDTO(imagingReportRepository.save(report));
    }

    // ── Reads ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public ImagingReportResponseDTO getReport(UUID reportId) {
        return imagingReportMapper.toResponseDTO(loadReportScoped(reportId));
    }

    @Override
    @Transactional(readOnly = true)
    public ImagingReportResponseDTO getLatestReportForOrder(UUID imagingOrderId) {
        loadOrderScoped(imagingOrderId);
        ImagingReport report = imagingReportRepository.findFirstByImagingOrder_IdAndLatestVersionIsTrue(imagingOrderId)
            .orElseGet(() -> imagingReportRepository.findTopByImagingOrder_IdOrderByReportVersionDesc(imagingOrderId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_REPORT_NOT_FOUND, imagingOrderId)));
        return imagingReportMapper.toResponseDTO(report);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImagingReportResponseDTO> getReportsForOrder(UUID imagingOrderId) {
        loadOrderScoped(imagingOrderId);
        return imagingReportRepository.findByImagingOrder_IdOrderByReportVersionDesc(imagingOrderId).stream()
            .map(imagingReportMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImagingReportResponseDTO> getReportsByHospitalAndStatus(UUID hospitalId, ImagingReportStatus status) {
        if (status == null) {
            throw new BusinessException("Status filter is required to list imaging reports.");
        }
        return imagingReportRepository
            .findByHospital_IdAndReportStatusOrderByPerformedAtDesc(requireReadableHospital(hospitalId), status).stream()
            .map(imagingReportMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImagingReportResponseDTO> getReportsByHospitalAndModality(UUID hospitalId, ImagingModality modality) {
        if (modality == null) {
            throw new BusinessException("Modality filter is required to list imaging reports.");
        }
        return imagingReportRepository
            .findByHospital_IdAndModalityOrderByPerformedAtDesc(requireReadableHospital(hospitalId), modality).stream()
            .map(imagingReportMapper::toResponseDTO)
            .toList();
    }

    // ── Tenancy ──────────────────────────────────────────────────────────

    /**
     * 404-not-403: a report belonging to another hospital must be
     * indistinguishable from one that does not exist. Before this, every read
     * and write here was a bare {@code findById} — so any authenticated caller
     * could pull a foreign hospital's radiology report, findings and all, by
     * UUID, and could rewrite its status. That is the same hole PR #483 closed
     * on lab acknowledge and critical read-back.
     */
    private ImagingReport loadReportScoped(UUID reportId) {
        ImagingReport report = imagingReportRepository.findById(reportId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_REPORT_NOT_FOUND, reportId));
        UUID scope = roleValidator.requireActiveHospitalId();
        if (scope != null
            && report.getHospital() != null
            && !scope.equals(report.getHospital().getId())) {
            throw new ResourceNotFoundException(MSG_REPORT_NOT_FOUND, reportId);
        }
        return report;
    }

    private ImagingOrder loadOrderScoped(UUID imagingOrderId) {
        ImagingOrder order = imagingOrderRepository.findById(imagingOrderId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_ORDER_NOT_FOUND, imagingOrderId));
        UUID scope = roleValidator.requireActiveHospitalId();
        if (scope != null
            && order.getHospital() != null
            && !scope.equals(order.getHospital().getId())) {
            throw new ResourceNotFoundException(MSG_ORDER_NOT_FOUND, imagingOrderId);
        }
        return order;
    }

    /**
     * The hospital id arrives as a path variable on the listing endpoints, so
     * on its own it is a caller-supplied claim: passing any hospital's UUID
     * returned that hospital's reports. Pin it to the caller's active scope;
     * a super-admin (null scope) may still name one explicitly.
     */
    private UUID requireReadableHospital(UUID requestedHospitalId) {
        UUID scope = roleValidator.requireActiveHospitalId();
        if (scope == null) {
            if (requestedHospitalId == null) {
                throw new BusinessException("Hospital ID is required to list imaging reports.");
            }
            return requestedHospitalId;
        }
        if (requestedHospitalId != null && !scope.equals(requestedHospitalId)) {
            throw new AccessDeniedException("Imaging reports can only be listed for your active hospital.");
        }
        return scope;
    }

    // ── Content helpers ──────────────────────────────────────────────────

    /**
     * A status the author asked for, validated against what an author may
     * assert, and against whether the order already carries a signed read.
     */
    private void applyAuthoredStatus(ImagingReport report,
                                     ImagingReportUpsertRequestDTO request,
                                     ImagingOrder imagingOrder,
                                     boolean creating) {
        ImagingReportStatus requested = request.getReportStatus();
        if (requested == null) {
            if (creating && report.getReportStatus() == null) {
                report.setReportStatus(ImagingReportStatus.PRELIMINARY);
            }
            return;
        }
        if (!AUTHORABLE_STATUSES.contains(requested)) {
            throw new BusinessException(
                ("%s cannot be set by authoring a report. FINAL is reached only by signing; "
                    + "CANCELLED and ERROR only through the status endpoint, which records a reason.")
                    .formatted(requested));
        }
        if (creating
            && !REVISION_STATUSES.contains(requested)
            && hasSignedReport(imagingOrder.getId())) {
            throw new BusinessException(
                "This study already has a signed report. A further report must be filed as an "
                    + "ADDENDUM, CORRECTED or AMENDED version so the record shows that the read "
                    + "changed after sign-off.");
        }
        report.setReportStatus(requested);
    }

    /**
     * Set-only, mirroring {@code requiresCosign} on prescriptions and notes: a
     * critical flag can be raised but not quietly withdrawn. Lowering it would
     * erase the evidence that someone was meant to be called — and item 27
     * hangs the notification sweep off exactly this timestamp.
     */
    private void applyCriticalFlag(ImagingReport report, ImagingReportUpsertRequestDTO request) {
        if (!Boolean.TRUE.equals(request.getCriticalFinding())) {
            return;
        }
        if (report.getCriticalResultFlaggedAt() == null) {
            report.setCriticalResultFlaggedAt(LocalDateTime.now());
        }
    }

    private boolean hasSignedReport(UUID imagingOrderId) {
        return imagingReportRepository.findByImagingOrder_IdOrderByReportVersionDesc(imagingOrderId).stream()
            .anyMatch(ImagingReport::isSigned);
    }

    private void promoteOrderToResultsAvailable(ImagingOrder order) {
        if (order == null) {
            return;
        }
        ImagingOrderStatus status = order.getStatus();
        // Never walk a terminal order backwards into an active state.
        if (status == ImagingOrderStatus.CANCELLED || status == ImagingOrderStatus.RESULTS_AVAILABLE) {
            return;
        }
        order.setStatus(ImagingOrderStatus.RESULTS_AVAILABLE);
        order.setStatusUpdatedAt(LocalDateTime.now());
        order.setStatusUpdatedBy(roleValidator.getCurrentUserId());
        imagingOrderRepository.save(order);
    }

    private Organization resolveOrganization(Hospital hospital) {
        return hospital != null ? hospital.getOrganization() : null;
    }

    private Department resolveDepartment(UUID departmentId, Hospital hospital) {
        if (departmentId == null) {
            return null;
        }
        Department department = departmentRepository.findById(departmentId)
            .orElseThrow(() -> new ResourceNotFoundException("department.notFound", departmentId));
        if (hospital != null && department.getHospital() != null
            && !Objects.equals(department.getHospital().getId(), hospital.getId())) {
            throw new BusinessException("Department must belong to the same hospital as the imaging report.");
        }
        return department;
    }

    private Staff resolveStaff(UUID staffId, Hospital hospital) {
        if (staffId == null) {
            return null;
        }
        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", staffId));
        if (hospital != null && staff.getHospital() != null
            && !Objects.equals(staff.getHospital().getId(), hospital.getId())) {
            throw new BusinessException("Staff must belong to the same hospital context as the imaging report.");
        }
        return staff;
    }

    /**
     * Only the two <em>attribution</em> associations are client-settable: who
     * acquired the study and who is responsible for the read. The signer and
     * the critical-finding acknowledger are stamped by their ceremonies from
     * the authenticated caller and are not reachable from a request body.
     */
    private void applyStaffAssociations(ImagingReport report,
                                        ImagingReportUpsertRequestDTO request,
                                        Hospital hospital) {
        if (request.getPerformedByStaffId() != null) {
            report.setPerformedBy(resolveStaff(request.getPerformedByStaffId(), hospital));
        }
        if (request.getInterpretingProviderId() != null) {
            report.setInterpretingProvider(resolveStaff(request.getInterpretingProviderId(), hospital));
        } else if (report.getInterpretingProvider() == null) {
            report.setInterpretingProvider(currentStaff(hospital));
        }
    }

    /** The authenticated caller's staff profile AT THIS HOSPITAL, or null. */
    private Staff currentStaff(Hospital hospital) {
        UUID userId = roleValidator.getCurrentUserId();
        UUID hospitalId = hospital != null ? hospital.getId() : null;
        if (userId == null || hospitalId == null) {
            return null;
        }
        // Resolved at the REPORT's hospital, not "first staff row anywhere" —
        // the gap PR #473 fixed for note co-signing rather than copying.
        return staffRepository.findByUserIdAndHospitalId(userId, hospitalId).orElse(null);
    }

    private void assignVersioningForCreate(ImagingReport report, UUID imagingOrderId) {
        int nextVersion = imagingReportRepository.findTopByImagingOrder_IdOrderByReportVersionDesc(imagingOrderId)
            .map(existing -> Optional.ofNullable(existing.getReportVersion()).orElse(0) + 1)
            .orElse(1);
        report.setReportVersion(nextVersion);
        report.setLatestVersion(Boolean.TRUE);
        markExistingLatestAsHistorical(imagingOrderId, null);
    }

    private void markExistingLatestAsHistorical(UUID imagingOrderId, UUID excludeReportId) {
        imagingReportRepository.findFirstByImagingOrder_IdAndLatestVersionIsTrue(imagingOrderId)
            .filter(existing -> excludeReportId == null || !Objects.equals(existing.getId(), excludeReportId))
            .ifPresent(existing -> {
                existing.setLatestVersion(false);
                imagingReportRepository.save(existing);
            });
    }

    private void enforceUniqueReportNumber(String reportNumber, UUID hospitalId, UUID currentReportId) {
        if (!StringUtils.hasText(reportNumber) || hospitalId == null) {
            return;
        }
        imagingReportRepository.findByReportNumberAndHospital_Id(reportNumber, hospitalId)
            .filter(existing -> currentReportId == null || !Objects.equals(existing.getId(), currentReportId))
            .ifPresent(existing -> {
                throw new BusinessException("Report number %s already exists for this hospital.".formatted(reportNumber));
            });
    }

    private void appendStatusHistory(ImagingReport report,
                                     ImagingReportStatus status,
                                     String statusReason,
                                     Staff changedBy,
                                     String notes) {
        if (status == null) {
            return;
        }
        if (report.getStatusHistory() == null) {
            report.setStatusHistory(new ArrayList<>());
        }
        ImagingReportStatusHistory history = new ImagingReportStatusHistory();
        history.setImagingReport(report);
        history.setImagingOrder(report.getImagingOrder());
        history.setStatus(status);
        history.setStatusReason(statusReason);
        history.setChangedBy(changedBy);
        history.setNotes(notes);
        history.setChangedByName(displayName(changedBy));
        history.setChangedAt(LocalDateTime.now());

        report.getStatusHistory().add(history);
    }

    private String displayName(Staff staff) {
        if (staff == null) {
            return null;
        }
        if (StringUtils.hasText(staff.getFullName())) {
            return staff.getFullName();
        }
        return staff.getName();
    }

    // ── Signature ────────────────────────────────────────────────────────

    /**
     * The content the signature attests to. Field order and separator are part
     * of the contract; nulls are rendered explicitly so that a missing and an
     * empty section cannot collide into the same digest.
     */
    private String canonicalSignaturePayload(ImagingReport report, LocalDateTime signedAt) {
        return String.join("|",
            String.valueOf(report.getId()),
            String.valueOf(report.getImagingOrder() != null ? report.getImagingOrder().getId() : null),
            String.valueOf(report.getHospital() != null ? report.getHospital().getId() : null),
            String.valueOf(report.getReportVersion()),
            String.valueOf(report.getModality()),
            String.valueOf(report.getBodyRegion()),
            String.valueOf(report.getTechnique()),
            String.valueOf(report.getComparisonStudies()),
            String.valueOf(report.getFindings()),
            String.valueOf(report.getImpression()),
            String.valueOf(report.getRecommendations()),
            String.valueOf(report.getCriticalResultFlaggedAt()),
            String.valueOf(report.getSignedBy() != null ? report.getSignedBy().getId() : null),
            String.valueOf(signedAt));
    }

    private String computeSignatureDigest(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SIGNATURE_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

package com.example.hms.service.imaging;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.ImagingStudyImage;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingStudyImageDTO;
import com.example.hms.payload.dto.imaging.ImagingStudyImageWithdrawalDTO;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.ImagingStudyImageRepository;
import com.example.hms.security.ClinicalActor;
import com.example.hms.security.SecurityUtils;
import com.example.hms.security.audit.ActingAssignments;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.FileUploadService;
import com.example.hms.service.PatientSubjectReadGuard;
import com.example.hms.service.PatientSubjectReaderRoles;
import com.example.hms.service.support.CancelledOrders;
import com.example.hms.storage.StoredObject;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Key images on an imaging study (owner 2026-11-19, V316): a technician's or
 * radiologist's JPEG / PNG / WebP / exported PDF on the order or on one of
 * its reports, so the radiologist does not read blind and the prescriber
 * sees more than text.
 *
 * <p><b>Reading follows the report exactly</b> ({@code ImagingReportServiceImpl}):
 * <ul>
 *   <li>staff read their hospital's studies; another hospital's answers 404,
 *       as a missing id does;</li>
 *   <li>an administrator with no clinical imaging role reads released images
 *       only (user decision 2026-11-14);</li>
 *   <li>a patient (and staff reading their own study elsewhere, #754's rule)
 *       reads their own study's released images only. An image is released
 *       when the report it was attached to is released, or, for an image on
 *       the study itself, when the study has a released report.</li>
 * </ul>
 * Reception and billing never reach it: the controller's roles exclude them.
 *
 * <p><b>Locking follows the signature.</b> An image on a signed (or voided)
 * report, or on a study that already carries a signed report, can no longer
 * be removed; after signing, more images go on an addendum. While unlocked,
 * only its uploader or a radiologist removes it.
 *
 * <p>Every upload, read of the bytes and removal is audited, by ids only:
 * no file name and no caption reaches the audit text.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImagingStudyImageService {

    /** More than enough key images for one study, and a bound on what one order can hold. */
    static final int MAX_IMAGES_PER_STUDY = 30;
    static final int MAX_CAPTION_LENGTH = 500;
    static final String AUDIT_ENTITY_TYPE = "IMAGING_STUDY_IMAGE";

    private static final String MSG_ORDER_NOT_FOUND = "imaging.order.notFound";
    private static final String MSG_IMAGE_NOT_FOUND = "imaging.image.notFound";
    private static final String RADIOLOGIST = "RADIOLOGIST";

    private final ImagingStudyImageRepository imageRepository;
    private final ImagingOrderRepository orderRepository;
    private final ImagingReportRepository reportRepository;
    private final FileUploadService fileUploadService;
    private final RoleValidator roleValidator;
    private final PatientSubjectReadGuard subjectReadGuard;
    private final AuditEventLogService auditEventLogService;

    /** The bytes of one image, opened; the caller (the response body) closes them. */
    public record ImageContent(StoredObject object, String contentType, UUID imageId) {
    }

    // ── Upload ───────────────────────────────────────────────────────────

    /**
     * Attach one image to a study, or to one of its unsigned reports.
     *
     * @param reportId null to attach to the study itself (refused once the study has a signed
     *                 report: the image then belongs on an addendum)
     */
    @Transactional(rollbackFor = IOException.class)
    public ImagingStudyImageDTO upload(UUID orderId, UUID reportId, MultipartFile file, String caption)
            throws IOException {
        ImagingOrder order = loadOrderInScope(orderId);
        CancelledOrders.requireNotCancelled(order.getStatus(), "imaging.order.cancelled.locked");
        if (order.getHospital() == null) {
            throw new BusinessException("imaging.report.orderWithoutHospital");
        }
        List<ImagingReport> reports = reportRepository.findByImagingOrder_IdOrderByReportVersionDesc(orderId);
        ImagingReport report = null;
        // Policy rule 3 (provisional): a technician attaches images to the study, never to a
        // report (they add nothing to a report).
        boolean technician = technicians().actsAsTechnician(order);
        if (reportId != null && technician) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("imaging.image.technicianStudyOnly"));
        }
        if (reportId != null) {
            report = reports.stream().filter(r -> reportId.equals(r.getId())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("imaging.report.notFound", reportId));
            if (reportLocked(report)) {
                throw new BusinessException("imaging.image.reportSigned");
            }
        } else if (reports.stream().anyMatch(ImagingReport::isSigned)) {
            throw new BusinessException("imaging.image.studySigned");
        }
        if (imageRepository.countByImagingOrder_Id(orderId) >= MAX_IMAGES_PER_STUDY) {
            throw new BusinessException("imaging.image.tooMany", MAX_IMAGES_PER_STUDY);
        }
        String cleanCaption = cleanCaption(caption);

        FileUploadService.StoredFileDescriptor stored = fileUploadService.uploadImagingImage(file);
        ImagingStudyImage image = ImagingStudyImage.builder()
            .imagingOrder(order)
            .imagingReport(report)
            .hospitalId(order.getHospital().getId())
            .storageKey(stored.storageKey())
            .contentType(stored.contentType())
            .sizeBytes(stored.sizeBytes())
            .sha256(stored.sha256())
            .caption(cleanCaption)
            .uploadedByUserId(roleValidator.getCurrentUserId())
            .build();
        try {
            image = imageRepository.save(image);
        } catch (RuntimeException ex) {
            // The row did not land: the stored bytes would be an orphan nobody can read.
            removeStoredQuietly(stored.storageKey());
            throw ex;
        }
        audit(AuditEventType.IMAGING_IMAGE_UPLOADED, order, image.getId(),
            "Imaging image " + image.getId() + " attached to imaging order " + order.getId()
                + (report != null ? " (report " + report.getId() + ")" : ""));
        return toStaffDto(image, false, technician, reports);
    }

    // ── Reads ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ImagingStudyImageDTO> list(UUID orderId) {
        ImagingOrder order = orderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_ORDER_NOT_FOUND, orderId));
        Reach reach = reachOf(order, MSG_ORDER_NOT_FOUND, orderId);
        List<ImagingReport> reports = reportRepository.findByImagingOrder_IdOrderByReportVersionDesc(orderId);
        boolean studyReleased = reports.stream().anyMatch(ImagingReport::isReleasedToPatient);
        // What the technician may still edit is decided once, for the screen's flags (never a refusal).
        boolean technicianWriter = reach != Reach.AS_PATIENT && order.getHospital() != null
            && technicians().technicianWriterAt(order.getHospital().getId());
        return imageRepository.findByImagingOrder_IdOrderByCreatedAtAsc(orderId).stream()
            // QA D2464: a withdrawn image is kept for the record and shown to no reader.
            .filter(image -> !image.isWithdrawn())
            .filter(image -> readable(reach, image, studyReleased, order))
            .map(image -> reach == Reach.AS_PATIENT ? toPatientDto(image)
                : toStaffDto(image, reach == Reach.ADMIN, technicianWriter, reports))
            .toList();
    }

    /** Open one image's bytes for its reader; audited as a read every time. */
    @Transactional(readOnly = true)
    public ImageContent readContent(UUID imageId) {
        ImagingStudyImage image = imageRepository.findById(imageId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_IMAGE_NOT_FOUND, imageId));
        ImagingOrder order = image.getImagingOrder();
        Reach reach = reachOf(order, MSG_IMAGE_NOT_FOUND, imageId);
        if (image.isWithdrawn()) {
            throw new ResourceNotFoundException(MSG_IMAGE_NOT_FOUND, imageId);
        }
        if (reach != Reach.CLINICAL) {
            boolean studyReleased = reportRepository.findByImagingOrder_IdOrderByReportVersionDesc(order.getId())
                .stream().anyMatch(ImagingReport::isReleasedToPatient);
            if (!readable(reach, image, studyReleased, order)) {
                throw new ResourceNotFoundException(MSG_IMAGE_NOT_FOUND, imageId);
            }
        }
        StoredObject object = fileUploadService.openStoredFile(image.getStorageKey(),
            FileUploadService.IMAGING_IMAGES_PATH);
        audit(AuditEventType.DATA_ACCESS, order, image.getId(),
            "Imaging image " + image.getId() + " of imaging order " + order.getId() + " viewed");
        return new ImageContent(object, image.getContentType(), image.getId());
    }

    // ── Removal ──────────────────────────────────────────────────────────

    @Transactional(rollbackFor = IOException.class)
    public void delete(UUID imageId) throws IOException {
        ImagingStudyImage image = imageRepository.findById(imageId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_IMAGE_NOT_FOUND, imageId));
        ImagingOrder order = image.getImagingOrder();
        if (!orderInScope(order) || image.isWithdrawn()) {
            throw new ResourceNotFoundException(MSG_IMAGE_NOT_FOUND, imageId);
        }
        // Policy rule 3: a technician of another hospital, admitted on that role alone, is refused.
        technicians().actsAsTechnician(order);
        List<ImagingReport> reports = reportRepository.findByImagingOrder_IdOrderByReportVersionDesc(order.getId());
        if (locked(image, reports)) {
            throw new BusinessException("imaging.image.locked");
        }
        if (!mayRemove(image)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("imaging.image.removeNotAllowed"));
        }
        String key = image.getStorageKey();
        imageRepository.delete(image);
        imageRepository.flush();
        fileUploadService.deleteStoredFile(key, FileUploadService.IMAGING_IMAGES_PATH);
        audit(AuditEventType.IMAGING_IMAGE_DELETED, order, imageId,
            "Imaging image " + imageId + " removed from imaging order " + order.getId());
    }

    // ── Withdrawal and caption correction (QA D2464 / D2466, r64) ────────

    /**
     * Withdraw a locked image (a wrong image, a wrong patient's) with a reason.
     * The row and the stored file are kept for the record and the audit; every
     * reader stops seeing it. An unlocked image is removed instead.
     */
    @Transactional
    public void withdraw(UUID imageId, String reason) {
        ImagingStudyImage image = loadInScope(imageId);
        // Withdrawn once: a second call never overwrites the reason (QA D1186 class).
        if (image.isWithdrawn()) {
            throw new ResourceNotFoundException(MSG_IMAGE_NOT_FOUND, imageId);
        }
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty()) {
            throw new com.example.hms.exception.FieldBusinessException("reason", "imaging.image.withdrawReasonRequired");
        }
        if (cleanReason.length() > MAX_CAPTION_LENGTH) {
            throw new com.example.hms.exception.FieldBusinessException("reason", "imaging.image.withdrawReasonTooLong",
                MAX_CAPTION_LENGTH);
        }
        List<ImagingReport> reports =
            reportRepository.findByImagingOrder_IdOrderByReportVersionDesc(image.getImagingOrder().getId());
        if (!locked(image, reports)) {
            throw new BusinessException("imaging.image.withdrawNotLocked");
        }
        if (!mayWithdraw(image, reports)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("imaging.image.withdrawNotAllowed"));
        }
        image.setWithdrawnAt(java.time.LocalDateTime.now());
        image.setWithdrawnByUserId(roleValidator.getCurrentUserId());
        image.setWithdrawalReason(cleanReason);
        imageRepository.save(image);
        audit(AuditEventType.IMAGING_IMAGE_WITHDRAWN, image.getImagingOrder(), imageId,
            "Imaging image " + imageId + " withdrawn from imaging order " + image.getImagingOrder().getId());
    }

    /** Correct an image's caption (its uploader or a radiologist), audited. */
    @Transactional
    public ImagingStudyImageDTO updateCaption(UUID imageId, String caption) {
        ImagingStudyImage image = loadInScope(imageId);
        // A withdrawn image is closed to every edit, as it is to every reader.
        if (image.isWithdrawn()) {
            throw new ResourceNotFoundException(MSG_IMAGE_NOT_FOUND, imageId);
        }
        if (!mayRemove(image)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("imaging.image.captionNotAllowed"));
        }
        // Policy rule 3 (provisional): once the image is locked by a signature it belongs to the
        // signed record; a technician no longer changes its caption (a radiologist does).
        boolean technician = technicians().actsAsTechnician(image.getImagingOrder());
        if (technician && technicianLockedOut(image)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("imaging.image.technicianCaptionLocked"));
        }
        image.setCaption(cleanCaption(caption));
        image.setCaptionUpdatedAt(java.time.LocalDateTime.now());
        image.setCaptionUpdatedByUserId(roleValidator.getCurrentUserId());
        ImagingStudyImage saved = imageRepository.save(image);
        audit(AuditEventType.IMAGING_IMAGE_CAPTION_UPDATED, image.getImagingOrder(), imageId,
            "Imaging image " + imageId + " caption corrected on imaging order " + image.getImagingOrder().getId());
        return toStaffDto(saved, false, technician,
            reportRepository.findByImagingOrder_IdOrderByReportVersionDesc(image.getImagingOrder().getId()));
    }

    /** An image of a study in the caller's hospital; another hospital's answers as a missing id. */
    private ImagingStudyImage loadInScope(UUID imageId) {
        ImagingStudyImage image = imageRepository.findById(imageId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_IMAGE_NOT_FOUND, imageId));
        if (!orderInScope(image.getImagingOrder())) {
            throw new ResourceNotFoundException(MSG_IMAGE_NOT_FOUND, imageId);
        }
        return image;
    }

    /**
     * A radiologist, or the clinician who signed the report the image belongs to
     * (for an image on the study itself, a signed report of the study).
     */
    private boolean mayWithdraw(ImagingStudyImage image, List<ImagingReport> studyReports) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (ClinicalActor.holdsOwnRoleQuietly(auth, RADIOLOGIST)) {
            return true;
        }
        UUID caller = roleValidator.getCurrentUserId();
        if (caller == null) {
            return false;
        }
        List<ImagingReport> signedBy = image.getImagingReport() != null
            ? List.of(image.getImagingReport())
            : studyReports;
        return signedBy.stream()
            .filter(ImagingReport::isSigned)
            .map(ImagingReport::getSignedBy)
            .filter(Objects::nonNull)
            .anyMatch(signer -> signer.getUser() != null && caller.equals(signer.getUser().getId()));
    }

    /**
     * The study's withdrawn images, for the record: when, by whom and why (QA D1225: a stored
     * reason is read somewhere). Metadata only; the bytes stay unreadable. Hospital-scoped.
     */
    @Transactional(readOnly = true)
    public List<ImagingStudyImageWithdrawalDTO> withdrawn(UUID orderId) {
        ImagingOrder order = loadOrderInScope(orderId);
        // Policy rule 3: a technician reads what became of the study's images, and of a report's
        // only where they read the report (a withdrawn image was signed, hence released).
        Reach reach = technicians().readsAsTechnician(order) ? technicianReach() : Reach.CLINICAL;
        return imageRepository.findByImagingOrder_IdOrderByCreatedAtAsc(order.getId()).stream()
            .filter(ImagingStudyImage::isWithdrawn)
            .filter(image -> readable(reach, image, true, order))
            .map(image -> new ImagingStudyImageWithdrawalDTO(image.getId(), order.getId(), reportIdOf(image),
                image.getCaption(), image.getWithdrawnAt(), image.getWithdrawnByUserId(),
                image.getWithdrawalReason()))
            .toList();
    }

    // ── Rules ────────────────────────────────────────────────────────────

    /**
     * How the caller reads a study: clinically, as an administrator, as its patient, or (policy
     * rule 3) as an imaging technician, alone or beside an administrative role.
     */
    enum Reach { CLINICAL, ADMIN, AS_PATIENT, TECHNICIAN, TECHNICIAN_ADMIN }

    /**
     * Whether this reader sees the image. The care team sees every image; an administrator and
     * the patient, the released ones. Policy rule 3 (provisional): an imaging technician sees the
     * STUDY's images (what was acquired, before any release), and an image that belongs to a
     * REPORT only as they read that report: released, and of an exam they performed (or, beside an
     * administrative role, any released one). A draft report's attachment is never theirs.
     */
    private boolean readable(Reach reach, ImagingStudyImage image, boolean studyReleased, ImagingOrder order) {
        if (reach == Reach.CLINICAL) {
            return true;
        }
        if (reach == Reach.TECHNICIAN || reach == Reach.TECHNICIAN_ADMIN) {
            ImagingReport report = image.getImagingReport();
            if (report == null) {
                return true;
            }
            return report.isReleasedToPatient() && (reach == Reach.TECHNICIAN_ADMIN || performedByCaller(order));
        }
        return released(image, studyReleased);
    }

    private Reach technicianReach() {
        return ClinicalActor.isAdministrativeImagingReader() ? Reach.TECHNICIAN_ADMIN : Reach.TECHNICIAN;
    }

    /** The exam record names the caller as who performed it (stamped from the session at its start). */
    private boolean performedByCaller(ImagingOrder order) {
        UUID caller = roleValidator.getCurrentUserId();
        return caller != null && caller.equals(order.getPerformedByUserId());
    }

    /**
     * The report's reading rule, applied to the study. Anything the caller may not read answers
     * as a missing id ({@code missingKey}), before any other check can answer differently.
     */
    private Reach reachOf(ImagingOrder order, String missingKey, UUID requestedId) {
        if (subjectReadGuard.isPatientOnly(PatientSubjectReaderRoles.IMAGING_REPORT_READS)) {
            if (!subjectReadGuard.callerOwns(order.getPatient())) {
                throw new ResourceNotFoundException(missingKey, requestedId);
            }
            return Reach.AS_PATIENT;
        }
        if (!orderInScope(order)) {
            // Staff who are also patients read their own study elsewhere, as its patient.
            if (!subjectReadGuard.ownsAsItsPatient(order.getPatient())) {
                throw new ResourceNotFoundException(missingKey, requestedId);
            }
            return Reach.AS_PATIENT;
        }
        // Policy rule 3: an imaging technician reads the study's images and, of a report's, only
        // those of the reports they read (readable), whatever administrative role they also hold.
        if (technicians().readsAsTechnician(order)) {
            return technicianReach();
        }
        return ClinicalActor.isAdministrativeImagingReader() ? Reach.ADMIN : Reach.CLINICAL;
    }

    private ImagingOrder loadOrderInScope(UUID orderId) {
        ImagingOrder order = orderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_ORDER_NOT_FOUND, orderId));
        if (!orderInScope(order)) {
            throw new ResourceNotFoundException(MSG_ORDER_NOT_FOUND, orderId);
        }
        return order;
    }

    private boolean orderInScope(ImagingOrder order) {
        UUID scope = roleValidator.requireActiveHospitalId();
        return scope == null || order.getHospital() == null || scope.equals(order.getHospital().getId());
    }

    /** Released to the patient: its report is, or (on the study itself) the study has a released report. */
    static boolean released(ImagingStudyImage image, boolean studyReleased) {
        ImagingReport report = image.getImagingReport();
        return report != null ? report.isReleasedToPatient() : studyReleased;
    }

    /** A signed or voided report takes no new image and gives none up. */
    static boolean reportLocked(ImagingReport report) {
        return report.isSigned()
            || report.getReportStatus() == ImagingReportStatus.CANCELLED
            || report.getReportStatus() == ImagingReportStatus.ERROR;
    }

    static boolean locked(ImagingStudyImage image, List<ImagingReport> studyReports) {
        ImagingReport report = image.getImagingReport();
        if (report != null) {
            return reportLocked(report);
        }
        return studyReports.stream().anyMatch(ImagingReport::isSigned);
    }

    /** Policy rule 3: where the imaging technician role counts (the order's hospital) and how far it reaches. */
    private com.example.hms.service.imaging.ImagingTechnicianScope technicians() {
        return new com.example.hms.service.imaging.ImagingTechnicianScope(roleValidator);
    }

    /** For a caller acting as a technician: an image a signature has locked, or a report's own image. */
    private boolean technicianLockedOut(ImagingStudyImage image) {
        return image.getImagingReport() != null || locked(image,
            reportRepository.findByImagingOrder_IdOrderByReportVersionDesc(image.getImagingOrder().getId()));
    }

    private boolean mayRemove(ImagingStudyImage image) {
        UUID caller = roleValidator.getCurrentUserId();
        if (caller != null && caller.equals(image.getUploadedByUserId())) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ClinicalActor.holdsOwnRoleQuietly(auth, RADIOLOGIST);
    }

    private static String cleanCaption(String caption) {
        if (!StringUtils.hasText(caption)) {
            return null;
        }
        String trimmed = caption.trim();
        if (trimmed.length() > MAX_CAPTION_LENGTH) {
            throw new BusinessException("imaging.image.captionTooLong", MAX_CAPTION_LENGTH);
        }
        return trimmed;
    }

    // ── Views ────────────────────────────────────────────────────────────

    private ImagingStudyImageDTO toStaffDto(ImagingStudyImage image, boolean adminReader, boolean technicianWriter,
                                            List<ImagingReport> reports) {
        boolean locked = locked(image, reports);
        boolean deletable = !adminReader && !locked && mayRemove(image);
        // Policy rule 3: a technician's caption edit ends at the signature, and never touches a report's image.
        boolean technicianClosed = technicianWriter && (locked || image.getImagingReport() != null);
        boolean captionEditable = !adminReader && !technicianClosed && mayRemove(image);
        boolean withdrawable = !adminReader && locked && mayWithdraw(image, reports);
        return new ImagingStudyImageDTO(image.getId(), image.getImagingOrder().getId(), reportIdOf(image),
            image.getContentType(), image.getSizeBytes(), image.getCaption(), image.getCreatedAt(),
            image.getUploadedByUserId(), locked, deletable, captionEditable, withdrawable);
    }

    private static ImagingStudyImageDTO toPatientDto(ImagingStudyImage image) {
        return new ImagingStudyImageDTO(image.getId(), image.getImagingOrder().getId(), reportIdOf(image),
            image.getContentType(), image.getSizeBytes(), image.getCaption(), image.getCreatedAt(),
            null, true, false, false, false);
    }

    private static UUID reportIdOf(ImagingStudyImage image) {
        return image.getImagingReport() != null ? image.getImagingReport().getId() : null;
    }

    // ── Audit ────────────────────────────────────────────────────────────

    /** Best effort, ids only: a failed audit write never fails the clinical action. */
    private void audit(AuditEventType type, ImagingOrder order, UUID imageId, String description) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            UUID patientId = order.getPatient() != null ? order.getPatient().getId() : null;
            UUID hospitalId = order.getHospital() != null ? order.getHospital().getId() : null;
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userName(SecurityUtils.getCurrentUsername())
                .userId(roleValidator.getCurrentUserId())
                .hospitalId(hospitalId)
                .roleName(ActingAssignments.roleName(null, ActingAssignments.actingRoles(auth)))
                .patientId(patientId)
                .eventType(type)
                .entityType(AUDIT_ENTITY_TYPE)
                .resourceId(Objects.toString(imageId, null))
                .eventDescription(description)
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            log.warn("Audit emit failed for imaging image {}: {}", imageId, ex.getMessage());
        }
    }

    private void removeStoredQuietly(String storageKey) {
        try {
            fileUploadService.deleteStoredFile(storageKey, FileUploadService.IMAGING_IMAGES_PATH);
        } catch (IOException | RuntimeException ex) {
            log.warn("Orphaned imaging image could not be removed from storage: {}", ex.getMessage());
        }
    }
}

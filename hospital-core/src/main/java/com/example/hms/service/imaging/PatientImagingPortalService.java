package com.example.hms.service.imaging;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ImagingReportMapper;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.ImagingStudyImage;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingStudyImageDTO;
import com.example.hms.payload.dto.imaging.PatientImagingStudyDTO;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.ImagingStudyImageRepository;
import com.example.hms.security.SecurityUtils;
import com.example.hms.security.audit.ActingAssignments;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.FileUploadService;
import com.example.hms.service.support.PatientSafeView;
import com.example.hms.storage.StoredObject;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * QA D2342 (r63, High): a released imaging report reaches its patient.
 *
 * <p>The caller has already been resolved to a patient id by
 * {@code PatientImagingPortalController}: the patient's own id from their
 * token, or a grantor's id after the proxy grant was checked. This service
 * never takes a patient id from anywhere else.
 *
 * <p><b>Only what is released.</b> A report reaches the patient once it is
 * signed and not voided ({@link ImagingReport#isReleasedToPatient()}), the
 * rule every other patient read of a report follows. Each report is the
 * patient's copy ({@link PatientSafeView#imagingReport}), so the status trail,
 * the signature digest, the escalation and the working measurements stay with
 * the care team (QA D1960). A key image is released with its report, or, on
 * the study itself, once the study has a released report (#1223's rule).
 *
 * <p><b>Tenancy.</b> The patient's own record at every hospital (design Q1,
 * as their lab results): the rows are selected by the patient id, never by a
 * hospital the caller names.
 *
 * <p><b>Audit.</b> Every list read and every image read writes an audit row,
 * by ids only, whether the patient or a proxy reads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatientImagingPortalService {

    static final String AUDIT_ENTITY_TYPE = "IMAGING_REPORT";
    private static final String MSG_IMAGE_NOT_FOUND = "imaging.image.notFound";

    private final ImagingOrderRepository orderRepository;
    private final ImagingReportRepository reportRepository;
    private final ImagingStudyImageRepository imageRepository;
    private final ImagingReportMapper reportMapper;
    private final FileUploadService fileUploadService;
    private final RoleValidator roleValidator;
    private final AuditEventLogService auditEventLogService;
    private final com.example.hms.repository.EncounterRepository encounterRepository;
    /** QA D2730: what a proxy may read of the grantor's imaging. */
    private final com.example.hms.service.recordaccess.ProxyRecordFilter proxyRecordFilter;

    /** How the patient was reached, for the audit row. */
    public enum Reader { PATIENT, PROXY }

    /** The patient's studies with at least one released report, newest first. */
    @Transactional(readOnly = true)
    public List<PatientImagingStudyDTO> studiesOf(UUID patientId, Reader reader) {
        List<ImagingOrder> orders = shownTo(reader, orderRepository.findByPatient_IdOrderByOrderedAtDesc(patientId));
        if (orders.isEmpty()) {
            audit(patientId, null, null, readDescription(patientId, reader, 0));
            return List.of();
        }
        List<UUID> orderIds = orders.stream().map(ImagingOrder::getId).toList();
        Map<UUID, List<ImagingReport>> released = reportRepository.findByImagingOrder_IdIn(orderIds).stream()
            .filter(ImagingReport::isReleasedToPatient)
            .collect(Collectors.groupingBy(r -> r.getImagingOrder().getId()));
        Map<UUID, List<ImagingStudyImage>> images = released.isEmpty() ? Map.of()
            : imageRepository.findByImagingOrder_IdInOrderByCreatedAtAsc(released.keySet()).stream()
                .collect(Collectors.groupingBy(i -> i.getImagingOrder().getId()));

        List<PatientImagingStudyDTO> studies = orders.stream()
            .filter(order -> released.containsKey(order.getId()))
            .map(order -> toStudy(order, released.get(order.getId()), images.getOrDefault(order.getId(), List.of())))
            .toList();
        audit(patientId, null, null, readDescription(patientId, reader, studies.size()));
        return studies;
    }

    /** One released key image of the patient's own study; anything else answers as a missing id. */
    @Transactional(readOnly = true)
    public ImagingStudyImageService.ImageContent openImage(UUID patientId, UUID imageId, Reader reader) {
        ImagingStudyImage image = imageRepository.findById(imageId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_IMAGE_NOT_FOUND, imageId));
        ImagingOrder order = image.getImagingOrder();
        if (image.isWithdrawn() || order == null || order.getPatient() == null
                || !Objects.equals(order.getPatient().getId(), patientId)
                || shownTo(reader, List.of(order)).isEmpty()) {
            throw new ResourceNotFoundException(MSG_IMAGE_NOT_FOUND, imageId);
        }
        boolean studyReleased = reportRepository.findByImagingOrder_IdOrderByReportVersionDesc(order.getId())
            .stream().anyMatch(ImagingReport::isReleasedToPatient);
        if (!ImagingStudyImageService.released(image, studyReleased)) {
            throw new ResourceNotFoundException(MSG_IMAGE_NOT_FOUND, imageId);
        }
        StoredObject object = fileUploadService.openStoredFile(image.getStorageKey(),
            FileUploadService.IMAGING_IMAGES_PATH);
        audit(patientId, order, image.getId(), "Imaging image " + image.getId() + " of imaging order " + order.getId()
            + " viewed on the patient portal" + (reader == Reader.PROXY ? " by a proxy" : ""));
        return new ImagingStudyImageService.ImageContent(object, image.getContentType(), image.getId());
    }

    /**
     * QA D2730 (r65): a proxy never reads a study ordered in a sensitive visit
     * ({@code ProxyRecordFilter}). An order names its visit by id; a visit
     * that cannot be read is treated as sensitive (fails closed); an order that
     * names no visit carries no category of its own and is judged by the next
     * rule only. QA D3154: whatever its visit, an obstetric, pelvic or
     * gynaecological study, which may reveal a pregnancy, never reaches a proxy
     * ({@link PregnancyRevealingStudyRule}, provisional). The patient reads every study.
     */
    private List<ImagingOrder> shownTo(Reader reader, List<ImagingOrder> orders) {
        if (reader != Reader.PROXY || orders.isEmpty()) {
            return orders;
        }
        java.util.Set<UUID> visitIds = orders.stream().map(ImagingOrder::getEncounterId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        java.util.Set<UUID> admittedVisits = visitIds.isEmpty() ? java.util.Set.of()
            : encounterRepository.findWithDepartmentByIdIn(visitIds).stream()
                .filter(proxyRecordFilter::admits)
                .map(com.example.hms.model.Encounter::getId)
                .collect(Collectors.toSet());
        return orders.stream()
            .filter(o -> !PregnancyRevealingStudyRule.mayRevealPregnancy(o))
            .filter(o -> o.getEncounterId() == null || admittedVisits.contains(o.getEncounterId()))
            .toList();
    }

    private PatientImagingStudyDTO toStudy(ImagingOrder order, List<ImagingReport> releasedReports,
                                           List<ImagingStudyImage> studyImages) {
        List<ImagingReportResponseDTO> reports = releasedReports.stream()
            .sorted(Comparator.comparing(ImagingReport::getReportVersion,
                Comparator.nullsFirst(Comparator.naturalOrder())))
            .map(reportMapper::toResponseDTO)
            .map(PatientSafeView::imagingReport)
            .toList();
        // The study is released (it is listed only with a released report): an image on the
        // study itself is shown, an image on a report only when that report is released.
        List<ImagingStudyImageDTO> shown = studyImages.stream()
            // QA D2464: a withdrawn image (a wrong image, a wrong patient's) never reaches the patient.
            .filter(image -> !image.isWithdrawn())
            .filter(image -> ImagingStudyImageService.released(image, true))
            .map(PatientImagingPortalService::toPatientImage)
            .toList();
        return new PatientImagingStudyDTO(order.getId(), order.getModality(), order.getStudyType(),
            order.getBodyRegion(), order.getLaterality(), order.getOrderedAt(),
            order.getHospital() != null ? order.getHospital().getName() : null, reports, shown);
    }

    private static ImagingStudyImageDTO toPatientImage(ImagingStudyImage image) {
        return new ImagingStudyImageDTO(image.getId(), image.getImagingOrder().getId(),
            image.getImagingReport() != null ? image.getImagingReport().getId() : null,
            image.getContentType(), image.getSizeBytes(), image.getCaption(), image.getCreatedAt(),
            null, true, false, false, false);
    }

    private static String readDescription(UUID patientId, Reader reader, int studies) {
        return "Imaging reports of patient " + patientId + " read on the patient portal"
            + (reader == Reader.PROXY ? " by a proxy" : "") + " (" + studies + " studies)";
    }

    /** Best effort, ids only: a failed audit write never fails the patient's read. */
    private void audit(UUID patientId, ImagingOrder order, UUID imageId, String description) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            UUID hospitalId = order != null && order.getHospital() != null ? order.getHospital().getId() : null;
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userName(SecurityUtils.getCurrentUsername())
                .userId(roleValidator.getCurrentUserId())
                .hospitalId(hospitalId)
                .roleName(ActingAssignments.roleName(null, ActingAssignments.actingRoles(auth)))
                .patientId(patientId)
                .eventType(AuditEventType.DATA_ACCESS)
                .entityType(imageId != null ? ImagingStudyImageService.AUDIT_ENTITY_TYPE : AUDIT_ENTITY_TYPE)
                .resourceId(imageId != null ? imageId.toString() : patientId.toString())
                .eventDescription(description)
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            log.warn("Audit emit failed for the patient imaging read of {}: {}", patientId, ex.getMessage());
        }
    }
}

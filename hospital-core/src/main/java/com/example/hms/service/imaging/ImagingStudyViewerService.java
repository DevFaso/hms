package com.example.hms.service.imaging;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.imaging.dicom.DicomInstanceRef;
import com.example.hms.imaging.dicom.DicomProxyProperties;
import com.example.hms.imaging.dicom.DicomUid;
import com.example.hms.imaging.dicom.PacsConnection;
import com.example.hms.imaging.dicom.PacsConnectionResolver;
import com.example.hms.imaging.dicom.PacsDicomWebGateway;
import com.example.hms.imaging.dicom.PacsUnavailableException;
import com.example.hms.imaging.dicom.PacsUrlPolicy;
import com.example.hms.imaging.dicom.RenderedFrame;
import com.example.hms.model.ImagingReport;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.imaging.pacs.ImagingStudyViewerDTO;
import com.example.hms.payload.dto.imaging.pacs.ImagingStudyViewerDTO.ImagingStudyInstanceDTO;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The in-app viewer of a report's images, keyed by the REPORT, never by a
 * caller-supplied study UID: the report is loaded and held to the caller's
 * hospital exactly as the report read is, and the study UID comes from the
 * report (or, on the hospital's own PACS, from its accession number). A
 * caller can therefore reach only the images of a report they could read.
 *
 * <p>Opening the viewer writes one {@code DATA_ACCESS} audit row (operator,
 * report, study); the frames it then pulls are served under the same checks
 * without a row each. Log lines carry the report id and a fixed reason, never
 * a UID, an accession number or a PACS answer.
 */
@Service
public class ImagingStudyViewerService {

    private static final Logger log = LoggerFactory.getLogger(ImagingStudyViewerService.class);
    private static final String MSG_REPORT_NOT_FOUND = "imaging.report.notFound";
    static final String MSG_FRAME_NOT_FOUND = "imaging.pacs.frame.notFound";
    static final String MSG_UID_INVALID = "imaging.pacs.uid.invalid";
    static final String MSG_FRAME_INVALID = "imaging.pacs.frame.invalid";
    private static final int MAX_FRAME = 10_000;

    /** How long an accession-number match is reused by the frame requests that follow. */
    private static final Duration ACCESSION_TTL = Duration.ofMinutes(10);
    private static final int ACCESSION_CACHE_SIZE = 512;

    /** The viewer's states, worded by the portal. */
    public enum Status {
        AVAILABLE, PROXY_DISABLED, PACS_NOT_CONFIGURED, NO_STUDY_LINKED, STUDY_NOT_FOUND,
        ACCESSION_AMBIGUOUS, PACS_UNREACHABLE
    }

    private final ImagingReportRepository imagingReportRepository;
    private final RoleValidator roleValidator;
    private final PacsConnectionResolver resolver;
    private final PacsDicomWebGateway gateway;
    private final AuditEventLogService auditEventLogService;
    private final DicomProxyProperties properties;
    private final Clock clock;
    private final TransactionOperations readOnly;

    /** reportId|baseUrl|accession → study UID; bounded, access-ordered. */
    private final Map<String, CachedStudy> accessionMatches =
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedStudy> eldest) {
                return size() > ACCESSION_CACHE_SIZE;
            }
        };

    private record CachedStudy(String studyUid, Instant expiresAt) {
    }

    /**
     * What the PACS calls need from the report, read in a short read-only
     * transaction: no database connection is held while the PACS answers.
     */
    private record Target(UUID reportId, UUID hospitalId, UUID patientId, String studyUid, String accession,
                          PacsConnectionResolver.Resolution resolution) {
    }

    /** Where the report's study UID came from. */
    private record StudyLink(String studyUid, String linkedBy, Status unlinkedStatus) {
    }

    /** Two constructors (the second is the tests' seam): Spring is told which one to use. */
    @Autowired
    public ImagingStudyViewerService(ImagingReportRepository imagingReportRepository, RoleValidator roleValidator,
                                     PacsConnectionResolver resolver, PacsDicomWebGateway gateway,
                                     AuditEventLogService auditEventLogService, DicomProxyProperties properties,
                                     PlatformTransactionManager transactionManager) {
        this(imagingReportRepository, roleValidator, resolver, gateway, auditEventLogService, properties,
            Clock.systemUTC(), readOnlyTemplate(transactionManager));
    }

    ImagingStudyViewerService(ImagingReportRepository imagingReportRepository, RoleValidator roleValidator,
                              PacsConnectionResolver resolver, PacsDicomWebGateway gateway,
                              AuditEventLogService auditEventLogService, DicomProxyProperties properties,
                              Clock clock, TransactionOperations readOnly) {
        this.imagingReportRepository = imagingReportRepository;
        this.roleValidator = roleValidator;
        this.resolver = resolver;
        this.gateway = gateway;
        this.auditEventLogService = auditEventLogService;
        this.properties = properties;
        this.clock = clock;
        this.readOnly = readOnly;
    }

    private static TransactionTemplate readOnlyTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    /**
     * QA D2467 (r64): whether the report's hospital has a PACS the viewer can reach, known
     * without calling the PACS, so the portal offers no « Voir les images » that can only
     * answer « not enabled ». Scoped as the manifest is (another hospital's report is 404).
     */
    public boolean viewerReady(UUID reportId) {
        return loadScoped(reportId).resolution().ready();
    }

    /** The viewer manifest of a report: its state and, when AVAILABLE, its instances. */
    public ImagingStudyViewerDTO manifest(UUID reportId, UUID actorUserId) {
        Target report = loadScoped(reportId);
        PacsConnectionResolver.Resolution resolution = report.resolution();
        if (!resolution.ready()) {
            return answer(reportId, resolution.state() == PacsConnectionResolver.State.PROXY_DISABLED
                ? Status.PROXY_DISABLED : Status.PACS_NOT_CONFIGURED, null);
        }
        PacsConnection connection = resolution.connection();
        try {
            StudyLink link = studyOf(report, connection);
            if (link.studyUid() == null) {
                return answer(reportId, link.unlinkedStatus(), null);
            }
            List<DicomInstanceRef> refs = gateway.studyInstances(connection, link.studyUid());
            audit(report, link, actorUserId, refs.size());
            if (refs.isEmpty()) {
                return answer(reportId, Status.STUDY_NOT_FOUND, link.linkedBy());
            }
            List<ImagingStudyInstanceDTO> instances = refs.stream()
                .map(r -> new ImagingStudyInstanceDTO(r.seriesInstanceUid(), r.sopInstanceUid(), r.seriesNumber(),
                    r.instanceNumber(), r.numberOfFrames(), r.modality()))
                .toList();
            return new ImagingStudyViewerDTO(reportId, Status.AVAILABLE.name(), link.linkedBy(), instances,
                refs.size() >= Math.max(1, properties.getMaxInstances()));
        } catch (PacsUnavailableException | PacsUrlPolicy.RejectedException ex) {
            log.warn("[pacs] viewer manifest for report {} could not reach the PACS ({})", reportId,
                ex instanceof PacsUnavailableException pue ? pue.reason() : "URL_REFUSED");
            return answer(reportId, Status.PACS_UNREACHABLE, null);
        }
    }

    /**
     * One rendered frame of one instance of the report's study. A UID that is
     * not a DICOM UID is refused before anything else; a report out of scope,
     * an unlinked study or a frame the PACS does not have answers 404.
     */
    public RenderedFrame frame(UUID reportId, String seriesUid, String instanceUid, int frame) {
        if (!DicomUid.isValid(seriesUid) || !DicomUid.isValid(instanceUid)) {
            throw new BusinessException(MSG_UID_INVALID);
        }
        if (frame < 1 || frame > MAX_FRAME) {
            throw new BusinessException(MSG_FRAME_INVALID);
        }
        Target report = loadScoped(reportId);
        PacsConnectionResolver.Resolution resolution = report.resolution();
        if (!resolution.ready()) {
            throw new ResourceNotFoundException(MSG_FRAME_NOT_FOUND);
        }
        StudyLink link = studyOf(report, resolution.connection());
        if (link.studyUid() == null) {
            throw new ResourceNotFoundException(MSG_FRAME_NOT_FOUND);
        }
        Optional<RenderedFrame> rendered = gateway.renderedFrame(resolution.connection(), link.studyUid(),
            seriesUid, instanceUid, frame);
        return rendered.orElseThrow(() -> new ResourceNotFoundException(MSG_FRAME_NOT_FOUND));
    }

    /**
     * QA D2915: the legacy instance listing (DicomProxyService, study UIDs of the acting hospital)
     * is read by the care team of that hospital, or by an administrator as before; a technician
     * there, or a caller whose clinical role is held elsewhere, is refused.
     */
    public void requireInstanceListingReader() {
        UUID acting = roleValidator.getCurrentHospitalId();
        ImagingRoleScope roles = new ImagingRoleScope(roleValidator);
        if (roles.readerAt(acting) == ImagingRoleScope.Reader.TECHNICIAN) {
            roles.requireClinicalReaderAt(acting);
        }
    }

    /**
     * The report, held to the caller's hospital (out of scope answers as a
     * missing id), read in one short read-only transaction.
     */
    private Target loadScoped(UUID reportId) {
        return readOnly.execute(status -> {
            ImagingReport report = imagingReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_REPORT_NOT_FOUND, reportId));
            UUID scope = roleValidator.requireActiveHospitalId();
            UUID reportHospital = report.getHospital() == null ? null : report.getHospital().getId();
            if (reportHospital == null || (scope != null && !scope.equals(reportHospital))) {
                throw new ResourceNotFoundException(MSG_REPORT_NOT_FOUND, reportId);
            }
            // QA D2915: pixels are read by the care team OF the report's hospital, never on a
            // clinical role the token names and the caller holds elsewhere.
            new ImagingRoleScope(roleValidator).requireClinicalReaderAt(reportHospital);
            UUID patientId = report.getImagingOrder() == null || report.getImagingOrder().getPatient() == null
                ? null : report.getImagingOrder().getPatient().getId();
            return new Target(report.getId(), reportHospital, patientId, report.getStudyInstanceUid(),
                report.getAccessionNumber(), resolver.resolve(report.getHospital()));
        });
    }

    /**
     * The study UID on the report; otherwise, on the hospital's OWN PACS only,
     * the one study its accession number names. The platform PACS may hold
     * several hospitals' studies, where an accession number is not unique, so
     * it is never matched by accession there.
     */
    private StudyLink studyOf(Target report, PacsConnection connection) {
        String stored = report.studyUid();
        if (stored != null && !stored.isBlank()) {
            return DicomUid.isValid(stored.trim())
                ? new StudyLink(stored.trim(), "STUDY_UID", null)
                : new StudyLink(null, null, Status.NO_STUDY_LINKED);
        }
        String accession = report.accession() == null ? "" : report.accession().trim();
        if (accession.isEmpty() || !connection.dedicated()) {
            return new StudyLink(null, null, Status.NO_STUDY_LINKED);
        }
        String key = report.reportId() + "|" + connection.baseUrl() + "|" + accession;
        Instant now = clock.instant();
        synchronized (accessionMatches) {
            CachedStudy cached = accessionMatches.get(key);
            if (cached != null && cached.expiresAt().isAfter(now)) {
                return new StudyLink(cached.studyUid(), "ACCESSION", null);
            }
        }
        List<String> matches = gateway.studyUidsByAccession(connection, accession);
        if (matches.size() != 1) {
            return new StudyLink(null, null, matches.isEmpty() ? Status.STUDY_NOT_FOUND : Status.ACCESSION_AMBIGUOUS);
        }
        synchronized (accessionMatches) {
            accessionMatches.put(key, new CachedStudy(matches.get(0), now.plus(ACCESSION_TTL)));
        }
        return new StudyLink(matches.get(0), "ACCESSION", null);
    }

    private static ImagingStudyViewerDTO answer(UUID reportId, Status status, String linkedBy) {
        return new ImagingStudyViewerDTO(reportId, status.name(), linkedBy, List.of(), false);
    }

    private void audit(Target report, StudyLink link, UUID actorUserId, int instanceCount) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.DATA_ACCESS)
                .status(AuditStatus.SUCCESS)
                .userId(actorUserId)
                .hospitalId(report.hospitalId())
                .patientId(report.patientId())
                .entityType("IMAGING_STUDY")
                .resourceId(report.reportId().toString())
                .eventDescription("Imaging study opened in the viewer (" + instanceCount + " instances)")
                .details(Map.of("reportId", report.reportId().toString(), "studyInstanceUid", link.studyUid(),
                    "linkedBy", link.linkedBy()))
                .build());
        } catch (RuntimeException ex) {
            log.warn("[pacs] viewer audit failed for report {}: {}", report.reportId(), ex.getClass().getSimpleName());
        }
    }
}

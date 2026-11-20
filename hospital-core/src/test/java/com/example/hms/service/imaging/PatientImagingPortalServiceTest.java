package com.example.hms.service.imaging;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ImagingReportMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.ImagingReportStatusHistory;
import com.example.hms.model.ImagingStudyImage;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.imaging.PatientImagingStudyDTO;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.ImagingStudyImageRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.FileUploadService;
import com.example.hms.service.imaging.PatientImagingPortalService.Reader;
import com.example.hms.service.recordaccess.ProxyRecordFilter;
import com.example.hms.service.recordaccess.SensitivityClassifierImpl;
import com.example.hms.storage.StoredObject;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D2342 (r63, High): what the patient's « Imagerie » page reads. Released
 * reports only, each the patient's copy (no status trail, no signature
 * digest, no escalation), the addendum with its original, and released key
 * images; another patient's image, or an unreleased one, answers as missing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("D2342: the patient's imaging page reads released reports only, as the patient's copy")
class PatientImagingPortalServiceTest {

    @Mock private ImagingOrderRepository orderRepository;
    @Mock private ImagingReportRepository reportRepository;
    @Mock private ImagingStudyImageRepository imageRepository;
    @Mock private FileUploadService fileUploadService;
    @Mock private RoleValidator roleValidator;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private com.example.hms.repository.EncounterRepository encounterRepository;

    private PatientImagingPortalService service;

    private final UUID patientId = UUID.randomUUID();
    private Patient patient;
    private Hospital hospital;
    private ImagingOrder order;
    private final List<ImagingReport> reports = new ArrayList<>();
    private final List<ImagingStudyImage> images = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new PatientImagingPortalService(orderRepository, reportRepository, imageRepository,
            new ImagingReportMapper(), fileUploadService, roleValidator, auditEventLogService,
            encounterRepository, new ProxyRecordFilter(new SensitivityClassifierImpl()));
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("CHU Yalgado");
        patient = new Patient();
        patient.setId(patientId);
        order = order(patient);
        when(orderRepository.findByPatient_IdOrderByOrderedAtDesc(patientId)).thenAnswer(inv -> List.of(order));
        when(reportRepository.findByImagingOrder_IdIn(anyCollection())).thenAnswer(inv -> reports);
        when(reportRepository.findByImagingOrder_IdOrderByReportVersionDesc(any())).thenAnswer(inv -> reports);
        when(imageRepository.findByImagingOrder_IdInOrderByCreatedAtAsc(anyCollection())).thenAnswer(inv -> images);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
    }

    private ImagingOrder order(Patient owner) {
        ImagingOrder o = new ImagingOrder();
        o.setId(UUID.randomUUID());
        o.setPatient(owner);
        o.setHospital(hospital);
        o.setModality(ImagingModality.XRAY);
        o.setStudyType("Radiographie thoracique");
        o.setOrderedAt(LocalDateTime.of(2026, 11, 14, 9, 0));
        return o;
    }

    private ImagingReport report(int version, ImagingReportStatus status, boolean signed) {
        ImagingReport r = new ImagingReport();
        r.setId(UUID.randomUUID());
        r.setImagingOrder(order);
        r.setHospital(hospital);
        r.setReportVersion(version);
        r.setReportStatus(status);
        r.setFindings("findings v" + version);
        r.setImpression("impression v" + version);
        if (signed) {
            r.setSignedAt(LocalDateTime.of(2026, 11, 14, 10, version));
            r.setSignatureValue("digest-" + version);
            r.setLockReason("Signed by Dr X");
        }
        r.setCriticalResultFlaggedAt(LocalDateTime.of(2026, 11, 14, 9, 30));
        ImagingReportStatusHistory history = new ImagingReportStatusHistory();
        history.setId(UUID.randomUUID());
        history.setStatusReason("staff-only reason");
        history.setNotes("staff-only note");
        history.setImagingReport(r);
        r.getStatusHistory().add(history);
        reports.add(r);
        return r;
    }

    private ImagingStudyImage image(ImagingReport onReport) {
        ImagingStudyImage image = ImagingStudyImage.builder()
            .imagingOrder(order)
            .imagingReport(onReport)
            .hospitalId(hospital.getId())
            .storageKey("imaging-images/" + UUID.randomUUID())
            .contentType("image/png")
            .sizeBytes(10)
            .sha256("x")
            .uploadedByUserId(UUID.randomUUID())
            .build();
        image.setId(UUID.randomUUID());
        images.add(image);
        return image;
    }

    @Test
    @DisplayName("D2730: a proxy never reads a study of an HIV-tagged visit, nor its image; the patient does")
    void aSensitiveVisitsStudyStaysWithThePatient() {
        com.example.hms.model.Encounter hivVisit = new com.example.hms.model.Encounter();
        hivVisit.setId(UUID.randomUUID());
        hivVisit.setSensitivityCategory(com.example.hms.enums.SensitivityCategory.HIV);
        order.setEncounterId(hivVisit.getId());
        when(encounterRepository.findWithDepartmentByIdIn(anyCollection())).thenReturn(List.of(hivVisit));
        report(1, ImagingReportStatus.FINAL, true);
        ImagingStudyImage onStudy = image(null);
        when(imageRepository.findById(onStudy.getId())).thenReturn(Optional.of(onStudy));

        assertThat(service.studiesOf(patientId, Reader.PATIENT)).hasSize(1);
        assertThat(service.studiesOf(patientId, Reader.PROXY)).isEmpty();
        assertThatThrownBy(() -> service.openImage(patientId, onStudy.getId(), Reader.PROXY))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("D2730: an untagged visit's study reaches the proxy; an unreadable visit fails closed")
    void anUntaggedVisitsStudyReachesTheProxy() {
        com.example.hms.model.Encounter visit = new com.example.hms.model.Encounter();
        visit.setId(UUID.randomUUID());
        order.setEncounterId(visit.getId());
        when(encounterRepository.findWithDepartmentByIdIn(anyCollection())).thenReturn(List.of(visit));
        report(1, ImagingReportStatus.FINAL, true);

        assertThat(service.studiesOf(patientId, Reader.PROXY)).hasSize(1);

        when(encounterRepository.findWithDepartmentByIdIn(anyCollection())).thenReturn(List.of());
        assertThat(service.studiesOf(patientId, Reader.PROXY)).isEmpty();
    }

    @Test
    @DisplayName("a draft or preliminary read never reaches the patient; nor does a study with only those")
    void unsignedReadsStayWithTheCareTeam() {
        report(1, ImagingReportStatus.PRELIMINARY, false);
        report(2, ImagingReportStatus.DRAFT, false);

        assertThat(service.studiesOf(patientId, Reader.PATIENT)).isEmpty();
    }

    @Test
    @DisplayName("the signed original, then its signed addendum; an unsigned addendum and a voided read stay out")
    void releasedVersionsOldestFirst() {
        ImagingReport original = report(1, ImagingReportStatus.FINAL, true);
        ImagingReport addendum = report(2, ImagingReportStatus.ADDENDUM, true);
        report(3, ImagingReportStatus.ADDENDUM, false);
        report(4, ImagingReportStatus.CANCELLED, true);

        List<PatientImagingStudyDTO> studies = service.studiesOf(patientId, Reader.PATIENT);

        assertThat(studies).hasSize(1);
        assertThat(studies.get(0).hospitalName()).isEqualTo("CHU Yalgado");
        assertThat(studies.get(0).reports()).extracting(ImagingReportResponseDTO::getId)
            .containsExactly(original.getId(), addendum.getId());
        assertThat(studies.get(0).reports().get(1).getSignedAt()).isEqualTo(addendum.getSignedAt());
    }

    @Test
    @DisplayName("each report is the patient's copy: no status trail, digest, lock reason or escalation (D1960)")
    void thePatientsCopyOnly() {
        report(1, ImagingReportStatus.FINAL, true);

        ImagingReportResponseDTO copy = service.studiesOf(patientId, Reader.PATIENT).get(0).reports().get(0);

        assertThat(copy.getImpression()).isEqualTo("impression v1");
        assertThat(copy.getStatusHistory()).isNullOrEmpty();
        assertThat(copy.getSignatureValue()).isNull();
        assertThat(copy.getLockReason()).isNull();
        assertThat(copy.getCriticalResultFlaggedAt()).isNull();
    }

    @Test
    @DisplayName("images: on the study and on a released report, never on an unsigned addendum")
    void releasedImagesOnly() {
        ImagingReport original = report(1, ImagingReportStatus.FINAL, true);
        ImagingReport pendingAddendum = report(2, ImagingReportStatus.ADDENDUM, false);
        ImagingStudyImage onStudy = image(null);
        ImagingStudyImage onReport = image(original);
        image(pendingAddendum);

        PatientImagingStudyDTO study = service.studiesOf(patientId, Reader.PATIENT).get(0);

        assertThat(study.images()).extracting(i -> i.id()).containsExactly(onStudy.getId(), onReport.getId());
        assertThat(study.images()).allSatisfy(i -> assertThat(i.uploadedByUserId()).isNull());
    }

    @Test
    @DisplayName("every read is audited by ids, and says when a proxy read it")
    void readsAreAudited() {
        report(1, ImagingReportStatus.FINAL, true);

        service.studiesOf(patientId, Reader.PROXY);

        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.DATA_ACCESS);
        assertThat(audit.getValue().getPatientId()).isEqualTo(patientId);
        assertThat(audit.getValue().getEventDescription())
            .isEqualTo("Imaging reports of patient " + patientId + " read on the patient portal by a proxy (1 studies)")
            .doesNotContain("impression");
    }

    @Test
    @DisplayName("an image of another patient's study answers as a missing id, and nothing is opened")
    void anotherPatientsImageIsMissing() {
        report(1, ImagingReportStatus.FINAL, true);
        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        order.setPatient(other);
        ImagingStudyImage image = image(null);
        when(imageRepository.findById(image.getId())).thenReturn(Optional.of(image));

        assertThatThrownBy(() -> service.openImage(patientId, image.getId(), Reader.PATIENT))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(fileUploadService, never()).openStoredFile(anyString(), anyString());
    }

    @Test
    @DisplayName("an image of the patient's own study before its report is signed answers as a missing id")
    void anUnreleasedImageIsMissing() {
        report(1, ImagingReportStatus.PRELIMINARY, false);
        ImagingStudyImage image = image(null);
        when(imageRepository.findById(image.getId())).thenReturn(Optional.of(image));

        assertThatThrownBy(() -> service.openImage(patientId, image.getId(), Reader.PATIENT))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(fileUploadService, never()).openStoredFile(anyString(), anyString());
    }

    @Test
    @DisplayName("a released image of the patient's own study opens, and the read is audited")
    void aReleasedImageOpens() {
        report(1, ImagingReportStatus.FINAL, true);
        ImagingStudyImage image = image(null);
        when(imageRepository.findById(image.getId())).thenReturn(Optional.of(image));
        StoredObject stored = mock(StoredObject.class);
        when(fileUploadService.openStoredFile(eq(image.getStorageKey()), anyString())).thenReturn(stored);

        var content = service.openImage(patientId, image.getId(), Reader.PATIENT);

        assertThat(content.object()).isSameAs(stored);
        assertThat(content.contentType()).isEqualTo("image/png");
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventDescription()).isEqualTo("Imaging image " + image.getId()
            + " of imaging order " + order.getId() + " viewed on the patient portal");
    }

    @Test
    @DisplayName("QA D2464: a withdrawn image reaches neither the patient's page nor their image read")
    void aWithdrawnImageIsGone() {
        report(1, ImagingReportStatus.FINAL, true);
        ImagingStudyImage kept = image(null);
        ImagingStudyImage withdrawn = image(null);
        withdrawn.setWithdrawnAt(LocalDateTime.now());
        when(imageRepository.findById(withdrawn.getId())).thenReturn(Optional.of(withdrawn));

        assertThat(service.studiesOf(patientId, Reader.PATIENT).get(0).images()).extracting(i -> i.id())
            .containsExactly(kept.getId());
        assertThatThrownBy(() -> service.openImage(patientId, withdrawn.getId(), Reader.PATIENT))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}

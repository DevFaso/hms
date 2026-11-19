package com.example.hms.service.imaging;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.ImagingStudyImage;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingStudyImageDTO;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.ImagingStudyImageRepository;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.FileUploadService;
import com.example.hms.service.PatientSubjectReadGuard;
import com.example.hms.storage.StoredObject;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Key images on an imaging study (V316): read as the report is read, locked
 * by the signature, removed only by their uploader or a radiologist, and
 * audited by ids.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImagingStudyImageServiceTest {

    @Mock private ImagingStudyImageRepository imageRepository;
    @Mock private ImagingOrderRepository orderRepository;
    @Mock private ImagingReportRepository reportRepository;
    @Mock private FileUploadService fileUploadService;
    @Mock private RoleValidator roleValidator;
    @Mock private PatientSubjectReadGuard subjectReadGuard;
    @Mock private AuditEventLogService auditEventLogService;

    @InjectMocks
    private ImagingStudyImageService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID callerId = UUID.randomUUID();
    private ImagingOrder order;
    private Patient patient;
    private final List<ImagingReport> reports = new ArrayList<>();
    private final List<ImagingStudyImage> images = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        order = new ImagingOrder();
        order.setId(UUID.randomUUID());
        order.setHospital(hospital);
        order.setPatient(patient);
        order.setStatus(ImagingOrderStatus.COMPLETED);

        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(reportRepository.findByImagingOrder_IdOrderByReportVersionDesc(order.getId())).thenReturn(reports);
        when(imageRepository.findByImagingOrder_IdOrderByCreatedAtAsc(order.getId())).thenReturn(images);
        when(imageRepository.save(any())).thenAnswer(inv -> {
            ImagingStudyImage saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(callerId);
        when(fileUploadService.uploadImagingImage(any())).thenReturn(new FileUploadService.StoredFileDescriptor(
            "/uploads/imaging-images/" + UUID.randomUUID() + ".png", null, null, "image/png", 12, "abc"));
        when(fileUploadService.openStoredFile(anyString(), eq(FileUploadService.IMAGING_IMAGES_PATH)))
            .thenAnswer(inv -> new StoredObject(new ByteArrayInputStream(new byte[] {1}), 1));
        as("ROLE_DOCTOR");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    private static void as(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("u", "n/a",
            Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private ImagingReport report(boolean signed, ImagingReportStatus status) {
        ImagingReport report = new ImagingReport();
        report.setId(UUID.randomUUID());
        report.setImagingOrder(order);
        report.setReportStatus(status);
        if (signed) {
            report.setSignedAt(LocalDateTime.now());
        }
        reports.add(report);
        return report;
    }

    private ImagingStudyImage image(ImagingReport report, UUID uploadedBy) {
        ImagingStudyImage image = ImagingStudyImage.builder()
            .imagingOrder(order).imagingReport(report).hospitalId(hospitalId)
            .storageKey("/uploads/imaging-images/" + UUID.randomUUID() + ".jpg")
            .contentType("image/jpeg").sizeBytes(10).caption("lesion").uploadedByUserId(uploadedBy).build();
        image.setId(UUID.randomUUID());
        images.add(image);
        when(imageRepository.findById(image.getId())).thenReturn(Optional.of(image));
        return image;
    }

    private void anotherHospital() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("file", "jean-dupont-thorax.png", "image/png", new byte[] {1, 2});
    }

    private List<AuditEventRequestDTO> audits() {
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, org.mockito.Mockito.atLeastOnce()).logEvent(captor.capture());
        return captor.getAllValues();
    }

    @Nested
    @DisplayName("reading follows the report")
    class Reading {

        @Test
        void aClinicianInScopeSeesEveryImageIncludingWorkInProgress() {
            ImagingReport draft = report(false, ImagingReportStatus.PRELIMINARY);
            image(draft, callerId);
            image(null, callerId);

            assertThat(service.list(order.getId())).hasSize(2);
        }

        @Test
        void anotherHospitalIsAMissingStudyForListBytesUploadAndDelete() {
            ImagingStudyImage image = image(null, callerId);
            anotherHospital();

            assertThatThrownBy(() -> service.list(order.getId())).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.readContent(image.getId())).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.upload(order.getId(), null, png(), null))
                .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.delete(image.getId())).isInstanceOf(ResourceNotFoundException.class);
            verify(fileUploadService, never()).openStoredFile(anyString(), anyString());
            verify(auditEventLogService, never()).logEvent(any());
        }

        @Test
        void anAdministratorSeesReleasedImagesOnly() {
            as("ROLE_HOSPITAL_ADMIN");
            ImagingReport draft = report(false, ImagingReportStatus.PRELIMINARY);
            ImagingStudyImage onDraft = image(draft, callerId);
            ImagingStudyImage onStudy = image(null, callerId);

            assertThat(service.list(order.getId())).isEmpty();
            assertThatThrownBy(() -> service.readContent(onDraft.getId())).isInstanceOf(ResourceNotFoundException.class);

            report(true, ImagingReportStatus.FINAL);
            assertThat(service.list(order.getId())).extracting(ImagingStudyImageDTO::id)
                .containsExactly(onStudy.getId());
            assertThat(service.list(order.getId())).allMatch(dto -> !dto.deletable());
        }

        @Test
        void thePatientReadsTheirOwnReleasedImagesOnly() {
            as("ROLE_PATIENT");
            when(subjectReadGuard.isPatientOnly(any())).thenReturn(true);
            when(subjectReadGuard.callerOwns(patient)).thenReturn(true);
            ImagingReport addendumDraft = report(false, ImagingReportStatus.PRELIMINARY);
            ImagingReport signed = report(true, ImagingReportStatus.FINAL);
            ImagingStudyImage released = image(signed, callerId);
            ImagingStudyImage unreleased = image(addendumDraft, callerId);

            List<ImagingStudyImageDTO> seen = service.list(order.getId());
            assertThat(seen).extracting(ImagingStudyImageDTO::id).containsExactly(released.getId());
            assertThat(seen.get(0).uploadedByUserId()).as("no staff id for the patient").isNull();
            assertThat(service.readContent(released.getId()).imageId()).isEqualTo(released.getId());
            assertThatThrownBy(() -> service.readContent(unreleased.getId())).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void anotherPatientIsAMissingStudyEvenInTheSameHospital() {
            as("ROLE_PATIENT");
            when(subjectReadGuard.isPatientOnly(any())).thenReturn(true);
            when(subjectReadGuard.callerOwns(patient)).thenReturn(false);
            ImagingStudyImage image = image(report(true, ImagingReportStatus.FINAL), callerId);

            assertThatThrownBy(() -> service.list(order.getId())).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.readContent(image.getId())).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void staffReadingTheirOwnStudyElsewhereReadItAsItsPatient() {
            anotherHospital();
            when(subjectReadGuard.ownsAsItsPatient(patient)).thenReturn(true);
            image(report(false, ImagingReportStatus.PRELIMINARY), callerId);
            ImagingStudyImage released = image(report(true, ImagingReportStatus.FINAL), callerId);

            assertThat(service.list(order.getId())).extracting(ImagingStudyImageDTO::id)
                .containsExactly(released.getId());
        }

        @Test
        void aVoidedReportReleasesNothing() {
            as("ROLE_PATIENT");
            when(subjectReadGuard.isPatientOnly(any())).thenReturn(true);
            when(subjectReadGuard.callerOwns(patient)).thenReturn(true);
            image(report(true, ImagingReportStatus.CANCELLED), callerId);

            assertThat(service.list(order.getId())).isEmpty();
        }

        @Test
        void everyReadOfTheBytesIsAuditedByIdsOnly() {
            ImagingStudyImage image = image(null, callerId);

            service.readContent(image.getId());

            AuditEventRequestDTO audit = audits().get(0);
            assertThat(audit.getEventType()).isEqualTo(AuditEventType.DATA_ACCESS);
            assertThat(audit.getEntityType()).isEqualTo("IMAGING_STUDY_IMAGE");
            assertThat(audit.getResourceId()).isEqualTo(image.getId().toString());
            assertThat(audit.getPatientId()).isEqualTo(patient.getId());
            assertThat(audit.getHospitalId()).isEqualTo(hospitalId);
            assertThat(audit.getEventDescription()).doesNotContain("lesion").doesNotContain(".jpg");
        }
    }

    @Nested
    @DisplayName("uploading")
    class Uploading {

        @Test
        void onTheStudyBeforeAnyReportIsSigned() throws IOException {
            ImagingStudyImageDTO dto = service.upload(order.getId(), null, png(), "  vue de face  ");

            assertThat(dto.imagingReportId()).isNull();
            assertThat(dto.caption()).isEqualTo("vue de face");
            assertThat(dto.deletable()).isTrue();
            AuditEventRequestDTO audit = audits().get(0);
            assertThat(audit.getEventType()).isEqualTo(AuditEventType.IMAGING_IMAGE_UPLOADED);
            assertThat(audit.getEventDescription()).doesNotContain("jean-dupont").doesNotContain("vue de face");
        }

        @Test
        void onAnUnsignedReport() throws IOException {
            ImagingReport draft = report(false, ImagingReportStatus.PRELIMINARY);

            assertThat(service.upload(order.getId(), draft.getId(), png(), null).imagingReportId())
                .isEqualTo(draft.getId());
        }

        @Test
        void aSignedReportTakesNoNewImage() {
            ImagingReport signed = report(true, ImagingReportStatus.FINAL);

            assertThatThrownBy(() -> service.upload(order.getId(), signed.getId(), png(), null))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("imaging.image.reportSigned");
        }

        @Test
        void aSignedStudyTakesMoreImagesOnAnAddendumOnly() throws IOException {
            report(true, ImagingReportStatus.FINAL);
            ImagingReport addendum = report(false, ImagingReportStatus.PRELIMINARY);

            assertThatThrownBy(() -> service.upload(order.getId(), null, png(), null))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("imaging.image.studySigned");
            assertThat(service.upload(order.getId(), addendum.getId(), png(), null).imagingReportId())
                .isEqualTo(addendum.getId());
        }

        @Test
        void aReportOfAnotherStudyIsMissing() {
            UUID foreignReport = UUID.randomUUID();

            assertThatThrownBy(() -> service.upload(order.getId(), foreignReport, png(), null))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void aCancelledOrderTakesNoImage() {
            order.setStatus(ImagingOrderStatus.CANCELLED);

            assertThatThrownBy(() -> service.upload(order.getId(), null, png(), null))
                .isInstanceOf(BusinessException.class);
        }

        @Test
        void aStudyHoldsABoundedNumberOfImages() {
            when(imageRepository.countByImagingOrder_Id(order.getId()))
                .thenReturn((long) ImagingStudyImageService.MAX_IMAGES_PER_STUDY);

            assertThatThrownBy(() -> service.upload(order.getId(), null, png(), null))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("imaging.image.tooMany");
        }

        @Test
        void aRowThatDoesNotLandRemovesTheStoredBytes() throws IOException {
            org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(imageRepository).save(any());

            assertThatThrownBy(() -> service.upload(order.getId(), null, png(), null))
                .isInstanceOf(IllegalStateException.class);
            verify(fileUploadService).deleteStoredFile(anyString(), eq(FileUploadService.IMAGING_IMAGES_PATH));
        }
    }

    @Nested
    @DisplayName("removing")
    class Removing {

        @Test
        void theUploaderRemovesTheirImageBeforeSigning() throws IOException {
            ImagingStudyImage image = image(report(false, ImagingReportStatus.PRELIMINARY), callerId);

            service.delete(image.getId());

            verify(imageRepository).delete(image);
            verify(fileUploadService).deleteStoredFile(image.getStorageKey(), FileUploadService.IMAGING_IMAGES_PATH);
            assertThat(audits()).extracting(AuditEventRequestDTO::getEventType)
                .containsExactly(AuditEventType.IMAGING_IMAGE_DELETED);
        }

        @Test
        void aRadiologistRemovesAColleaguesImage() throws IOException {
            as("ROLE_RADIOLOGIST");
            ImagingStudyImage image = image(null, UUID.randomUUID());

            service.delete(image.getId());

            verify(imageRepository).delete(image);
        }

        @Test
        void anotherDoctorDoesNotRemoveAColleaguesImage() throws IOException {
            ImagingStudyImage image = image(null, UUID.randomUUID());

            assertThatThrownBy(() -> service.delete(image.getId())).isInstanceOf(ClientSafeAccessDeniedException.class);
            verify(imageRepository, never()).delete(any());
            verify(fileUploadService, never()).deleteStoredFile(anyString(), anyString());
        }

        @Test
        void aSignedReportsImagesAreLocked() throws IOException {
            as("ROLE_RADIOLOGIST");
            ImagingStudyImage onReport = image(report(true, ImagingReportStatus.FINAL), callerId);
            ImagingStudyImage onStudy = image(null, callerId);

            assertThatThrownBy(() -> service.delete(onReport.getId()))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("imaging.image.locked");
            assertThatThrownBy(() -> service.delete(onStudy.getId()))
                .as("the signed report interpreted the study's images too")
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("imaging.image.locked");
            assertThat(service.list(order.getId())).allMatch(ImagingStudyImageDTO::locked)
                .noneMatch(ImagingStudyImageDTO::deletable);
            verify(fileUploadService, never()).deleteStoredFile(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("QA D2464 / D2466 (r64): withdrawal after signing, caption correction")
    class WithdrawalAndCaption {

        private ImagingReport signedBy(UUID signerUserId) {
            ImagingReport signed = report(true, ImagingReportStatus.FINAL);
            com.example.hms.model.User user = new com.example.hms.model.User();
            user.setId(signerUserId);
            com.example.hms.model.Staff staff = new com.example.hms.model.Staff();
            staff.setUser(user);
            signed.setSignedBy(staff);
            return signed;
        }

        @Test
        void aRadiologistWithdrawsALockedImageWithAReasonAndEveryReaderStopsSeeingIt() throws IOException {
            as("ROLE_RADIOLOGIST");
            ImagingStudyImage wrong = image(signedBy(UUID.randomUUID()), UUID.randomUUID());
            ImagingStudyImage kept = image(null, callerId);

            service.withdraw(wrong.getId(), "  Mauvais patient  ");

            assertThat(wrong.isWithdrawn()).isTrue();
            assertThat(wrong.getWithdrawalReason()).isEqualTo("Mauvais patient");
            assertThat(wrong.getWithdrawnByUserId()).isEqualTo(callerId);
            verify(fileUploadService, never()).deleteStoredFile(anyString(), anyString());
            assertThat(service.list(order.getId())).extracting(ImagingStudyImageDTO::id).containsExactly(kept.getId());
            assertThatThrownBy(() -> service.readContent(wrong.getId())).isInstanceOf(ResourceNotFoundException.class);
            as("ROLE_HOSPITAL_ADMIN");
            assertThat(service.list(order.getId())).extracting(ImagingStudyImageDTO::id).containsExactly(kept.getId());
            AuditEventRequestDTO audit = audits().stream()
                .filter(a -> a.getEventType() == AuditEventType.IMAGING_IMAGE_WITHDRAWN).findFirst().orElseThrow();
            assertThat(audit.getEventDescription()).doesNotContain("Mauvais");
        }

        @Test
        void theSignerOfTheReportWithdrawsAndAnotherDoctorDoesNot() {
            ImagingStudyImage mine = image(signedBy(callerId), UUID.randomUUID());
            ImagingStudyImage theirs = image(signedBy(UUID.randomUUID()), callerId);

            service.withdraw(mine.getId(), "Image floue, refaite");
            assertThat(mine.isWithdrawn()).isTrue();
            assertThatThrownBy(() -> service.withdraw(theirs.getId(), "raison"))
                .isInstanceOf(ClientSafeAccessDeniedException.class);
            assertThat(theirs.isWithdrawn()).isFalse();
        }

        @Test
        void aReasonIsRequiredAndAnUnlockedImageIsRemovedInstead() {
            as("ROLE_RADIOLOGIST");
            ImagingStudyImage locked = image(signedBy(callerId), callerId);
            ImagingStudyImage unlocked = image(report(false, ImagingReportStatus.PRELIMINARY), callerId);

            assertThatThrownBy(() -> service.withdraw(locked.getId(), "   "))
                .isInstanceOf(BusinessException.class).extracting("messageKey")
                .isEqualTo("imaging.image.withdrawReasonRequired");
            assertThatThrownBy(() -> service.withdraw(unlocked.getId(), "raison"))
                .isInstanceOf(BusinessException.class).extracting("messageKey")
                .isEqualTo("imaging.image.withdrawNotLocked");
            assertThat(locked.isWithdrawn()).isFalse();
        }

        @Test
        void anotherHospitalsImageIsMissing() {
            as("ROLE_RADIOLOGIST");
            ImagingStudyImage image = image(signedBy(callerId), callerId);
            anotherHospital();

            assertThatThrownBy(() -> service.withdraw(image.getId(), "raison"))
                .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.updateCaption(image.getId(), "x"))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void theUploaderCorrectsTheCaptionEvenAfterSigningAndItIsAudited() {
            ImagingStudyImage image = image(signedBy(UUID.randomUUID()), callerId);

            ImagingStudyImageDTO dto = service.updateCaption(image.getId(), " Coupe longitudinale du rein droit ");

            assertThat(dto.caption()).isEqualTo("Coupe longitudinale du rein droit");
            assertThat(image.getCaptionUpdatedByUserId()).isEqualTo(callerId);
            assertThat(audits()).extracting(AuditEventRequestDTO::getEventType)
                .contains(AuditEventType.IMAGING_IMAGE_CAPTION_UPDATED);
        }

        @Test
        void anotherDoctorDoesNotCorrectAColleaguesCaption() {
            ImagingStudyImage image = image(null, UUID.randomUUID());

            assertThatThrownBy(() -> service.updateCaption(image.getId(), "x"))
                .isInstanceOf(ClientSafeAccessDeniedException.class);
        }
    }
}
